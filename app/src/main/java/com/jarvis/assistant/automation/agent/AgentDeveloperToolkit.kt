package com.jarvis.assistant.automation.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.jarvis.assistant.automation.accessibility.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Developer-grade Android toolkit providing real execution capabilities:
 * - Non-root terminal shell execution
 * - HTTP API Inspector (HTTP Canary / Postman style request inspector)
 * - Android UI hierarchy dump and grounding
 * - Deep package and component inspection
 * - Hardware and system telemetry
 */
object AgentDeveloperToolkit {
    private const val TAG = "AgentDevToolkit"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Executes non-root shell command on the Android device.
     */
    suspend fun executeShell(command: String, maxLines: Int = 80): String = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return@withContext "Empty command"
        Log.i(TAG, "Executing shell command: $trimmed")
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", trimmed))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            var count = 0

            var line: String?
            while (reader.readLine().also { line = it } != null && count < maxLines) {
                output.appendLine(line)
                count++
            }
            val errOutput = StringBuilder()
            while (errorReader.readLine().also { line = it } != null && errOutput.length < 1500) {
                errOutput.appendLine(line)
            }
            process.waitFor(8, TimeUnit.SECONDS)

            val exitVal = process.exitValue()
            buildString {
                if (output.isNotEmpty()) appendLine(output.toString().trimEnd())
                if (errOutput.isNotEmpty()) appendLine("[STDERR]: ${errOutput.toString().trimEnd()}")
                if (output.isEmpty() && errOutput.isEmpty()) appendLine("Command exited with code $exitVal (no output)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing shell: ${e.message}", e)
            "Execution error: ${e.message}"
        }
    }

    /**
     * HTTP Inspector / API Testing Engine (HTTP Canary / Postman capability)
     */
    suspend fun inspectHttp(
        url: String,
        method: String = "GET",
        headersJson: String? = null,
        bodyText: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val reqBuilder = Request.Builder().url(url)
            if (!headersJson.isNullOrBlank()) {
                try {
                    val headersObj = JSONObject(headersJson)
                    val keys = headersObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        reqBuilder.addHeader(k, headersObj.getString(k))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed parsing custom headers: ${e.message}")
                }
            }

            val upperMethod = method.uppercase()
            if (upperMethod in setOf("POST", "PUT", "PATCH", "DELETE")) {
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val reqBody = (bodyText ?: "").toRequestBody(mediaType)
                reqBuilder.method(upperMethod, reqBody)
            } else {
                reqBuilder.method("GET", null)
            }

            val start = System.currentTimeMillis()
            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                val duration = System.currentTimeMillis() - start
                val code = response.code
                val respBody = response.body?.string().orEmpty()
                val respHeaders = response.headers

                buildString {
                    appendLine("HTTP $code ${response.message} (Latency: ${duration}ms)")
                    appendLine("--- Response Headers ---")
                    for (i in 0 until minOf(respHeaders.size, 15)) {
                        appendLine("${respHeaders.name(i)}: ${respHeaders.value(i)}")
                    }
                    appendLine("--- Response Body (first 2500 chars) ---")
                    appendLine(respBody.take(2500))
                    if (respBody.length > 2500) appendLine("...[truncated ${respBody.length - 2500} bytes]")
                }
            }
        } catch (e: Exception) {
            "HTTP Request Failed: ${e.message}"
        }
    }

    /**
     * Inspects package details, version, permissions, and main activities.
     */
    fun inspectPackage(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of((PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES).toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES)
            }

            val appInfo = pInfo.applicationInfo
            val isSystem = (appInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            val requestedPerms = pInfo.requestedPermissions ?: emptyArray()

            buildString {
                appendLine("Package: $packageName")
                appendLine("Version: ${pInfo.versionName} (${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else @Suppress("DEPRECATION") pInfo.versionCode})")
                appendLine("Type: ${if (isSystem) "System App" else "User Installed App"}")
                appendLine("Data Dir: ${appInfo?.dataDir}")
                appendLine("Source APK: ${appInfo?.sourceDir}")
                appendLine("Activities: ${pInfo.activities?.size ?: 0}")
                appendLine("Services: ${pInfo.services?.size ?: 0}")
                appendLine("Requested Permissions (${requestedPerms.size}):")
                requestedPerms.take(20).forEach { appendLine("  - $it") }
                if (requestedPerms.size > 20) appendLine("  ...and ${requestedPerms.size - 20} more")
            }
        } catch (e: Exception) {
            "Package not found or inaccessible: ${e.message}"
        }
    }

    /**
     * Dumps the full visible UI hierarchy of the foreground app.
     */
    fun dumpScreenHierarchy(): String {
        val service = JarvisAccessibilityService.instance ?: return "Accessibility Service not running. Please enable in Settings."
        val root = service.getActiveRootNode() ?: return "No active window root found."
        val result = JSONArray()
        collectNodes(root, result, 0)
        return result.toString(2)
    }

    private fun collectNodes(node: android.view.accessibility.AccessibilityNodeInfo?, array: JSONArray, depth: Int) {
        if (node == null || depth > 10) return
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val viewId = node.viewIdResourceName?.toString().orEmpty()
        val className = node.className?.toString().orEmpty()

        if (node.isClickable || node.isScrollable || node.isEditable || text.isNotEmpty() || desc.isNotEmpty() || viewId.isNotEmpty()) {
            val item = JSONObject().apply {
                if (viewId.isNotEmpty()) put("id", viewId)
                if (text.isNotEmpty()) put("text", text)
                if (desc.isNotEmpty()) put("desc", desc)
                put("class", className.substringAfterLast('.'))
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("bounds", "[${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}]")
                put("center", "[${bounds.centerX()},${bounds.centerY()}]")
            }
            array.put(item)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                collectNodes(child, array, depth + 1)
            }
        }
    }
}
