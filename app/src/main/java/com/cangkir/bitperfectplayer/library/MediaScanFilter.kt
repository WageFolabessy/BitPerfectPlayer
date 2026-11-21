package com.cangkir.bitperfectplayer.library

import com.cangkir.bitperfectplayer.audio.SupportedAudioFormat

/**
 * Pure-Kotlin MediaStore auto-scan filter (zero `android.*` imports).
 *
 * Excludes non-music clutter such as WhatsApp Audio / Voice Notes, call & voice recordings
 * (including Xiaomi MIUI/HyperOS `MIUI/sound_recorder`), Telegram/messaging voice clips,
 * system ringtones/notifications/alarms, app data/media caches, and short SFX clips,
 * while preserving legitimate local music libraries.
 *
 * Note: Manual Storage Access Framework (SAF) folder/file imports intentionally bypass this
 * filter because they are explicitly chosen by the user.
 */
object MediaScanFilter {

    const val MIN_AUTO_SCAN_DURATION_MS: Long = 15_000L

    data class MediaStoreScanCandidate(
        val displayName: String,
        val relativePath: String? = null,
        val legacyDataPath: String? = null,
        val bucketName: String? = null,
        val durationMs: Long = 0L,
        val sizeBytes: Long = 0L,
        val format: SupportedAudioFormat,
        val isMusic: Boolean = true,
        val isRingtone: Boolean = false,
        val isAlarm: Boolean = false,
        val isNotification: Boolean = false,
        val isRecording: Boolean = false,
    )

    private val excludedPathSegments = setOf(
        "ringtones",
        "notifications",
        "alarms",
        "recordings",
        "recording",
        "sound_recorder",
        "soundrecorder",
        "voicerecorder",
        "voice_recorder",
        "voice recorder",
        "call_rec",
        "callrec",
        "call_recordings",
        "call recordings",
        "callrecording",
        "voice_notes",
        "voicenotes",
        "voice notes",
        "voice_memos",
        "voicememos",
        "voice memos",
        "dictaphone",
        "whatsapp",
        "whatsapp audio",
        "whatsapp voice notes",
        "whatsapp documents",
        "telegram",
        "telegram audio",
        "telegram voice",
        "signal",
        "viber",
        "wechat",
        "messenger",
        "discord",
        "snapchat",
        "tiktok",
        "instagram",
        "dcim",
        "cache",
        ".cache",
        ".thumbnails",
        "thumbnails",
        "temp",
        "tmp",
        "ui",
        "system",
    )

    private val excludedPathSubstrings = listOf(
        "android/data/",
        "android/obb/",
        "android/media/",
        "com.whatsapp",
        "whatsapp/",
        "whatsapp audio",
        "whatsapp voice",
        "miui/sound_recorder",
        "miui/ringtone",
        "miui/theme",
        "miui/gallery",
        "sound_recorder",
        "call_rec",
        "call recording",
        "voice recorder",
        "voice note",
        "voice memo",
        "screenrecorder",
        "screen_recorder",
        "telegram audio",
        "telegram voice",
        "/recordings/",
        "/ringtones/",
        "/notifications/",
        "/alarms/",
    )

    private val excludedFileNameRegexes = listOf(
        // WhatsApp voice notes & forwarded audio: PTT-20260602-WA0001.opus, AUD-20260602-WA0003.mp3
        Regex("""^ptt-\d{6,8}-wa\d+""", RegexOption.IGNORE_CASE),
        Regex("""^aud-\d{6,8}-wa\d+""", RegexOption.IGNORE_CASE),
        // Common call & voice recorder prefixes (MIUI/HyperOS, AOSP, Samsung, etc.)
        Regex("""^call[@_\-\s]""", RegexOption.IGNORE_CASE),
        Regex("""^callrec[_\-\s]?""", RegexOption.IGNORE_CASE),
        Regex("""^(voice|audio)[_\-\s]?(rec|record|recording|note|memo)""", RegexOption.IGNORE_CASE),
        Regex("""^(rec|recording)[_\-\s]\d{4,}""", RegexOption.IGNORE_CASE),
        Regex("""^record[_\-\s]?\d{6,}""", RegexOption.IGNORE_CASE),
        Regex("""^standard recording\b""", RegexOption.IGNORE_CASE),
        Regex("""^new recording\b""", RegexOption.IGNORE_CASE),
    )

    fun shouldIncludeInAutoScan(candidate: MediaStoreScanCandidate): Boolean {
        if (candidate.sizeBytes <= 0L || isExcludedMediaType(candidate)) return false

        val normalizedName = candidate.displayName.trim()
        val isHiddenOrRejectedName = normalizedName.isEmpty() ||
            normalizedName.startsWith(".") ||
            excludedFileNameRegexes.any { it.containsMatchIn(normalizedName) }
        if (isHiddenOrRejectedName || isExcludedPath(candidate)) {
            return false
        }

        val isLossless = candidate.format == SupportedAudioFormat.FLAC ||
            candidate.format == SupportedAudioFormat.WAV

        // Filter out short SFX/notification/voice clips (< 15 seconds).
        // Allow durationMs == 0 only for lossless FLAC/WAV where MediaStore may omit duration until header read.
        val isTooShort = candidate.durationMs in 1L until MIN_AUTO_SCAN_DURATION_MS
        val missingLossyDuration = candidate.durationMs <= 0L && !isLossless
        val lacksMusicClassification = !candidate.isMusic && !isLossless
        return !isTooShort && !missingLossyDuration && !lacksMusicClassification
    }

    private fun isExcludedMediaType(candidate: MediaStoreScanCandidate): Boolean {
        val isSystemAlert = candidate.isRingtone || candidate.isAlarm || candidate.isNotification
        return isSystemAlert || candidate.isRecording
    }

    private fun isExcludedPath(candidate: MediaStoreScanCandidate): Boolean {
        val pathCombined = buildList {
            candidate.relativePath?.takeIf { it.isNotBlank() }?.let { add(it) }
            candidate.legacyDataPath?.takeIf { it.isNotBlank() }?.let { add(it) }
            candidate.bucketName?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString("/")
            .replace('\\', '/')
            .lowercase()

        if (pathCombined.isEmpty()) return false
        val wrappedPath = "/$pathCombined/"
        if (excludedPathSubstrings.any { wrappedPath.contains(it) }) return true

        val segments = pathCombined.split('/').filter { it.isNotBlank() }
        return segments.any { isExcludedPathSegment(it) }
    }

    private fun isExcludedPathSegment(segment: String): Boolean {
        if (segment.startsWith(".") || segment in excludedPathSegments) return true
        return excludedSegmentSubstrings.any { segment.contains(it) }
    }

    private val excludedSegmentSubstrings = listOf(
        "whatsapp",
        "sound_recorder",
        "call_rec",
        "voicerecorder",
    )
}
