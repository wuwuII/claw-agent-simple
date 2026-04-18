package com.claw.chat

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.claw.chat.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    // 可选模型列表（与服务端 AVAILABLE_MODELS 对应）
    private val modelList = listOf(
        "kimi-k2.5",
        "kimi-latest",
        "moonshot-v1-8k",
        "moonshot-v1-32k",
        "moonshot-v1-128k"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupModelSpinner()
        loadSettings()
        setupListeners()
    }

    private fun setupModelSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modelList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerModel.adapter = adapter
    }

    private fun loadSettings() {
        val serverUrl = prefs.getString("server_url", getString(R.string.default_server))
            ?: getString(R.string.default_server)
        binding.etServerUrl.setText(serverUrl)

        // 加载已保存模型
        val savedModel = prefs.getString("model", "kimi-k2.5") ?: "kimi-k2.5"
        val idx = modelList.indexOf(savedModel)
        if (idx >= 0) binding.spinnerModel.setSelection(idx)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnSave.setOnClickListener {
            val serverUrl = binding.etServerUrl.text.toString().trim()
            if (serverUrl.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val selectedModel = modelList[binding.spinnerModel.selectedItemPosition]

            prefs.edit()
                .putString("server_url", serverUrl)
                .putString("model", selectedModel)
                .apply()

            Toast.makeText(this, "设置已保存，模型: $selectedModel", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnDefault.setOnClickListener {
            binding.etServerUrl.setText(getString(R.string.default_server))
            val defaultIdx = modelList.indexOf("kimi-k2.5")
            if (defaultIdx >= 0) binding.spinnerModel.setSelection(defaultIdx)
        }
    }
}
