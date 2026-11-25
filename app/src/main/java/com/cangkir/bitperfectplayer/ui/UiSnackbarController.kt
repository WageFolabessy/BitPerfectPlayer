package com.cangkir.bitperfectplayer.ui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UiSnackbarMessage(val id: Long, val message: String, val actionLabel: String? = null, val onAction: (() -> Unit)? = null)

interface UiMessageEmitter {
    fun emitSnackbar(message: String, actionLabel: String? = null, onAction: (() -> Unit)? = null)
}

/**
 * Owns transient UI snackbar events emitted by [MainViewModel] and its domain coordinators.
 */
class UiSnackbarController : UiMessageEmitter {
    private val nextSnackbarId = AtomicLong(1L)
    private val _messages = MutableStateFlow<UiSnackbarMessage?>(null)
    val messages: StateFlow<UiSnackbarMessage?> = _messages.asStateFlow()

    override fun emitSnackbar(message: String, actionLabel: String?, onAction: (() -> Unit)?) {
        _messages.value = UiSnackbarMessage(
            id = nextSnackbarId.getAndIncrement(),
            message = message,
            actionLabel = actionLabel,
            onAction = onAction,
        )
    }

    fun clear(expectedId: Long? = null) {
        if (expectedId == null || _messages.value?.id == expectedId) {
            _messages.value = null
        }
    }
}
