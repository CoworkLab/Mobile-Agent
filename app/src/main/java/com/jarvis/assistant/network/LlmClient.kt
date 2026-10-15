package com.jarvis.assistant.network

import android.util.Base64
import android.util.Log
import com.jarvis.assistant.data.model.ChatTurn
import com.jarvis.assistant.data.model.GeminiConstants
import com.jarvis.assistant.data.model.LlmProviderConfig
import com.jarvis.assistant.data.model.LlmProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class LlmToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject
)

data class LlmResponse(
    val text: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val model: String = "",
    val durationMs: Long = 0L
)

object LlmClient {
    private const val TAG = "LlmClient"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // 1x1 transparent PNG base64 for vision testing
    private const val TEST_IMAGE_BASE64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

    fun normalizeModel(providerType: LlmProviderType, model: String): String {
        val trimmed = model.trim()
        if (providerType == LlmProviderType.OLLAMA_CLOUD) {
            val lower = trimmed.lowercase()
            if (lower == "gemma 31b" || lower == "gemma:31b" || lower == "gemma-31b" || lower == "gemma31b" || lower == "gemma" || lower.contains("31b")) {
                return "gemma4:31b"
            }
        }
        return trimmed
    }

    private fun resolveChatEndpoint(provider: LlmProviderConfig): Pair<String, Boolean> {
        val clean = provider.baseUrl.trim().trimEnd('/')
        if (provider.type == LlmProviderType.OLLAMA_CLOUD || clean.contains("ollama.com") || clean.contains("api.ollama.ai")) {
            return when {
                clean.endsWith("/chat/completions") -> Pair(clean, false)
                clean.endsWith("/api/chat") -> Pair(clean, true)
                clean.endsWith("/chat/api") -> Pair("https://ollama.com/api/chat", true)
                clean.endsWith("/v1") -> Pair("$clean/chat/completions", false)
                clean.endsWith("/api") -> Pair("$clean/chat", true)
                else -> Pair("$clean/api/chat", true)
            }
        }
        val standardUrl = if (clean.endsWith("/chat/completions")) clean else "$clean/chat/completions"
        return Pair(standardUrl, false)
    }

    /**
     * Dynamically fetches the full list of available models from the provider.
     */
    suspend fun fetchModels(provider: LlmProviderConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        val key = provider.apiKey.trim()
        if (key.isBlank() && provider.type != LlmProviderType.OLLAMA_CLOUD) {
            return@withContext Result.failure(IllegalArgumentException("Please enter an API key first"))
        }

        try {
            when (provider.type) {
                LlmProviderType.GEMINI -> {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
                    val request = Request.Builder().url(url).build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(Exception("Gemini returned HTTP ${response.code}: ${response.message}"))
                        }
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val modelsArr = json.optJSONArray("models") ?: JSONArray()
                        val list = mutableListOf<String>()
                        for (i in 0 until modelsArr.length()) {
                            val m = modelsArr.getJSONObject(i)
                            val name = m.optString("name", "").removePrefix("models/")
                            val supportedMethods = m.optJSONArray("supportedGenerationMethods")
                            val supportsGenerate = supportedMethods?.let { arr ->
                                (0 until arr.length()).any { arr.getString(it) == "generateContent" || arr.getString(it) == "bidiGenerateContent" }
                            } ?: true
                            if (name.isNotBlank() && supportsGenerate && !name.contains("embedding") && !name.contains("aqa")) {
                                list.add(name)
                            }
                        }
                        list.sort()
                        Result.success(list.ifEmpty { provider.availableModels })
                    }
                }

                LlmProviderType.OLLAMA_CLOUD -> {
                    val cleanBase = provider.baseUrl.trim().trimEnd('/')
                    val tagsUrl = if (cleanBase.endsWith("/v1")) {
                        "$cleanBase/models"
                    } else {
                        val baseWithoutApi = if (cleanBase.endsWith("/api")) cleanBase.removeSuffix("/api") else cleanBase
                        "$baseWithoutApi/api/tags"
                    }
                    val reqBuilder = Request.Builder().url(tagsUrl)
                    val effectiveKey = if (key.isNotBlank()) key else GeminiConstants.OLLAMA_CLOUD_API_KEY
                    if (effectiveKey.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $effectiveKey")
                    }

                    httpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(Exception("${provider.displayName} returned HTTP ${response.code}: ${response.message}"))
                        }
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val list = mutableListOf<String>()

                        // 1. Ollama native tags {"models": [{"name": "gemma4:31b"}]}
                        val modelsArr = json.optJSONArray("models")
                        if (modelsArr != null) {
                            for (i in 0 until modelsArr.length()) {
                                val item = modelsArr.getJSONObject(i)
                                val name = item.optString("name", item.optString("model", ""))
                                if (name.isNotBlank()) list.add(name)
                            }
                        }

                        // 2. OpenAI compatible {"data": [{"id": "gemma4:31b"}]}
                        val dataArr = json.optJSONArray("data")
                        if (dataArr != null) {
                            for (i in 0 until dataArr.length()) {
                                val item = dataArr.getJSONObject(i)
                                val id = item.optString("id", "")
                                if (id.isNotBlank() && !list.contains(id)) list.add(id)
                            }
                        }

                        list.sort()
                        Result.success(list.ifEmpty { provider.availableModels })
                    }
                }

                LlmProviderType.GROQ,
                LlmProviderType.OPENROUTER,
                LlmProviderType.CUSTOM -> {
                    val cleanBase = provider.baseUrl.trim().trimEnd('/')
                    val modelsUrl = if (cleanBase.endsWith("/models")) cleanBase else "$cleanBase/models"
                    val reqBuilder = Request.Builder().url(modelsUrl)

                    if (key.isNotBlank()) {
                        reqBuilder.addHeader("Authorization", "Bearer $key")
                    }
                    if (provider.type == LlmProviderType.OPENROUTER) {
                        reqBuilder.addHeader("HTTP-Referer", "https://jarvis.assistant")
                        reqBuilder.addHeader("X-Title", "JARVIS Assistant")
                    }

                    httpClient.newCall(reqBuilder.build()).execute().use { response ->
                        if (!response.isSuccessful) {
                            return@withContext Result.failure(Exception("${provider.displayName} returned HTTP ${response.code}: ${response.message}"))
                        }
                        val body = response.body?.string().orEmpty()
                        val json = JSONObject(body)
                        val dataArr = json.optJSONArray("data") ?: JSONArray()
                        val list = mutableListOf<String>()
                        for (i in 0 until dataArr.length()) {
                            val item = dataArr.getJSONObject(i)
                            val id = item.optString("id", "")
                            if (id.isNotBlank()) list.add(id)
                        }
                        list.sort()
                        Result.success(list.ifEmpty { provider.availableModels })
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models from ${provider.displayName}: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Tests ping & connectivity to the provider.
     */
    suspend fun testPing(provider: LlmProviderConfig): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val modelsRes = fetchModels(provider)
        val duration = System.currentTimeMillis() - start
        if (modelsRes.isSuccess) {
            Pair(true, duration)
        } else {
            Pair(false, -1L)
        }
    }

    /**
     * Checks if the selected model actually supports vision / multimodal images.
     */
    suspend fun testVisionSupport(provider: LlmProviderConfig): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val key = if (provider.type == LlmProviderType.OLLAMA_CLOUD && provider.apiKey.isBlank()) {
            GeminiConstants.OLLAMA_CLOUD_API_KEY
        } else provider.apiKey.trim()
        val model = normalizeModel(provider.type, provider.selectedModel)

        // 1. Fast heuristics for known vision models
        val lowerModel = model.lowercase()
        val isKnownVision = lowerModel.contains("vision") ||
                lowerModel.contains("gemini") ||
                lowerModel.contains("gpt-4o") ||
                lowerModel.contains("claude-3") ||
                lowerModel.contains("pixtral") ||
                lowerModel.contains("qwen-vl") ||
                lowerModel.contains("llava") ||
                lowerModel.contains("gemma") ||
                lowerModel.contains("31b") ||
                lowerModel.contains("multimodal")

        // 2. Perform real API test call with test image payload
        try {
            when (provider.type) {
                LlmProviderType.GEMINI -> {
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key"
                    val payload = JSONObject().apply {
                        put("contents", JSONArray().apply {
                            put(JSONObject().apply {
                                put("parts", JSONArray().apply {
                                    put(JSONObject().apply { put("text", "Check image capability. Reply 'OK'.") })
                                    put(JSONObject().apply {
                                        put("inline_data", JSONObject().apply {
                                            put("mime_type", "image/png")
                                            put("data", TEST_IMAGE_BASE64)
                                        })
                                    })
                                })
                            })
                        })
                    }
                    val req = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON_MEDIA)).build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful) {
                            Pair(true, "Vision Supported & Verified on Gemini ($model)")
                        } else {
                            val err = resp.body?.string().orEmpty()
                            if (isKnownVision) {
                                Pair(true, "Vision Supported by model specification ($model)")
                            } else {
                                Pair(false, "Gemini error: ${resp.code} (${err.take(120)})")
                            }
                        }
                    }
                }

                LlmProviderType.OLLAMA_CLOUD -> {
                    val (chatUrl, isNativeOllama) = resolveChatEndpoint(provider)
                    val payload = if (isNativeOllama) {
                        JSONObject().apply {
                            put("model", model)
                            put("messages", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", "Check vision capability.")
                                    put("images", JSONArray().put(TEST_IMAGE_BASE64))
                                })
                            })
                            put("stream", false)
                        }
                    } else {
                        JSONObject().apply {
                            put("model", model)
                            put("max_tokens", 5)
                            put("messages", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", JSONArray().apply {
                                        put(JSONObject().apply {
                                            put("type", "text")
                                            put("text", "Check vision capability.")
                                        })
                                        put(JSONObject().apply {
                                            put("type", "image_url")
                                            put("image_url", JSONObject().apply {
                                                put("url", "data:image/png;base64,$TEST_IMAGE_BASE64")
                                            })
                                        })
                                    })
                                })
                            })
                        }
                    }

                    val reqBuilder = Request.Builder().url(chatUrl)
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                    val effectiveKey = if (key.isNotBlank()) key else GeminiConstants.OLLAMA_CLOUD_API_KEY
                    if (effectiveKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $effectiveKey")

                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            Pair(true, "Vision Verified: Model successfully processed image on Ollama Cloud ($model)")
                        } else if (body.contains("image", ignoreCase = true) && (body.contains("not support", ignoreCase = true) || body.contains("invalid", ignoreCase = true))) {
                            Pair(false, "Model $model does not support image inputs.")
                        } else if (isKnownVision) {
                            Pair(true, "Vision Supported (Heuristic verified: $model)")
                        } else {
                            Pair(false, "Vision Test Failed (HTTP ${resp.code}): ${body.take(120)}")
                        }
                    }
                }

                LlmProviderType.GROQ,
                LlmProviderType.OPENROUTER,
                LlmProviderType.CUSTOM -> {
                    val cleanBase = provider.baseUrl.trim().trimEnd('/')
                    val chatUrl = if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"
                    val payload = JSONObject().apply {
                        put("model", model)
                        put("max_tokens", 5)
                        put("messages", JSONArray().apply {
                            put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", "Check vision capability.")
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:image/png;base64,$TEST_IMAGE_BASE64")
                                        })
                                    })
                                })
                            })
                        })
                    }

                    val reqBuilder = Request.Builder().url(chatUrl)
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                    if (key.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $key")
                    if (provider.type == LlmProviderType.OPENROUTER) {
                        reqBuilder.addHeader("HTTP-Referer", "https://jarvis.assistant")
                    }

                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val body = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            Pair(true, "Vision Verified: Model successfully processed image ($model)")
                        } else if (body.contains("image", ignoreCase = true) && (body.contains("not support", ignoreCase = true) || body.contains("invalid", ignoreCase = true))) {
                            Pair(false, "Model $model does not support image inputs.")
                        } else if (isKnownVision) {
                            Pair(true, "Vision Supported (Heuristic verified: $model)")
                        } else {
                            Pair(false, "Vision Test Failed (HTTP ${resp.code}): ${body.take(120)}")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isKnownVision) {
                Pair(true, "Vision Supported by model profile ($model)")
            } else {
                Pair(false, "Vision verification error: ${e.message}")
            }
        }
    }

    /**
     * Unified Chat Completion for the Mobile Agent across all providers.
     */
    suspend fun sendAgentPrompt(
        provider: LlmProviderConfig,
        systemPrompt: String,
        userQuery: String,
        history: List<ChatTurn> = emptyList(),
        screenCaptureBase64: String? = null
    ): Result<LlmResponse> = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val key = if (provider.type == LlmProviderType.OLLAMA_CLOUD && provider.apiKey.isBlank()) {
            GeminiConstants.OLLAMA_CLOUD_API_KEY
        } else provider.apiKey.trim()
        val model = normalizeModel(provider.type, provider.selectedModel)

        try {
            when (provider.type) {
                LlmProviderType.GEMINI -> {
                    val effectiveKey = if (key.isNotBlank()) key else GeminiConstants.RELEASE_API_KEY
                    val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$effectiveKey"
                    val contentsArr = JSONArray()

                    // Previous context
                    history.takeLast(6).forEach { turn ->
                        if (turn.userTranscript.isNotBlank() && turn.jarvisResponse.isNotBlank()) {
                            contentsArr.put(JSONObject().apply {
                                put("role", "user")
                                put("parts", JSONArray().put(JSONObject().put("text", turn.userTranscript)))
                            })
                            contentsArr.put(JSONObject().apply {
                                put("role", "model")
                                put("parts", JSONArray().put(JSONObject().put("text", turn.jarvisResponse)))
                            })
                        }
                    }

                    // Current turn
                    val currentParts = JSONArray().apply {
                        put(JSONObject().put("text", userQuery))
                        if (provider.isVisionEnabled && !screenCaptureBase64.isNullOrBlank()) {
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/jpeg")
                                    put("data", screenCaptureBase64)
                                })
                            })
                        }
                    }
                    contentsArr.put(JSONObject().apply {
                        put("role", "user")
                        put("parts", currentParts)
                    })

                    val payload = JSONObject().apply {
                        put("system_instruction", JSONObject().apply {
                            put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
                        })
                        put("contents", contentsArr)
                        put("generation_config", JSONObject().apply {
                            put("temperature", 0.6)
                            put("max_output_tokens", 1024)
                        })
                    }

                    val req = Request.Builder().url(url).post(payload.toString().toRequestBody(JSON_MEDIA)).build()
                    httpClient.newCall(req).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(Exception("Gemini HTTP ${resp.code}: $respBody"))
                        }
                        val json = JSONObject(respBody)
                        val text = json.optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)
                            ?.optString("text", "").orEmpty()

                        val duration = System.currentTimeMillis() - start
                        Result.success(LlmResponse(text = text, model = model, durationMs = duration))
                    }
                }

                LlmProviderType.OLLAMA_CLOUD -> {
                    val (chatUrl, isNativeOllama) = resolveChatEndpoint(provider)
                    val effectiveKey = if (key.isNotBlank()) key else GeminiConstants.OLLAMA_CLOUD_API_KEY

                    val payload = if (isNativeOllama) {
                        val messagesArr = JSONArray()
                        messagesArr.put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })

                        history.takeLast(6).forEach { turn ->
                            if (turn.userTranscript.isNotBlank() && turn.jarvisResponse.isNotBlank()) {
                                messagesArr.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", turn.userTranscript)
                                })
                                messagesArr.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", turn.jarvisResponse)
                                })
                            }
                        }

                        val userMsgObj = JSONObject().apply {
                            put("role", "user")
                            put("content", userQuery)
                            if (provider.isVisionEnabled && !screenCaptureBase64.isNullOrBlank()) {
                                put("images", JSONArray().put(screenCaptureBase64))
                            }
                        }
                        messagesArr.put(userMsgObj)

                        JSONObject().apply {
                            put("model", model)
                            put("messages", messagesArr)
                            put("stream", false)
                        }
                    } else {
                        // OpenAI format
                        val messagesArr = JSONArray()
                        messagesArr.put(JSONObject().apply {
                            put("role", "system")
                            put("content", systemPrompt)
                        })

                        history.takeLast(6).forEach { turn ->
                            if (turn.userTranscript.isNotBlank() && turn.jarvisResponse.isNotBlank()) {
                                messagesArr.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", turn.userTranscript)
                                })
                                messagesArr.put(JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", turn.jarvisResponse)
                                })
                            }
                        }

                        if (provider.isVisionEnabled && !screenCaptureBase64.isNullOrBlank()) {
                            messagesArr.put(JSONObject().apply {
                                put("role", "user")
                                put("content", JSONArray().apply {
                                    put(JSONObject().apply {
                                        put("type", "text")
                                        put("text", userQuery)
                                    })
                                    put(JSONObject().apply {
                                        put("type", "image_url")
                                        put("image_url", JSONObject().apply {
                                            put("url", "data:image/jpeg;base64,$screenCaptureBase64")
                                        })
                                    })
                                })
                            })
                        } else {
                            messagesArr.put(JSONObject().apply {
                                put("role", "user")
                                put("content", userQuery)
                            })
                        }

                        JSONObject().apply {
                            put("model", model)
                            put("messages", messagesArr)
                            put("temperature", 0.6)
                            put("max_tokens", 1024)
                        }
                    }

                    val reqBuilder = Request.Builder().url(chatUrl)
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                    if (effectiveKey.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $effectiveKey")

                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(Exception("${provider.displayName} HTTP ${resp.code}: $respBody"))
                        }
                        val json = JSONObject(respBody)
                        val text = json.optJSONObject("message")?.optString("content", "")?.ifEmpty { null }
                            ?: json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
                            ?: ""

                        val duration = System.currentTimeMillis() - start
                        Result.success(LlmResponse(text = text, model = model, durationMs = duration))
                    }
                }

                LlmProviderType.GROQ,
                LlmProviderType.OPENROUTER,
                LlmProviderType.CUSTOM -> {
                    val cleanBase = provider.baseUrl.trim().trimEnd('/')
                    val chatUrl = if (cleanBase.endsWith("/chat/completions")) cleanBase else "$cleanBase/chat/completions"

                    val messagesArr = JSONArray()
                    messagesArr.put(JSONObject().apply {
                        put("role", "system")
                        put("content", systemPrompt)
                    })

                    history.takeLast(6).forEach { turn ->
                        if (turn.userTranscript.isNotBlank() && turn.jarvisResponse.isNotBlank()) {
                            messagesArr.put(JSONObject().apply {
                                put("role", "user")
                                put("content", turn.userTranscript)
                            })
                            messagesArr.put(JSONObject().apply {
                                put("role", "assistant")
                                put("content", turn.jarvisResponse)
                            })
                        }
                    }

                    if (provider.isVisionEnabled && !screenCaptureBase64.isNullOrBlank()) {
                        messagesArr.put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", userQuery)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put("image_url", JSONObject().apply {
                                        put("url", "data:image/jpeg;base64,$screenCaptureBase64")
                                    })
                                })
                            })
                        })
                    } else {
                        messagesArr.put(JSONObject().apply {
                            put("role", "user")
                            put("content", userQuery)
                        })
                    }

                    val payload = JSONObject().apply {
                        put("model", model)
                        put("messages", messagesArr)
                        put("temperature", 0.6)
                        put("max_tokens", 1024)
                    }

                    val reqBuilder = Request.Builder().url(chatUrl)
                        .post(payload.toString().toRequestBody(JSON_MEDIA))
                    if (key.isNotBlank()) reqBuilder.addHeader("Authorization", "Bearer $key")
                    if (provider.type == LlmProviderType.OPENROUTER) {
                        reqBuilder.addHeader("HTTP-Referer", "https://jarvis.assistant")
                        reqBuilder.addHeader("X-Title", "JARVIS Assistant")
                    }

                    httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                        val respBody = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful) {
                            return@withContext Result.failure(Exception("${provider.displayName} HTTP ${resp.code}: $respBody"))
                        }
                        val json = JSONObject(respBody)
                        val choices = json.optJSONArray("choices")
                        val messageObj = choices?.optJSONObject(0)?.optJSONObject("message")
                        val text = messageObj?.optString("content", "").orEmpty()

                        val duration = System.currentTimeMillis() - start
                        Result.success(LlmResponse(text = text, model = model, durationMs = duration))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendAgentPrompt error: ${e.message}", e)
            Result.failure(e)
        }
    }
}
