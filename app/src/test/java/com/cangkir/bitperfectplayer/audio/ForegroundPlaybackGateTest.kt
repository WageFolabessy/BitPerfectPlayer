package com.cangkir.bitperfectplayer.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundPlaybackGateTest {

    @Test
    fun `immediate pass-through when app is in foreground requires no handshake`() = runBlocking {
        val gate = ForegroundPlaybackGate(
            initialAppInForeground = true,
            initialServiceInForeground = false,
        )

        assertFalse(gate.needsForegroundServiceHandshake())
        assertEquals(
            ForegroundHandshakeResult.READY_IMMEDIATELY,
            gate.awaitServicePromotion(timeoutMs = 50L),
        )
    }

    @Test
    fun `immediate pass-through when service is already promoted requires no handshake`() = runBlocking {
        val gate = ForegroundPlaybackGate(
            initialAppInForeground = false,
            initialServiceInForeground = true,
        )

        assertFalse(gate.needsForegroundServiceHandshake())
        assertEquals(
            ForegroundHandshakeResult.READY_IMMEDIATELY,
            gate.awaitServicePromotion(timeoutMs = 50L),
        )
    }

    @Test
    fun `background resume waits until markServicePromoted fires`() = runBlocking {
        val gate = ForegroundPlaybackGate(
            initialAppInForeground = false,
            initialServiceInForeground = false,
        )
        assertTrue(gate.needsForegroundServiceHandshake())

        val token = gate.beginHandshake()
        val deferredResult = async {
            gate.awaitServicePromotion(timeoutMs = 1_000L, token = token)
        }

        delay(20L)
        assertFalse(deferredResult.isCompleted)

        gate.markServicePromoted()
        assertEquals(ForegroundHandshakeResult.PROMOTED, deferredResult.await())
        assertTrue(gate.isServiceInForeground)
        assertFalse(gate.needsForegroundServiceHandshake())
    }

    @Test
    fun `cancelPendingHandshake cancels in-flight wait cleanly on pause or stop`() = runBlocking {
        val gate = ForegroundPlaybackGate(
            initialAppInForeground = false,
            initialServiceInForeground = false,
        )

        val token = gate.beginHandshake()
        val deferredResult = async {
            gate.awaitServicePromotion(timeoutMs = 1_000L, token = token)
        }

        delay(20L)
        gate.cancelPendingHandshake()

        assertEquals(ForegroundHandshakeResult.CANCELLED, deferredResult.await())
        assertTrue(gate.needsForegroundServiceHandshake())
    }

    @Test
    fun `new track load mid-wait supersedes previous handshake token cleanly`() = runBlocking {
        val gate = ForegroundPlaybackGate(
            initialAppInForeground = false,
            initialServiceInForeground = false,
        )

        val firstToken = gate.beginHandshake()
        val firstWait = async {
            gate.awaitServicePromotion(timeoutMs = 1_000L, token = firstToken)
        }

        delay(20L)
        val secondToken = gate.beginHandshake()
        assertEquals(ForegroundHandshakeResult.CANCELLED, firstWait.await())

        val secondWait = async {
            gate.awaitServicePromotion(timeoutMs = 1_000L, token = secondToken)
        }
        gate.markServicePromoted()
        assertEquals(ForegroundHandshakeResult.PROMOTED, secondWait.await())
    }

    @Test
    fun `markServiceStartFailed propagates FAILED_START_NOT_ALLOWED and records reason`() = runBlocking {
        val gate = ForegroundPlaybackGate(
            initialAppInForeground = false,
            initialServiceInForeground = false,
        )

        // Case 1: synchronous start failure between beginHandshake() and awaitServicePromotion()
        val syncToken = gate.beginHandshake()
        gate.markServiceStartFailed("ForegroundServiceStartNotAllowedException: App in background")
        assertEquals(
            ForegroundHandshakeResult.FAILED_START_NOT_ALLOWED,
            gate.awaitServicePromotion(timeoutMs = 1_000L, token = syncToken),
        )
        assertEquals(
            "ForegroundServiceStartNotAllowedException: App in background",
            gate.lastFailureReason,
        )
        assertFalse(gate.isServiceInForeground)

        // Case 2: asynchronous failure while suspended
        val asyncWait = async {
            gate.awaitServicePromotion(timeoutMs = 1_000L)
        }
        delay(20L)
        gate.markServiceStartFailed("SecurityException: FGS mediaPlayback denied")
        assertEquals(ForegroundHandshakeResult.FAILED_START_NOT_ALLOWED, asyncWait.await())
        assertEquals("SecurityException: FGS mediaPlayback denied", gate.lastFailureReason)

        // Recovery clears failure reason
        gate.markServicePromoted()
        assertNull(gate.lastFailureReason)
    }

    @Test
    fun `bounded timeout returns TIMED_OUT without hanging and allows subsequent handshakes`() = runBlocking {
        val gate = ForegroundPlaybackGate(
            initialAppInForeground = false,
            initialServiceInForeground = false,
        )

        val timedOutResult = gate.awaitServicePromotion(timeoutMs = 40L)
        assertEquals(ForegroundHandshakeResult.TIMED_OUT, timedOutResult)

        // Subsequent handshake still works after a timeout
        val retryToken = gate.beginHandshake()
        gate.markServicePromoted()
        assertEquals(
            ForegroundHandshakeResult.PROMOTED,
            gate.awaitServicePromotion(timeoutMs = 500L, token = retryToken),
        )

        // Stopping service returns gate to background handshake required state
        gate.markServiceStopped()
        assertFalse(gate.isServiceInForeground)
        assertTrue(gate.needsForegroundServiceHandshake())
    }
}
