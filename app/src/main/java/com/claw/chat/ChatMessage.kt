package com.claw.chat

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val time: String
)
