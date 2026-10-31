package com.audiocopier.app.background

import com.audiocopier.app.data.ObservedAudio

object MonitorRules {
    const val STABILITY_MS = 20_000L
    fun isAac(name: String, mime: String): Boolean {
        val type = mime.substringBefore(';').trim().lowercase()
        if (type in setOf("audio/aac", "audio/aacp", "audio/x-aac", "audio/adts", "audio/vnd.dlna.adts")) return true
        return name.substringAfterLast('.', "").equals("aac", true) &&
            type in setOf("", "application/octet-stream", "binary/octet-stream")
    }
    fun ready(old: ObservedAudio, size: Long, modified: Long, now: Long): Boolean =
        !old.baseline && !old.done && size > 0 && old.size == size && old.modified == modified &&
            now >= old.retryAfter && now - old.stableSince >= STABILITY_MS
    fun retryDelay(failures: Int): Long = (30_000L * (1L shl failures.coerceIn(0, 5))).coerceAtMost(900_000L)
}
