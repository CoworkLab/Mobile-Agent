package com.jarvis.assistant.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.R
import com.jarvis.assistant.data.model.LlmProviderConfig
import com.jarvis.assistant.data.model.LlmProviderType
import com.jarvis.assistant.databinding.ActivityApiConnectionBinding
import com.jarvis.assistant.network.LlmClient
import com.jarvis.assistant.util.ThemeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ApiConnectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityApiConnectionBinding
    private val preferences by lazy { JarvisApp.instance.preferences }

    private var currentProviders = mutableListOf<LlmProviderConfig>()
    private var selectedProviderType: LlmProviderType = LlmProviderType.GEMINI
    private var isUpdatingUi = false

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityApiConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.applyDynamicBackground(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        loadAllProviders()
        setupListeners()
        updateUiForCurrentProvider()
    }

    private fun loadAllProviders() {
        currentProviders = preferences.getAllProviderConfigs().toMutableList()
        selectedProviderType = preferences.activeProviderType
    }

    private fun getSelectedConfig(): LlmProviderConfig {
        var cfg = currentProviders.find { it.type == selectedProviderType }
        if (cfg == null) {
            cfg = LlmProviderConfig(type = selectedProviderType)
            currentProviders.add(cfg)
        }
        return cfg
    }

    private fun updateUiForCurrentProvider() {
        isUpdatingUi = true
        val config = getSelectedConfig()

        // Sync Chip
        when (config.type) {
            LlmProviderType.GEMINI -> binding.chipGemini.isChecked = true
            LlmProviderType.GROQ -> binding.chipGroq.isChecked = true
            LlmProviderType.OPENROUTER -> binding.chipOpenRouter.isChecked = true
            LlmProviderType.OLLAMA_CLOUD -> binding.chipOllama.isChecked = true
            LlmProviderType.CUSTOM -> binding.chipCustom.isChecked = true
        }

        binding.tvLiveStatus.text = "ACTIVE PROVIDER: ${config.displayName.uppercase()}"
        binding.tvEndpointSummary.text = "Target: ${config.baseUrl}"
        binding.tvProviderTitle.text = "${config.displayName} Configuration"

        binding.etBaseUrl.setText(config.baseUrl)
        binding.etApiKey.setText(config.apiKey)
        binding.etSelectedModel.setText(config.selectedModel)
        binding.switchVisionEnabled.isChecked = config.isVisionEnabled

        // Update status dot & ping
        if (config.apiKey.isNotBlank() || config.type == LlmProviderType.OLLAMA_CLOUD) {
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
            binding.tvPingResult.text = if (config.lastPingMs > 0) "Ping: ${config.lastPingMs} ms" else "Ready"
        } else {
            binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EAB308"))
            binding.tvPingResult.text = "Key Needed"
        }

        // Populate models spinner
        val models = config.availableModels.ifEmpty { listOf(config.selectedModel) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, models)
        binding.spinnerModels.adapter = adapter
        val selectedIdx = models.indexOf(config.selectedModel)
        if (selectedIdx >= 0) {
            binding.spinnerModels.setSelection(selectedIdx)
        }

        binding.tvVisionStatus.text = if (config.isVisionVerified) {
            "Status: Vision Verified on ${config.selectedModel}"
        } else if (config.isVisionEnabled) {
            "Status: Vision Enabled (Tap Test to verify)"
        } else {
            "Status: Vision Disabled"
        }

        isUpdatingUi = false
    }

    private fun saveCurrentFieldsIntoConfig() {
        if (isUpdatingUi) return
        val config = getSelectedConfig()
        config.baseUrl = binding.etBaseUrl.text?.toString()?.trim().orEmpty().ifEmpty { config.type.defaultBaseUrl }
        config.apiKey = binding.etApiKey.text?.toString()?.trim().orEmpty()
        config.selectedModel = binding.etSelectedModel.text?.toString()?.trim().orEmpty().ifEmpty { config.type.defaultModel }
        config.isVisionEnabled = binding.switchVisionEnabled.isChecked
    }

    private fun setupListeners() {
        // Provider Chip Selection
        binding.chipGroupProviders.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            saveCurrentFieldsIntoConfig()
            val newType = when (checkedIds.first()) {
                R.id.chipGemini -> LlmProviderType.GEMINI
                R.id.chipGroq -> LlmProviderType.GROQ
                R.id.chipOpenRouter -> LlmProviderType.OPENROUTER
                R.id.chipOllama -> LlmProviderType.OLLAMA_CLOUD
                R.id.chipCustom -> LlmProviderType.CUSTOM
                else -> LlmProviderType.GEMINI
            }
            selectedProviderType = newType
            updateUiForCurrentProvider()
        }

        // Paste & Clear API Key
        binding.btnPasteKey.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            val pasted = item?.text?.toString()?.trim().orEmpty()
            if (pasted.isNotBlank()) {
                binding.etApiKey.setText(pasted)
                saveCurrentFieldsIntoConfig()
                Toast.makeText(this, "API Key pasted", Toast.LENGTH_SHORT).show()
                // Auto trigger fetch models on paste
                fetchModelsForCurrentProvider()
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearKey.setOnClickListener {
            binding.etApiKey.setText("")
            saveCurrentFieldsIntoConfig()
        }

        // Spinner Model selection updates the Active Model field
        binding.spinnerModels.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (isUpdatingUi) return
                val chosen = parent?.getItemAtPosition(position)?.toString() ?: return
                binding.etSelectedModel.setText(chosen)
                saveCurrentFieldsIntoConfig()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        // Vision switch toggle
        binding.switchVisionEnabled.setOnCheckedChangeListener { _, isChecked ->
            saveCurrentFieldsIntoConfig()
            val config = getSelectedConfig()
            config.isVisionEnabled = isChecked
            binding.tvVisionStatus.text = if (isChecked) {
                if (config.isVisionVerified) "Status: Vision Verified on ${config.selectedModel}"
                else "Status: Vision Enabled"
            } else {
                "Status: Vision Disabled"
            }
        }

        // Fetch Live Models Button
        binding.btnFetchModels.setOnClickListener {
            fetchModelsForCurrentProvider()
        }

        // Test Connection & Ping Button
        binding.btnTestConnection.setOnClickListener {
            testCurrentConnection()
        }

        // Test Vision Support Button
        binding.btnTestVision.setOnClickListener {
            testVisionSupportForCurrentModel()
        }

        // Save & Activate Provider Button
        binding.btnSaveConnection.setOnClickListener {
            saveCurrentFieldsIntoConfig()
            val config = getSelectedConfig()
            preferences.saveActiveProviderConfig(config)
            preferences.saveAllProviderConfigs(currentProviders)

            // If active provider is Gemini, sync with main Gemini key & endpoint
            if (config.type == LlmProviderType.GEMINI) {
                preferences.apiKey = config.apiKey
            }

            Toast.makeText(this, "Activated ${config.displayName} with ${config.selectedModel}!", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun fetchModelsForCurrentProvider() {
        saveCurrentFieldsIntoConfig()
        val config = getSelectedConfig()

        binding.tvModelFetchStatus.text = "Fetching live models from ${config.displayName}..."
        binding.btnFetchModels.isEnabled = false

        lifecycleScope.launch {
            val result = LlmClient.fetchModels(config)
            binding.btnFetchModels.isEnabled = true

            if (result.isSuccess) {
                val modelsList = result.getOrNull().orEmpty()
                if (modelsList.isNotEmpty()) {
                    config.availableModels = modelsList
                    binding.tvModelFetchStatus.text = "Loaded ${modelsList.size} models from ${config.displayName}!"
                    val adapter = ArrayAdapter(this@ApiConnectionActivity, android.R.layout.simple_spinner_dropdown_item, modelsList)
                    binding.spinnerModels.adapter = adapter
                    val idx = modelsList.indexOf(config.selectedModel)
                    if (idx >= 0) {
                        binding.spinnerModels.setSelection(idx)
                    } else if (modelsList.isNotEmpty()) {
                        binding.spinnerModels.setSelection(0)
                        binding.etSelectedModel.setText(modelsList[0])
                        config.selectedModel = modelsList[0]
                    }
                    Toast.makeText(this@ApiConnectionActivity, "Loaded ${modelsList.size} models", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvModelFetchStatus.text = "No models returned. Using default models."
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to fetch models"
                binding.tvModelFetchStatus.text = "Fetch error: $err"
                Toast.makeText(this@ApiConnectionActivity, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testCurrentConnection() {
        saveCurrentFieldsIntoConfig()
        val config = getSelectedConfig()

        binding.tvPingResult.text = "Pinging..."
        binding.btnTestConnection.isEnabled = false

        lifecycleScope.launch {
            val (success, pingMs) = LlmClient.testPing(config)
            binding.btnTestConnection.isEnabled = true

            if (success) {
                config.lastPingMs = pingMs
                binding.tvPingResult.text = "Ping: $pingMs ms"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
                Toast.makeText(this@ApiConnectionActivity, "Connected to ${config.displayName} in ${pingMs}ms!", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvPingResult.text = "Failed"
                binding.viewStatusDot.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#EF4444"))
                Toast.makeText(this@ApiConnectionActivity, "Connection test failed. Check API key and URL.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun testVisionSupportForCurrentModel() {
        saveCurrentFieldsIntoConfig()
        val config = getSelectedConfig()

        binding.tvVisionStatus.text = "Testing vision support with real test image..."
        binding.btnTestVision.isEnabled = false

        lifecycleScope.launch {
            val (supported, message) = LlmClient.testVisionSupport(config)
            binding.btnTestVision.isEnabled = true

            config.isVisionVerified = supported
            if (supported) {
                config.isVisionEnabled = true
                binding.switchVisionEnabled.isChecked = true
                binding.tvVisionStatus.text = "✅ $message"
                binding.tvVisionStatus.setTextColor(Color.parseColor("#22C55E"))
                Toast.makeText(this@ApiConnectionActivity, "Vision Supported & Verified!", Toast.LENGTH_SHORT).show()
            } else {
                binding.tvVisionStatus.text = "❌ $message"
                binding.tvVisionStatus.setTextColor(Color.parseColor("#EF4444"))
                Toast.makeText(this@ApiConnectionActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
