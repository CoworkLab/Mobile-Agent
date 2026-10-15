package com.jarvis.assistant.data.model

import org.json.JSONArray
import org.json.JSONObject

enum class LlmProviderType(val displayName: String, val defaultBaseUrl: String, val defaultModel: String) {
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-3.1-flash-preview"),
    GROQ("Groq Cloud", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.0-flash-exp:free"),
    OLLAMA_CLOUD("Ollama Cloud", "https://ollama.com/api", "gemma4:31b"),
    CUSTOM("Custom Provider", "https://api.openai.com/v1", "gpt-4o")
}

data class LlmProviderConfig(
    val type: LlmProviderType,
    val displayName: String = type.displayName,
    var baseUrl: String = type.defaultBaseUrl,
    var apiKey: String = "",
    var selectedModel: String = type.defaultModel,
    var availableModels: List<String> = emptyList(),
    var isVisionEnabled: Boolean = true,
    var isVisionVerified: Boolean = false,
    var lastPingMs: Long = -1L
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("type", type.name)
            put("displayName", displayName)
            put("baseUrl", baseUrl)
            put("apiKey", apiKey)
            put("selectedModel", selectedModel)
            put("availableModels", JSONArray(availableModels))
            put("isVisionEnabled", isVisionEnabled)
            put("isVisionVerified", isVisionVerified)
            put("lastPingMs", lastPingMs)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): LlmProviderConfig {
            val typeStr = json.optString("type", LlmProviderType.GEMINI.name)
            val type = try {
                LlmProviderType.valueOf(typeStr)
            } catch (_: Exception) {
                LlmProviderType.GEMINI
            }
            val modelsArr = json.optJSONArray("availableModels")
            val modelsList = mutableListOf<String>()
            if (modelsArr != null) {
                for (i in 0 until modelsArr.length()) {
                    modelsList.add(modelsArr.optString(i))
                }
            }
            return LlmProviderConfig(
                type = type,
                displayName = json.optString("displayName", type.displayName),
                baseUrl = json.optString("baseUrl", type.defaultBaseUrl),
                apiKey = json.optString("apiKey", ""),
                selectedModel = json.optString("selectedModel", type.defaultModel),
                availableModels = modelsList,
                isVisionEnabled = json.optBoolean("isVisionEnabled", true),
                isVisionVerified = json.optBoolean("isVisionVerified", false),
                lastPingMs = json.optLong("lastPingMs", -1L)
            )
        }

        fun defaultPresets(): List<LlmProviderConfig> = listOf(
            LlmProviderConfig(
                type = LlmProviderType.GEMINI,
                baseUrl = LlmProviderType.GEMINI.defaultBaseUrl,
                apiKey = "AQ.Ab8RN6K5wXGpeWNW8S7xamVFn1jVx4tWfcluZaNsK0wnzRaCCw",
                selectedModel = "gemini-3.1-flash-preview",
                availableModels = listOf(
                    "gemini-3.1-flash-preview",
                    "gemini-3.1-flash-lite-preview",
                    "gemini-3.5-flash",
                    "gemini-3.1-pro-preview",
                    "gemini-2.5-flash"
                ),
                isVisionEnabled = true,
                isVisionVerified = true
            ),
            LlmProviderConfig(
                type = LlmProviderType.GROQ,
                baseUrl = LlmProviderType.GROQ.defaultBaseUrl,
                selectedModel = "llama-3.3-70b-versatile",
                availableModels = listOf(
                    "llama-3.3-70b-versatile",
                    "llama-3.1-8b-instant",
                    "llama-3.2-11b-vision-preview",
                    "llama-3.2-90b-vision-preview",
                    "mixtral-8x7b-32768",
                    "gemma2-9b-it"
                ),
                isVisionEnabled = true
            ),
            LlmProviderConfig(
                type = LlmProviderType.OPENROUTER,
                baseUrl = LlmProviderType.OPENROUTER.defaultBaseUrl,
                selectedModel = "google/gemini-2.0-flash-exp:free",
                availableModels = listOf(
                    "google/gemini-2.0-flash-exp:free",
                    "meta-llama/llama-3.3-70b-instruct",
                    "anthropic/claude-3.5-sonnet",
                    "deepseek/deepseek-chat",
                    "qwen/qwen-2.5-72b-instruct"
                ),
                isVisionEnabled = true
            ),
            LlmProviderConfig(
                type = LlmProviderType.OLLAMA_CLOUD,
                baseUrl = "https://ollama.com/api",
                apiKey = "bde15c32cad14f3d86f05d427e84e375.mgqZnus8A_pHFLKMjsO3uytH",
                selectedModel = "gemma4:31b",
                availableModels = listOf(
                    "gemma4:31b",
                    "gemma4:31b-cloud",
                    "glm-5.3-flash",
                    "deepseek-v4.1-flash",
                    "mistral-large-4",
                    "kimi-k3",
                    "minimax-m3",
                    "gpt-oss:120b"
                ),
                isVisionEnabled = true,
                isVisionVerified = true
            ),
            LlmProviderConfig(
                type = LlmProviderType.CUSTOM,
                baseUrl = "https://api.openai.com/v1",
                selectedModel = "gpt-4o",
                availableModels = listOf("gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet-20241022"),
                isVisionEnabled = true
            )
        )
    }
}
