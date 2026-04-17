package com.claw.agent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Base64
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.claw.agent.databinding.ActivityMainBinding
import com.claw.agent.voice.VoiceChatClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

/**
 * 主界面 v1.1.0
 * - 聊天气泡列表（文字 + 图片）
 * - 文字输入框
 * - 图片选择
 * - 语音/文字切换
 * - 连接稳定性修复
 */
class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERM_REQUEST = 100
        private const val DEFAULT_SERVER = "ws://47.93.240.220:8765"
        private const val MAX_IMAGE_SIZE = 800 // px
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var chatAdapter: ChatAdapter
    private var voiceService: VoiceChatService? = null
    private var isServiceBound = false
    private var isVoiceMode = false
    private var pendingAssistantMsgId: String? = null

    // 图片选择器
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleImageSelected(it) }
    }

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as VoiceChatService.VoiceChatBinder
            voiceService = binder.getService()
            isServiceBound = true

            // 注册文本回调（语音识别结果 + AI回复）
            voiceService?.setTextCallback { text, isFinal ->
                runOnUiThread { handleIncomingText(text, isFinal) }
            }
            // 注册状态回调
            voiceService?.setStateCallback { state ->
                runOnUiThread { updateStatusUI(state) }
            }

            updateStatusUI(voiceService?.getCurrentState() ?: VoiceChatClient.VoiceState.DISCONNECTED)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voiceService = null
            isServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupInputBar()
        setupButtons()
        checkPermissions()
        loadServerUrl()
    }

    override fun onResume() {
        super.onResume()
        bindVoiceService()
    }

    override fun onPause() {
        super.onPause()
        // 不切后台时断开，保持连接
        // unbindVoiceService()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unbindVoiceService()
    }

    // ──────────────────────────────────────────────
    // 初始化
    // ──────────────────────────────────────────────

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        val layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.rvMessages.apply {
            this.layoutManager = layoutManager
            adapter = chatAdapter
        }
        // 欢迎消息（带版本号 v1.1.0）
        addAssistantMessage("你好！我是果冻助手 v1.1.0。你可以文字聊天、发图片，或者按住说话进行语音对话。")
    }

    private fun setupInputBar() {
        // 软键盘发送
        binding.etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTextMessage()
                true
            } else false
        }

        // 监听输入框内容变化，切换发送/语音按钮状态
        binding.etInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                // 有内容时发送按钮高亮
                val hasText = s?.isNotBlank() == true
                binding.btnSend.alpha = if (hasText) 1.0f else 0.5f
            }
        })
    }

    private fun setupButtons() {
        // 连接按钮
        binding.btnConnect.setOnClickListener {
            if (isServiceBound && voiceService?.getCurrentState() != VoiceChatClient.VoiceState.DISCONNECTED) {
                stopVoiceService()
            } else {
                startVoiceService()
            }
        }

        // 设置按钮
        binding.btnSettings.setOnClickListener {
            val isVisible = binding.layoutSettings.visibility == View.VISIBLE
            binding.layoutSettings.visibility = if (isVisible) View.GONE else View.VISIBLE
        }

        // 保存服务器地址
        binding.btnSaveServer.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                saveServerUrl(url)
                binding.layoutSettings.visibility = View.GONE
                Toast.makeText(this, "已保存，重新连接后生效", Toast.LENGTH_SHORT).show()
            }
        }

        // 发送文字
        binding.btnSend.setOnClickListener {
            if (isVoiceMode) {
                // 语音模式不显示发送按钮，此处不处理
            } else {
                sendTextMessage()
            }
        }

        // 图片按钮
        binding.btnImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        // 语音/键盘切换
        binding.btnVoiceToggle.setOnClickListener {
            toggleVoiceMode()
        }

        // 按住说话（仅语音模式下可见）
        binding.btnSpeak.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    voiceService?.startRecording()
                    binding.btnSpeak.text = "松开结束"
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    voiceService?.stopRecording()
                    binding.btnSpeak.text = "按住说话"
                    true
                }
                else -> false
            }
        }
    }

    // ──────────────────────────────────────────────
    // 消息处理
    // ──────────────────────────────────────────────

    private fun sendTextMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return

        if (!isServiceBound || voiceService?.getCurrentState() == VoiceChatClient.VoiceState.DISCONNECTED) {
            Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
            return
        }

        // 显示用户消息
        addUserMessage(text)
        binding.etInput.text?.clear()
        hideKeyboard()

        // 显示"加载中"气泡
        val loadingId = addLoadingAssistantMessage()
        pendingAssistantMsgId = loadingId

        // 发送给服务端
        voiceService?.sendTextMessage(text)
    }

    private fun handleImageSelected(uri: Uri) {
        try {
            val bitmap = loadAndResizeBitmap(uri) ?: return

            if (!isServiceBound || voiceService?.getCurrentState() == VoiceChatClient.VoiceState.DISCONNECTED) {
                Toast.makeText(this, "请先连接服务器", Toast.LENGTH_SHORT).show()
                return
            }

            // 显示用户图片气泡
            chatAdapter.addMessage(ChatMessage(role = ChatMessage.Role.USER, imageBitmap = bitmap))
            scrollToBottom()

            // 转Base64
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
            val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            // 显示加载气泡
            val loadingId = addLoadingAssistantMessage()
            pendingAssistantMsgId = loadingId

            // 发送
            voiceService?.sendImageMessage(b64, "image/jpeg")

        } catch (e: Exception) {
            Log.e(TAG, "图片处理失败", e)
            Toast.makeText(this, "图片处理失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleIncomingText(text: String, isFinal: Boolean) {
        if (text.isEmpty()) return
        if (pendingAssistantMsgId != null) {
            // 更新加载中气泡
            chatAdapter.updateLastMessage(text)
            if (isFinal) pendingAssistantMsgId = null
        } else {
            // 新消息
            if (isFinal) {
                addAssistantMessage(text)
            } else {
                val id = addLoadingAssistantMessage()
                pendingAssistantMsgId = id
                chatAdapter.updateLastMessage(text)
            }
        }
        scrollToBottom()
    }

    private fun addUserMessage(text: String) {
        chatAdapter.addMessage(ChatMessage(role = ChatMessage.Role.USER, text = text))
        scrollToBottom()
    }

    private fun addAssistantMessage(text: String): String {
        val msg = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = text)
        chatAdapter.addMessage(msg)
        scrollToBottom()
        return msg.id
    }

    private fun addLoadingAssistantMessage(): String {
        val msg = ChatMessage(role = ChatMessage.Role.ASSISTANT, text = "...", isLoading = true)
        chatAdapter.addMessage(msg)
        scrollToBottom()
        return msg.id
    }

    private fun scrollToBottom() {
        val itemCount = chatAdapter.itemCount
        if (itemCount > 0) {
            binding.rvMessages.scrollToPosition(itemCount - 1)
        }
    }

    // ──────────────────────────────────────────────
    // 语音/文字模式切换
    // ──────────────────────────────────────────────

    private fun toggleVoiceMode() {
        isVoiceMode = !isVoiceMode
        if (isVoiceMode) {
            binding.etInput.visibility = View.GONE
            binding.btnSpeak.visibility = View.VISIBLE
            binding.btnSend.visibility = View.GONE
            binding.btnVoiceToggle.setImageResource(android.R.drawable.ic_menu_edit)
            binding.btnVoiceToggle.setColorFilter(
                ContextCompat.getColor(this, android.R.color.holo_blue_dark)
            )
        } else {
            binding.etInput.visibility = View.VISIBLE
            binding.btnSpeak.visibility = View.GONE
            binding.btnSend.visibility = View.VISIBLE
            binding.btnVoiceToggle.setImageResource(android.R.drawable.ic_btn_speak_now)
            binding.btnVoiceToggle.clearColorFilter()
        }
    }

    // ──────────────────────────────────────────────
    // 服务管理
    // ──────────────────────────────────────────────

    private fun startVoiceService() {
        val serverUrl = binding.etServerUrl.text.toString().trim().ifEmpty { DEFAULT_SERVER }
        val intent = Intent(this, VoiceChatService::class.java).apply {
            action = VoiceChatService.ACTION_START
            putExtra(VoiceChatService.EXTRA_SERVER_URL, serverUrl)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindVoiceService()
    }

    private fun stopVoiceService() {
        val intent = Intent(this, VoiceChatService::class.java).apply {
            action = VoiceChatService.ACTION_STOP
        }
        startService(intent)
        unbindVoiceService()
        updateStatusUI(VoiceChatClient.VoiceState.DISCONNECTED)
    }

    private fun bindVoiceService() {
        if (!isServiceBound) {
            val intent = Intent(this, VoiceChatService::class.java)
            bindService(intent, serviceConn, Context.BIND_AUTO_CREATE)
        }
    }

    private fun unbindVoiceService() {
        if (isServiceBound) {
            try { unbindService(serviceConn) } catch (e: Exception) { /* 忽略 */ }
            isServiceBound = false
            voiceService = null
        }
    }

    // ──────────────────────────────────────────────
    // UI更新
    // ──────────────────────────────────────────────

    private fun updateStatusUI(state: VoiceChatClient.VoiceState) {
        val (statusText, connectText, speakEnabled) = when (state) {
            VoiceChatClient.VoiceState.DISCONNECTED -> Triple("未连接", "连接", false)
            VoiceChatClient.VoiceState.CONNECTING -> Triple("连接中...", "连接中", false)
            VoiceChatClient.VoiceState.IDLE -> Triple("已连接", "断开", true)
            VoiceChatClient.VoiceState.WAKE_WORD -> Triple("唤醒中...", "断开", false)
            VoiceChatClient.VoiceState.LISTENING -> Triple("聆听中...", "断开", false)
            VoiceChatClient.VoiceState.PROCESSING -> Triple("处理中...", "断开", false)
            VoiceChatClient.VoiceState.SPEAKING -> Triple("播放中...", "断开", false)
        }
        binding.tvStatus.text = statusText
        binding.btnConnect.text = connectText
        binding.btnSpeak.isEnabled = speakEnabled
    }

    // ──────────────────────────────────────────────
    // 工具方法
    // ──────────────────────────────────────────────

    private fun loadAndResizeBitmap(uri: Uri): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }

            val scale = maxOf(
                opts.outWidth / MAX_IMAGE_SIZE,
                opts.outHeight / MAX_IMAGE_SIZE,
                1
            )
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = scale }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载图片失败", e)
            null
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (perms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERM_REQUEST)
        }
    }

    private fun saveServerUrl(url: String) {
        getSharedPreferences("claw_agent", Context.MODE_PRIVATE)
            .edit().putString("server_url", url).apply()
    }

    private fun loadServerUrl() {
        val url = getSharedPreferences("claw_agent", Context.MODE_PRIVATE)
            .getString("server_url", DEFAULT_SERVER) ?: DEFAULT_SERVER
        binding.etServerUrl.setText(url)
    }
}
