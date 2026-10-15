package com.onionhost.app.tor

enum class TorState {
    STOPPED,
    STARTING,
    BOOTSTRAPPING,
    RUNNING,
    ERROR
}

data class TorStatus(
    val state: TorState = TorState.STOPPED,
    val bootstrapProgress: Int = 0,
    val onionAddress: String = "",
    val errorMessage: String? = null,
    val isHosting: Boolean = false
)
