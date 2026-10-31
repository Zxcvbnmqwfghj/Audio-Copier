package com.audiocopier.app.copying

import android.content.ContentResolver
import android.database.sqlite.SQLiteFullException
import android.net.Uri
import android.provider.DocumentsContract
import android.system.ErrnoException
import android.system.OsConstants
import com.audiocopier.app.data.FolderSettings
import com.audiocopier.app.data.Operation
import com.audiocopier.app.data.OperationDao
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class AudioDocument(
    val uri: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long,
    val documentId: String,
)

enum class CopyPhase { WAITING, CHECKING, COPYING, VERIFYING }
data class CopyProgress(val phase: CopyPhase, val bytes: Long = 0, val total: Long = 0)

fun problemOf(error: Throwable): Problem {
    if (error is CopyProblem) return error.problem
    if (error is SecurityException) return Problem.PERMISSION
    var cause: Throwable? = error
    repeat(8) {
        val current = cause ?: return@repeat
        if (current is SQLiteFullException ||
            (current is ErrnoException && current.errno == OsConstants.ENOSPC) ||
            current.message.orEmpty().contains("ENOSPC", ignoreCase = true) ||
            current.message.orEmpty().contains("No space left", ignoreCase = true)
        ) return Problem.SPACE
        if (current is ErrnoException && current.errno in setOf(OsConstants.EACCES, OsConstants.EPERM)) {
            return Problem.PERMISSION
        }
        if (current.message.orEmpty().contains("EACCES") || current.message.orEmpty().contains("EPERM")) {
            return Problem.PERMISSION
        }
        cause = current.cause
    }
    if (error is FileNotFoundException) return Problem.MISSING
    return if (error is IOException) Problem.IO else Problem.UNKNOWN
}

class CopyRepository(
    private val resolver: ContentResolver,
    private val dao: OperationDao,
) {
    private val mutex = Mutex()
    val history = dao.recent()
    val copies = dao.copies()
    val autoCount = dao.autoCount()
    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
    )

    fun tree(uri: String): Uri {
        if (uri.isBlank()) throw CopyProblem(Problem.FOLDER_REQUIRED)
        val value = Uri.parse(uri)
        if (value.scheme != "content" || value.authority != "com.android.externalstorage.documents" ||
            !DocumentsContract.isTreeUri(value)
        ) throw CopyProblem(Problem.LOCAL_ONLY)
        return value
    }

    fun validateSelection(selected: String, other: String) {
        val first = tree(selected)
        if (other.isNotBlank() && CopyRules.overlapping(
                DocumentsContract.getTreeDocumentId(first),
                DocumentsContract.getTreeDocumentId(tree(other)),
            )
        ) throw CopyProblem(Problem.OVERLAP)
    }

    private fun roots(settings: FolderSettings): Pair<Uri, Uri> {
        val source = tree(settings.source)
        val destination = tree(settings.destination)
        validateSelection(settings.source, settings.destination)
        return source to destination
    }

    private fun rootDocument(tree: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    private fun query(uri: Uri, tree: Uri): List<AudioDocument> {
        val result = mutableListOf<AudioDocument>()
        val cursor = resolver.query(uri, projection, android.os.Bundle.EMPTY, null) ?: throw CopyProblem(Problem.IO)
        cursor.use {
            while (it.moveToNext()) {
                if (result.size >= 10000) throw CopyProblem(Problem.TOO_MANY)
                val id = it.getString(0) ?: throw CopyProblem(Problem.IO)
                result += AudioDocument(
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(),
                    name = it.getString(1) ?: id.substringAfterLast('/'),
                    mime = it.getString(2).orEmpty(),
                    size = if (it.isNull(3)) -1L else it.getLong(3),
                    modified = if (it.isNull(4)) 0L else it.getLong(4),
                    documentId = id,
                )
            }
        }
        return result
    }

    private fun read(uri: Uri): AudioDocument = query(uri, uri).firstOrNull()
        ?: throw FileNotFoundException()

    private fun children(tree: Uri, id: String): List<AudioDocument> = query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), tree,
    )

    suspend fun validateReadable(selected: String) = withContext(Dispatchers.IO) {
        val tree = tree(selected)
        if (read(rootDocument(tree)).mime != DocumentsContract.Document.MIME_TYPE_DIR) {
            throw CopyProblem(Problem.FOLDER_REQUIRED)
        }
        children(tree, DocumentsContract.getTreeDocumentId(tree))
        Unit
    }

    suspend fun testFolders(settings: FolderSettings) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (source, destination) = roots(settings)
            children(source, DocumentsContract.getTreeDocumentId(source))
            var temporary: Uri? = null
            try {
                temporary = DocumentsContract.createDocument(
                    resolver, rootDocument(destination), "application/octet-stream",
                    ".audio-copier-test-${UUID.randomUUID()}.tmp",
                ) ?: throw CopyProblem(Problem.IO)
                val payload = "Audio Copier folder access test".toByteArray()
                (resolver.openOutputStream(temporary, "wt") ?: throw CopyProblem(Problem.IO))
                    .use { it.write(payload) }
                val actual = (resolver.openInputStream(temporary) ?: throw CopyProblem(Problem.IO))
                    .use { input ->
                        val bytes = ByteArray(payload.size + 1)
                        var count = 0
                        while (count < bytes.size) {
                            val read = input.read(bytes, count, bytes.size - count)
                            if (read < 0) break
                            count += read
                        }
                        bytes.copyOf(count)
                    }
                if (!actual.contentEquals(payload)) throw CopyProblem(Problem.VERIFY)
            } finally {
                temporary?.let { if (!removeOwned(it)) throw CopyProblem(Problem.CLEANUP) }
            }
        }
    }

    suspend fun scan(source: String): List<AudioDocument> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tree = tree(source)
            val queue = ArrayDeque<String>()
            val visited = hashSetOf<String>()
            val files = mutableListOf<AudioDocument>()
            queue.add(DocumentsContract.getTreeDocumentId(tree))
            var seen = 0
            while (queue.isNotEmpty()) {
                currentCoroutineContext().ensureActive()
                val id = queue.removeFirst()
                if (!visited.add(id)) continue
                for (document in children(tree, id)) {
                    if (++seen > 10000) throw CopyProblem(Problem.TOO_MANY)
                    if (document.mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.add(document.documentId)
                    else if (CopyRules.isAudio(document.name, document.mime)) files += document
                }
            }
            files.distinctBy { it.uri }.sortedWith(compareByDescending<AudioDocument> { it.modified }.thenBy { it.name })
        }
    }

    private fun sameVersion(a: AudioDocument, b: AudioDocument): Boolean =
        a.uri == b.uri && a.size == b.size && a.modified == b.modified && a.name == b.name

    private fun removeOwned(uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: FileNotFoundException) {
        true
    } catch (_: Exception) {
        false
    }

    private suspend fun hash(uri: Uri, expected: Long): String {
        val context = currentCoroutineContext()
        val sink = object : OutputStream() {
            override fun write(value: Int) = Unit
            override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
        }
        return (resolver.openInputStream(uri) ?: throw CopyProblem(Problem.IO)).use {
            transferVerifiedLength(it, sink, expected) { context.ensureActive() }.sha256
        }
    }

    suspend fun recoverInterrupted() = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (record in dao.interrupted()) {
                var cleaned = true
                if (record.destinationUri.isNotBlank()) {
                    // Only delete outputs recorded by this app, never a source URI.
                    cleaned = record.destinationUri != record.sourceUri &&
                        removeOwned(Uri.parse(record.destinationUri))
                }
                dao.update(record.copy(
                    status = "FAILED", problem = if (cleaned) "INTERRUPTED" else "CLEANUP",
                    destinationUri = if (cleaned) "" else record.destinationUri,
                    finishedAt = System.currentTimeMillis(),
                ))
            }
        }
    }

    suspend fun copy(
        settings: FolderSettings,
        selected: AudioDocument,
        automatic: Boolean = false,
        onProgress: (CopyProgress) -> Unit,
    ): Operation = withContext(Dispatchers.IO) {
        mutex.withLock {
            val (sourceTree, destinationTree) = roots(settings)
            val source = Uri.parse(selected.uri)
            if (source.authority != sourceTree.authority ||
                DocumentsContract.getTreeDocumentId(source) != DocumentsContract.getTreeDocumentId(sourceTree)
            ) throw CopyProblem(Problem.PERMISSION)
            var operation = Operation(
                sourceUri = selected.uri, sourceName = selected.name,
                sourceSize = selected.size, sourceModified = selected.modified,
                destinationTree = settings.destination, automatic = automatic,
            )
            operation = operation.copy(id = dao.insert(operation))
            var ownedOutput: Uri? = null
            try {
                onProgress(CopyProgress(CopyPhase.WAITING))
                val before = read(source)
                if (!CopyRules.isAudio(before.name, before.mime)) throw CopyProblem(Problem.NOT_READY)
                delay(2000)
                val ready = read(source)
                if (ready.size <= 0 || !sameVersion(before, ready)) throw CopyProblem(Problem.NOT_READY)
                operation = operation.copy(
                    sourceName = ready.name, sourceSize = ready.size, sourceModified = ready.modified,
                )
                dao.update(operation)
                onProgress(CopyProgress(CopyPhase.CHECKING))
                val previous = dao.previous(source.toString(), settings.destination, ready.size, ready.modified)
                if (previous != null && previous.destinationUri.isNotBlank()) {
                    val oldUri = Uri.parse(previous.destinationUri)
                    val oldExists = try { read(oldUri).size == ready.size } catch (_: FileNotFoundException) { false }
                    if (oldExists && hash(source, ready.size) == previous.sha256 &&
                        hash(oldUri, ready.size) == previous.sha256
                    ) {
                        if (!sameVersion(ready, read(source))) throw CopyProblem(Problem.SOURCE_CHANGED)
                        operation = operation.copy(
                            status = "SKIPPED", problem = "DUPLICATE", finishedAt = System.currentTimeMillis(),
                        )
                        dao.update(operation)
                        return@withLock operation
                    }
                }
                val temporaryName = ".audio-copier-${UUID.randomUUID()}.pending"
                ownedOutput = DocumentsContract.createDocument(
                    resolver, rootDocument(destinationTree), CopyRules.mime(ready.name, ready.mime), temporaryName,
                ) ?: throw CopyProblem(Problem.IO)
                operation = operation.copy(status = "COPYING", destinationUri = ownedOutput.toString())
                dao.update(operation) // Journal output before writing, for interrupted-copy cleanup.
                val context = currentCoroutineContext()
                val transferred = (resolver.openInputStream(source) ?: throw CopyProblem(Problem.IO)).use { input ->
                    (resolver.openOutputStream(ownedOutput, "wt") ?: throw CopyProblem(Problem.IO)).use { output ->
                        transferVerifiedLength(input, output, ready.size) { bytes ->
                            context.ensureActive()
                            onProgress(CopyProgress(CopyPhase.COPYING, bytes, ready.size))
                        }
                    }
                }
                if (!sameVersion(ready, read(source))) throw CopyProblem(Problem.SOURCE_CHANGED)
                onProgress(CopyProgress(CopyPhase.VERIFYING))
                if (hash(ownedOutput, ready.size) != transferred.sha256) throw CopyProblem(Problem.VERIFY)
                val occupied = children(destinationTree, DocumentsContract.getTreeDocumentId(destinationTree))
                    .map { it.name }.toSet()
                val finalName = CopyRules.uniqueName(ready.name, occupied)
                ownedOutput = DocumentsContract.renameDocument(resolver, ownedOutput, finalName)
                    ?: throw CopyProblem(Problem.IO)
                // Renaming may return a different URI; immediately journal that URI too.
                operation = operation.copy(destinationUri = ownedOutput.toString())
                dao.update(operation)
                val final = read(ownedOutput)
                if (final.size != ready.size || !sameVersion(ready, read(source))) {
                    throw CopyProblem(Problem.SOURCE_CHANGED)
                }
                operation = operation.copy(
                    status = "SUCCESS", outputName = final.name, sha256 = transferred.sha256, copySize = final.size,
                    finishedAt = System.currentTimeMillis(),
                )
                dao.update(operation)
                ownedOutput = null // Successful output is never included in cleanup.
                operation
            } catch (error: Exception) {
                withContext(NonCancellable) {
                    val cleaned = ownedOutput?.let { removeOwned(it) } ?: true
                    val reason = if (!cleaned) Problem.CLEANUP else if (error is CancellationException)
                        Problem.INTERRUPTED else problemOf(error)
                    operation = operation.copy(
                        status = if (reason in setOf(Problem.NOT_READY, Problem.SOURCE_CHANGED)) "WAITING" else "FAILED",
                        problem = reason.name,
                        destinationUri = if (cleaned) "" else ownedOutput.toString(),
                        finishedAt = System.currentTimeMillis(),
                    )
                    dao.update(operation)
                }
                if (error is CancellationException) throw error
                operation
            }
        }
    }
    private suspend fun owned(id: Long): Operation {
        val record = dao.get(id) ?: throw CopyProblem(Problem.MISSING)
        if (record.status != "SUCCESS" || record.destinationUri.isBlank() ||
            record.destinationUri == record.sourceUri) throw CopyProblem(Problem.PERMISSION)
        val uri = Uri.parse(record.destinationUri)
        val parent = tree(record.destinationTree)
        if (uri.authority != parent.authority ||
            DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(parent)) {
            throw CopyProblem(Problem.PERMISSION)
        }
        return record
    }
    suspend fun details(id: Long): AudioDocument = withContext(Dispatchers.IO) {
        mutex.withLock { read(Uri.parse(owned(id).destinationUri)) }
    }
    suspend fun deleteCopy(id: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = owned(id)
            if (!DocumentsContract.deleteDocument(resolver, Uri.parse(record.destinationUri))) throw CopyProblem(Problem.IO)
            dao.update(record.copy(status = "DELETED", destinationUri = "", copySize = 0))
        }
    }
    suspend fun renameCopy(id: Long, name: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = owned(id)
            if (name.isBlank()) throw CopyProblem(Problem.NOT_READY)
            val parent = tree(record.destinationTree)
            val existing = children(parent, DocumentsContract.getTreeDocumentId(parent))
                .filter { it.uri != record.destinationUri }.map { it.name }.toSet()
            val target = CopyRules.uniqueName(name, existing)
            val uri = DocumentsContract.renameDocument(resolver, Uri.parse(record.destinationUri), target)
                ?: throw CopyProblem(Problem.IO)
            val actual = read(uri)
            dao.update(record.copy(destinationUri = uri.toString(), outputName = actual.name, copySize = actual.size))
        }
    }
    suspend fun refreshCopies(): Long = withContext(Dispatchers.IO) {
        mutex.withLock {
            var bytes = 0L
            for (record in dao.allCopies()) {
                currentCoroutineContext().ensureActive()
                try {
                    val actual = read(Uri.parse(record.destinationUri))
                    if (actual.size < 0) throw CopyProblem(Problem.IO)
                    bytes += actual.size
                    if (actual.name != record.outputName || actual.size != record.copySize) {
                        dao.update(record.copy(outputName = actual.name, copySize = actual.size))
                    }
                } catch (_: FileNotFoundException) {
                    dao.update(record.copy(status = "MISSING_COPY", destinationUri = "", copySize = 0))
                }
            }
            bytes
        }
    }

}
