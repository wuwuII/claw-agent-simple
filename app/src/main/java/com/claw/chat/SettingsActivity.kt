package com.claw.chat

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.claw.chat.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadSettings()
        setupListeners()
    }

    private fun loadSettings() {
        val serverUrl = prefs.getString("server_url", getString(R.string.default_server))
            ?: getString(R.string.default_server)
        binding.etServerUrl.setText(serverUrl)
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

            prefs.edit().putString("server_url", serverUrl).apply()
            Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnDefault.setOnClickListener {
            binding.etServerUrl.setText(getString(R.string.default_server))
        }
    }
}
