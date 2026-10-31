package com.audiocopier.app.copying

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

enum class Problem {
    PERMISSION, MISSING, IO, SPACE, LOCAL_ONLY, OVERLAP, NOT_READY,
    SOURCE_CHANGED, VERIFY, FOLDER_REQUIRED, TOO_MANY, INTERRUPTED,
    CLEANUP, DATABASE, DUPLICATE, UNKNOWN,
}

class CopyProblem(val problem: Problem) : Exception(problem.name)

object CopyRules {
    private val types = mapOf(
        "aac" to "audio/aac", "m4a" to "audio/mp4", "mp3" to "audio/mpeg",
        "wav" to "audio/wav", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "opus" to "audio/ogg", "amr" to "audio/amr", "3gp" to "audio/3gpp",
        "3gpp" to "audio/3gpp", "aif" to "audio/aiff", "aiff" to "audio/aiff",
        "wma" to "audio/x-ms-wma", "mka" to "audio/x-matroska",
        "m4b" to "audio/mp4", "oga" to "audio/ogg", "awb" to "audio/amr-wb",
    )

    fun isAudio(name: String, mime: String?): Boolean {
        val type = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (type.startsWith("audio/") || type == "application/ogg") return true
        val ext = name.substringAfterLast('.', "").lowercase()
        if (type == "video/3gpp" || type == "video/3gpp2") return ext in setOf("3gp", "3gpp")
        return type in setOf("", "application/octet-stream", "binary/octet-stream") && ext in types
    }

    fun mime(name: String, provided: String): String =
        if (provided.startsWith("audio/")) provided else
            types[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    fun safeName(name: String): String = name.map {
        if (it == '/' || it == '\\' || it.code < 32 || it.code == 127) '_' else it
    }.joinToString("").trim().takeIf { it.isNotEmpty() && it != "." && it != ".." } ?: "audio"

    fun uniqueName(original: String, occupied: Set<String>): String {
        val name = safeName(original)
        val lowerNames = occupied.map { it.lowercase() }.toHashSet()
        if (name.lowercase() !in lowerNames) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val base = name.substring(0, dot)
        val ext = name.substring(dot)
        for (number in 1..10000) {
            val candidate = "$base ($number)$ext"
            if (candidate.lowercase() !in lowerNames) return candidate
        }
        throw CopyProblem(Problem.TOO_MANY)
    }

    // Android ExternalStorageProvider IDs are volume:relative/path.
    fun overlapping(first: String, second: String): Boolean {
        fun normalized(id: String): String {
            val volume = id.substringBefore(':').lowercase()
            val parts = id.substringAfter(':', "").split('/').filter { it.isNotEmpty() }
            if (parts.any { it == "." || it == ".." }) throw CopyProblem(Problem.OVERLAP)
            return volume + ":" + parts.joinToString("/").lowercase()
        }
        val a = normalized(first)
        val b = normalized(second)
        fun contains(parent: String, child: String) = child == parent ||
            child.startsWith(if (parent.endsWith(':')) parent else "$parent/")
        return contains(a, b) || contains(b, a)
    }
}

data class TransferResult(val bytes: Long, val sha256: String)

/** Does not own either stream. Caller closes them and cleans up failed output. */
fun transferVerifiedLength(
    source: InputStream,
    target: OutputStream,
    expected: Long,
    onProgress: (Long) -> Unit = {},
): TransferResult {
    if (expected <= 0) throw CopyProblem(Problem.NOT_READY)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        onProgress(count) // Also a cancellation checkpoint for the Android caller.
        val read = source.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (read.toLong() > expected - count) throw CopyProblem(Problem.SOURCE_CHANGED)
        target.write(buffer, 0, read)
        digest.update(buffer, 0, read)
        count += read
    }
    if (count != expected) throw CopyProblem(Problem.SOURCE_CHANGED)
    target.flush()
    onProgress(count)
    return TransferResult(count, digest.digest().joinToString("") { "%02x".format(it) })
}
