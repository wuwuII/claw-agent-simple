package com.claw.chat

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class WebSocketClient(
    serverUrl: String,
    private val listener: Listener
) {
    private var client: WebSocketClient? = null
    private var connected = false

    init {
        try {
            val uri = URI(serverUrl)
            client = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    connected = true
                    listener.onConnected()
                }

                override fun onMessage(message: String?) {
                    message?.let { listener.onMessageReceived(it) }
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
        client?.send(text)
    }

    fun isConnected(): Boolean = connected

    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
        fun onMessageReceived(text: String)
    }
}
