package com.onionhost.app.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Carries Android ACTION_VIEW chat invites into the Compose navigation layer. */
object ChatInviteLinkStore {
    private val _pendingLink = MutableStateFlow<String?>(null)
    val pendingLink = _pendingLink.asStateFlow()

    fun submit(value: String?) {
        if (!value.isNullOrBlank()) _pendingLink.value = value
    }

    fun consume(value: String) {
        if (_pendingLink.value == value) _pendingLink.value = null
    }
}
