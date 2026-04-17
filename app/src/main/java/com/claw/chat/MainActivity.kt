package com.claw.chat

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.claw.chat.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private val messages = mutableListOf<ChatMessage>()
    private var webSocketClient: WebSocketClient? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupListeners()
        addWelcomeMessage()
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter(messages)
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = chatAdapter
        }
    }

    private fun setupListeners() {
        binding.btnConnect.setOnClickListener {
            if (webSocketClient?.isConnected() == true) {
                disconnect()
            } else {
                connect()
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnSend.setOnClickListener {
            sendMessage()
        }

        binding.etInput.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    private fun connect() {
        val serverUrl = prefs.getString("server_url", getString(R.string.default_server))
            ?: getString(R.string.default_server)

        webSocketClient = WebSocketClient(serverUrl, object : WebSocketClient.Listener {
            override fun onConnected() {
                runOnUiThread {
                    updateConnectionStatus(true)
                    Toast.makeText(this@MainActivity, "已连接", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onDisconnected() {
                runOnUiThread {
                    updateConnectionStatus(false)
                }
            }

            override fun onError(error: String) {
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "错误: $error", Toast.LENGTH_SHORT).show()
                    updateConnectionStatus(false)
                }
            }

            override fun onMessageReceived(text: String) {
                runOnUiThread {
                    addAssistantMessage(text)
                }
            }
        })

        webSocketClient?.connect()
    }

    private fun disconnect() {
        webSocketClient?.disconnect()
        webSocketClient = null
        updateConnectionStatus(false)
    }

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return

        if (webSocketClient?.isConnected() != true) {
            Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
            return
        }

        addUserMessage(text)
        webSocketClient?.sendMessage(text)
        binding.etInput.text?.clear()
    }

    private fun addWelcomeMessage() {
        addAssistantMessage("你好！我是果冻助手 v1.0.0。你可以文字聊天，或者按住说话进行语音对话。")
    }

    private fun addUserMessage(text: String) {
        val message = ChatMessage(
            text = text,
            isUser = true,
            time = getCurrentTime()
        )
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)
    }

    private fun addAssistantMessage(text: String) {
        val message = ChatMessage(
            text = text,
            isUser = false,
            time = getCurrentTime()
        )
        messages.add(message)
        chatAdapter.notifyItemInserted(messages.size - 1)
        binding.rvMessages.scrollToPosition(messages.size - 1)
    }

    private fun updateConnectionStatus(connected: Boolean) {
        if (connected) {
            binding.tvStatus.text = "已连接"
            binding.btnConnect.text = "断开"
        } else {
            binding.tvStatus.text = "未连接"
            binding.btnConnect.text = "连接"
        }
    }

    private fun getCurrentTime(): String {
        return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }
}
