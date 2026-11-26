package com.cangkir.bitperfectplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cangkir.bitperfectplayer.audio.AudioOutputEngine
import com.cangkir.bitperfectplayer.audio.AudioOutputMode
import com.cangkir.bitperfectplayer.audio.AudioSafetyController
import com.cangkir.bitperfectplayer.audio.EngineTrackRequest
import com.cangkir.bitperfectplayer.audio.PlaybackStatus
import com.cangkir.bitperfectplayer.audio.WavPcmDecoder
import com.cangkir.bitperfectplayer.lyrics.FileChannelSeekableByteSource
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device audio pipeline smoke test generating tiny 16-bit and 24-bit silent RIFF/WAV files in
 * app cache, verifying [WavPcmDecoder] header/PCM integrity and [AudioOutputEngine] clean
 * `SAFE_VOLUME_MODE` load/play/pause/stop/release lifecycle without ever auto-activating
 * `BIT_PERFECT_OPT_IN`.
 */
@RunWith(AndroidJUnit4::class)
class AudioPipelineSmokeInstrumentedTest {

    private lateinit var targetContext: Context
    private val tempFiles = mutableListOf<File>()
    private var activeEngine: AudioOutputEngine? = null

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        runCatching { activeEngine?.release() }
        activeEngine = null
        tempFiles.forEach { file -> runCatching { file.delete() } }
        tempFiles.clear()
    }

    @Test
    fun wavPcmDecoder_decodes16BitAnd24BitTempFilesCleanlyOnDevice() {
        val wav16 = createTempSilentWavFile("smoke_16bit.wav", sampleRateHz = 44_100, bitsPerSample = 16, frameCount = 4_410)
        val wav24 = createTempSilentWavFile("smoke_24bit.wav", sampleRateHz = 48_000, bitsPerSample = 24, frameCount = 4_800)
        val supportsNative24 = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        FileInputStream(wav16).use { fis ->
            WavPcmDecoder(FileChannelSeekableByteSource(fis.channel, wav16.length()), supportsNative24).use { decoder ->
                assertEquals(44_100, decoder.format.sampleRateHz)
                assertEquals(16, decoder.format.sourceBitDepth)
                assertEquals(16, decoder.format.effectiveBitDepth)
                val readBytes = decoder.readPcm(ByteArray(4_096))
                assertTrue("16-bit WAV should read positive PCM bytes", readBytes > 0)
                assertEquals(50L, decoder.seekToMs(50L))
            }
        }

        FileInputStream(wav24).use { fis ->
            WavPcmDecoder(FileChannelSeekableByteSource(fis.channel, wav24.length()), supportsNative24).use { decoder ->
                assertEquals(48_000, decoder.format.sampleRateHz)
                assertEquals(24, decoder.format.sourceBitDepth)
                assertEquals(24, decoder.format.effectiveBitDepth)
                val readBytes = decoder.readPcm(ByteArray(4_096))
                assertTrue("24-bit WAV should read positive PCM bytes", readBytes > 0)
                assertEquals(50L, decoder.seekToMs(50L))
            }
        }
    }

    @Test
    fun audioOutputEngine_safeModePlayPauseStopLifecycleCompletesWithoutCrash() {
        val wav16 = createTempSilentWavFile("engine_smoke_16bit.wav", sampleRateHz = 48_000, bitsPerSample = 16, frameCount = 48_000)
        val safetyController = AudioSafetyController()
        val engine = AudioOutputEngine(context = targetContext, safetyController = safetyController)
        activeEngine = engine

        // Hearing safety invariant: Safe Mode is strictly default and Bit-Perfect never auto-activates.
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, safetyController.state().mode)

        engine.loadTrack(
            EngineTrackRequest(
                uri = Uri.fromFile(wav16),
                fileName = wav16.name,
                mimeType = "audio/wav",
                autoPlay = true,
                expectedDurationMs = 1_000L,
                expectedContainer = "WAV",
                expectedCodec = "Linear PCM",
                expectedSampleRateHz = 48_000,
                expectedBitDepth = 16,
            ),
        )

        awaitCondition("Engine should exit PREPARING cleanly") {
            engine.state.value.status != PlaybackStatus.PREPARING &&
                engine.state.value.status != PlaybackStatus.IDLE
        }
        assertNotEquals(
            "Safe-Mode WAV smoke playback must not enter ERROR (${engine.state.value.errorMessage})",
            PlaybackStatus.ERROR,
            engine.state.value.status,
        )
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, safetyController.state().mode)

        engine.pause()
        awaitCondition("Engine should pause cleanly") {
            engine.state.value.status == PlaybackStatus.PAUSED ||
                engine.state.value.status == PlaybackStatus.COMPLETED
        }

        engine.stop()
        awaitCondition("Engine should stop cleanly back to IDLE") {
            engine.state.value.status == PlaybackStatus.IDLE
        }
        assertEquals(AudioOutputMode.SAFE_VOLUME_MODE, safetyController.state().mode)

        engine.release()
        activeEngine = null
    }

    private fun createTempSilentWavFile(fileName: String, sampleRateHz: Int, bitsPerSample: Int, frameCount: Int): File {
        val channels = 2
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channels * bytesPerSample
        val byteRate = sampleRateHz * blockAlign
        val dataSize = frameCount * blockAlign
        val riffChunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(riffChunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16) // PCM fmt chunk size
            putShort(1) // WAVE_FORMAT_PCM
            putShort(channels.toShort())
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val file = File(targetContext.cacheDir, fileName)
        tempFiles.add(file)
        FileOutputStream(file).use { out ->
            out.write(header)
            out.write(ByteArray(dataSize)) // Silent PCM samples (hearing-safe even at any speaker/IEM gain)
        }
        return file
    }

    private fun awaitCondition(description: String, timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(20L)
        }
        assertTrue("Timed out waiting for: $description", condition())
    }
}
