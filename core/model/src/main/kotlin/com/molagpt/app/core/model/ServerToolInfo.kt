package com.molagpt.app.core.model

import kotlinx.serialization.Serializable

@Serializable
data class ServerToolInfo(
    val callId: String,
    val icon: String? = null,
    val runningLabel: String? = null,
    val metadata: String? = null,
    val error: String? = null,
    val retryTicket: String? = null,
    val fileTicket: String? = null,
    val filename: String? = null,
)
