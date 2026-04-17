package com.claw.agent.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 璇煶瀵硅瘽瀹㈡埛绔?
 * 杩炴帴鏈嶅姟绔繘琛岃闊宠瘑鍒€丄I瀵硅瘽銆佽闊冲悎鎴?
 */
class VoiceChatClient(
    private val context: Context,
    private val serverUrl: String,
    private val deviceId: String,
    private val onStateChanged: (VoiceState) -> Unit,
    private val onError: (String) -> Unit,
    private val onTextReceived: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "VoiceChatClient"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = SAMPLE_RATE * 2 // 1绉掔紦鍐插尯
    }

    enum class VoiceState {
        DISCONNECTED,   // 鏈繛鎺?
        CONNECTING,     // 杩炴帴涓?
        IDLE,           // 绌洪棽锛堢瓑寰呭敜閱掞級
        WAKE_WORD,      // 鍞ら啋璇嶆娴嬩腑
        LISTENING,      // 鑱嗗惉涓?
        PROCESSING,     // 澶勭悊涓?
        SPEAKING        // 鎾斁涓?
    }

    private var webSocket: WebSocketClient? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentState = VoiceState.DISCONNECTED
    private var isRecording = AtomicBoolean(false)

    // 闊抽缂撳啿鍖?
    private val audioBuffer = mutableListOf<Byte>()

    /**
     * 杩炴帴鍒版湇鍔＄
     */
    fun connect() {
        if (currentState != VoiceState.DISCONNECTED) return

        updateState(VoiceState.CONNECTING)

        try {
            val uri = URI(serverUrl)
            webSocket = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.d(TAG, "WebSocket杩炴帴鎴愬姛")
                    // 鍙戦€佹敞鍐屾秷鎭?
                    sendRegister()
                }

                override fun onMessage(message: String?) {
                    message?.let { handleMessage(it) }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.d(TAG, "WebSocket鍏抽棴: $reason")
                    updateState(VoiceState.DISCONNECTED)
                }

                override fun onError(ex: Exception?) {
                    Log.e(TAG, "WebSocket閿欒", ex)
                    mainHandler.post { onError("杩炴帴閿欒: ${ex?.message}") }
                    updateState(VoiceState.DISCONNECTED)
                }
            }
            webSocket?.connect()
        } catch (e: Exception) {
            Log.e(TAG, "杩炴帴澶辫触", e)
            onError("杩炴帴澶辫触: ${e.message}")
            updateState(VoiceState.DISCONNECTED)
        }
    }

    /**
     * 鏂紑杩炴帴
     */
    fun disconnect() {
        stopRecording()
        stopSpeaking()
        webSocket?.close()
        webSocket = null
        updateState(VoiceState.DISCONNECTED)
    }

    /**
     * 鍙戦€佹敞鍐屾秷鎭?
     */
    private fun sendRegister() {
        val message = JSONObject().apply {
            put("type", "register")
            put("device_id", deviceId)
            put("version", "2.0.0")
        }
        webSocket?.send(message.toString())
    }

    /**
     * 閫氱煡鏈嶅姟绔敜閱掕瘝宸叉娴?
     */
    fun notifyWakeWordDetected() {
        if (currentState == VoiceState.IDLE) {
            val message = JSONObject().apply {
                put("type", "wake_word_detected")
            }
            webSocket?.send(message.toString())
            updateState(VoiceState.WAKE_WORD)
        }
    }

    /**
     * 寮€濮嬪綍闊?
     */
    fun startRecording() {
        if (isRecording.get()) return

        scope.launch {
            try {
                // 鍙戦€佸紑濮嬪綍闊虫秷鎭?
                val message = JSONObject().apply {
                    put("type", "start_recording")
                }
                webSocket?.send(message.toString())

                isRecording.set(true)
                audioBuffer.clear()
                updateState(VoiceState.LISTENING)

                // 鍒濆鍖朅udioRecord
                val minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
                )
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    maxOf(minBufferSize, BUFFER_SIZE)
                )

                audioRecord?.startRecording()

                // 褰曢煶寰幆
                val buffer = ByteArray(640) // 20ms @ 16kHz
                while (isRecording.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        // 娣诲姞鍒扮紦鍐插尯
                        synchronized(audioBuffer) {
                            audioBuffer.addAll(buffer.take(read))
                        }

                        // 姣?00ms鍙戦€佷竴娆￠煶棰戞暟鎹?
                        if (audioBuffer.size >= SAMPLE_RATE) {
                            sendAudioChunk(false)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "褰曢煶澶辫触", e)
                mainHandler.post { onError("褰曢煶澶辫触: ${e.message}") }
                stopRecording()
            }
        }
    }

    /**
     * 鍙戦€侀煶棰戞暟鎹潡
     */
    private fun sendAudioChunk(isFinal: Boolean) {
        val audioData = synchronized(audioBuffer) {
            val data = audioBuffer.toByteArray()
            audioBuffer.clear()
            data
        }

        if (audioData.isNotEmpty()) {
            val base64Audio = Base64.encodeToString(audioData, Base64.NO_WRAP)
            val message = JSONObject().apply {
                put("type", "audio_data")
                put("audio", base64Audio)
                put("is_final", isFinal)
                put("sample_rate", SAMPLE_RATE)
            }
            webSocket?.send(message.toString())
        }
    }

    /**
     * 鍋滄褰曢煶
     */
    fun stopRecording() {
        isRecording.set(false)

        // 鍙戦€佸墿浣欓煶棰?
        sendAudioChunk(true)

        // 鍙戦€佸仠姝㈠綍闊虫秷鎭?
        val message = JSONObject().apply {
            put("type", "stop_recording")
        }
        webSocket?.send(message.toString())

        // 閲婃斁AudioRecord
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "鍋滄褰曢煶澶辫触", e)
        }
        audioRecord = null

        updateState(VoiceState.PROCESSING)
    }

    /**
     * 澶勭悊鏈嶅姟绔秷鎭?
     */
    private fun handleMessage(message: String) {
        try {
            val json = JSONObject(message)
            val type = json.optString("type", "")

            when (type) {
                "registered" -> {
                    Log.d(TAG, "娉ㄥ唽鎴愬姛")
                    updateState(VoiceState.IDLE)
                }

                "wake_word_confirmed" -> {
                    Log.d(TAG, "鍞ら啋纭")
                    // 鑷姩寮€濮嬪綍闊?
                    startRecording()
                }

                "recording_started" -> {
                    Log.d(TAG, "褰曢煶宸插紑濮?)
                }

                "asr_processing" -> {
                    Log.d(TAG, "ASR澶勭悊涓?)
                    mainHandler.post { onTextReceived("璇嗗埆涓?..") }
                }

                "asr_result" -> {
                    val text = json.optString("text", "")
                    Log.d(TAG, "ASR缁撴灉: $text")
                    mainHandler.post { onTextReceived(text) }
                }

                "llm_processing" -> {
                    Log.d(TAG, "LLM澶勭悊涓?)
                    mainHandler.post { onTextReceived("鎬濊€冧腑...") }
                }

                "llm_result" -> {
                    val text = json.optString("text", "")
                    Log.d(TAG, "LLM缁撴灉: $text")
                    mainHandler.post { onTextReceived(text) }
                }

                "tts_processing" -> {
                    Log.d(TAG, "TTS澶勭悊涓?)
                }

                "tts_result" -> {
                    val audioBase64 = json.optString("audio", "")
                    val format = json.optString("format", "wav")
                    if (audioBase64.isNotEmpty()) {
                        playAudio(audioBase64, format)
                    }
                }

                "error" -> {
                    val errorMsg = json.optString("message", "鏈煡閿欒")
                    Log.e(TAG, "鏈嶅姟绔敊璇? $errorMsg")
                    mainHandler.post {
                        onError(errorMsg)
                        onTextReceived("閿欒: $errorMsg")
                    }
                    updateState(VoiceState.IDLE)
                }

                "pong" -> {
                    // 蹇冭烦鍝嶅簲
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "澶勭悊娑堟伅澶辫触", e)
        }
    }

    /**
     * 鎾斁闊抽
     */
    private fun playAudio(audioBase64: String, format: String) {
        scope.launch {
            try {
                updateState(VoiceState.SPEAKING)

                val audioData = Base64.decode(audioBase64, Base64.NO_WRAP)

                // 鍒濆鍖朅udioTrack
                val bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()

                audioTrack?.play()
                audioTrack?.write(audioData, 0, audioData.size)

                // 绛夊緟鎾斁瀹屾垚
                while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    delay(100)
                }

                stopSpeaking()
                updateState(VoiceState.IDLE)

            } catch (e: Exception) {
                Log.e(TAG, "鎾斁闊抽澶辫触", e)
                mainHandler.post { onError("鎾斁澶辫触: ${e.message}") }
                updateState(VoiceState.IDLE)
            }
        }
    }

    /**
     * 鍋滄鎾斁
     */
    fun stopSpeaking() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "鍋滄鎾斁澶辫触", e)
        }
        audioTrack = null
    }

    /**
     * 鏇存柊鐘舵€?
     */
    private fun updateState(newState: VoiceState) {
        currentState = newState
        mainHandler.post { onStateChanged(newState) }
    }

    /**
     * 鑾峰彇褰撳墠鐘舵€?
     */
    fun getCurrentState(): VoiceState = currentState

    /**
     * 鏄惁宸茶繛鎺?
     */
    fun isConnected(): Boolean = currentState != VoiceState.DISCONNECTED

    /**
     * 鍙戦€佸績璺?
     */
    fun sendPing() {
        val message = JSONObject().apply {
            put("type", "ping")
        }
        webSocket?.send(message.toString())
    }

    /**
     * 閲婃斁璧勬簮
     */
    fun release() {
        disconnect()
        scope.cancel()
    }
}
