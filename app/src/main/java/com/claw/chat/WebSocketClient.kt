package com.claw.chat

import android.util.Log
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.util.UUID

class WebSocketClient(
    serverUrl: String,
    private val listener: Listener
) {
    private var client: WebSocketClient? = null
    private var connected = false
    private val deviceId: String = UUID.randomUUID().toString()

    init {
        try {
            val uri = URI(serverUrl)
            client = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    connected = true
                    // 连接后立即发送注册消息
                    val register = JSONObject().apply {
                        put("type", "register")
                        put("device_id", deviceId)
                    }
                    send(register.toString())
                    listener.onConnected()
                }

                override fun onMessage(message: String?) {
                    message?.let {
                        try {
                            val json = JSONObject(it)
                            val type = json.optString("type")
                            when (type) {
                                "registered" -> {
                                    Log.d("WebSocket", "注册成功")
                                }
                                "text_response" -> {
                                    val text = json.optString("text", "")
                                    if (text.isNotEmpty()) {
                                        listener.onMessageReceived(text)
                                    } else {
                                        Unit
                                    }
                                }
                                "processing" -> {
                                    Log.d("WebSocket", "服务端处理中...")
                                }
                                "ping" -> {
                                    Log.d("WebSocket", "收到心跳")
                                }
                                "error" -> {
                                    val msg = json.optString("message", "未知错误")
                                    listener.onMessageReceived("错误: $msg")
                                }
                                else -> {
                                    listener.onMessageReceived(it)
                                }
                            }
                        } catch (e: Exception) {
                            listener.onMessageReceived(it)
                        }
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    connected = false
                    listener.onDisconnected()
                }

                override fun onError(ex: Exception?) {
                    connected = false
                    listener.onError(ex?.message ?: "Unknown error")
                }
            }
        } catch (e: Exception) {
            listener.onError(e.message ?: "Invalid URL")
        }
    }

    fun connect() {
        client?.connect()
    }

    fun disconnect() {
        client?.close()
        connected = false
    }

    fun sendMessage(text: String) {
        val json = JSONObject().apply {
            put("type", "text_message")
            put("text", text)
            put("device_id", deviceId)
        }
        client?.send(json.toString())
    }

    fun isConnected(): Boolean = connected

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onMessageReceived(text: String)
    }
}
