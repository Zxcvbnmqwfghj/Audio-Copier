package com.audiocopier.app.ui

import android.app.Application
import android.app.usage.StorageStatsManager
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.storage.StorageManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.audiocopier.app.*
import com.audiocopier.app.R
import com.audiocopier.app.background.MonitoringService
import com.audiocopier.app.copying.*
import com.audiocopier.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

@androidx.annotation.OptIn(UnstableApi::class)
class CopierViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CopierApplication
    private val mutable = MutableStateFlow(CopierState())
    val state = mutable.asStateFlow()
    private val initialized = CompletableDeferred<Unit>()
    private var statsJob: Job? = null
    private val player = ExoPlayer.Builder(app).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { mutable.update { it.copy(playing = isPlaying) } }
            override fun onPlaybackStateChanged(playbackState: Int) { updatePlayer() }
            override fun onPlayerError(error: PlaybackException) {
                mutable.update { it.copy(message = R.string.player_error, playing = false) }
            }
        })
    }
    init {
        viewModelScope.launch {
            try {
                app.monitor.initialize()
                mutable.update { it.copy(settings = app.preferences.settings.first()) }
            } catch (error: Exception) { report(error) }
            finally { mutable.update { it.copy(busy = false) }; initialized.complete(Unit) }
        }
        viewModelScope.launch {
            app.preferences.settings.catch { report(it) }.collect { setting ->
                mutable.update { it.copy(settings = setting) }
                if (setting.automatic && setting.background && app.monitor.visible.value) MonitoringService.start(app)
            }
        }
        viewModelScope.launch {
            app.copies.copies.catch { report(it) }.collect { list ->
                mutable.update { it.copy(copies = list) }
                if (mutable.value.playingId != null && list.none { it.id == mutable.value.playingId }) stopPlayer()
                refreshStats()
            }
        }
        viewModelScope.launch { app.copies.autoCount.catch { report(it) }.collect { n -> mutable.update { it.copy(autoCount = n) } } }
        viewModelScope.launch { app.monitor.problem.collect { p -> mutable.update { it.copy(monitorProblem = p) } } }
        viewModelScope.launch { app.monitor.serviceRunning.collect { active -> mutable.update { it.copy(serviceRunning = active) } } }
        viewModelScope.launch { app.monitor.progress.collect { p -> mutable.update { it.copy(autoProgress = p) } } }
        viewModelScope.launch { while (isActive) { updatePlayer(); delay(500) } }
    }
    private fun updatePlayer() {
        mutable.update { it.copy(position = player.currentPosition.coerceAtLeast(0), duration = player.duration.takeIf { d -> d > 0 } ?: 0) }
    }
    private fun report(error: Throwable) {
        if (error is CancellationException) throw error
        mutable.update { it.copy(message = problemString(problemOf(error))) }
    }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            try { initialized.await(); block() } catch (error: Exception) { report(error) }
            finally { mutable.update { it.copy(busy = false, manualProgress = null) } }
        }
    }
    fun dismissMessage() { mutable.update { it.copy(message = null) } }
    fun message(id: Int) { mutable.update { it.copy(message = id) } }
    fun visible(value: Boolean) {
        app.monitor.visible.value = value
        if (!value) player.pause()
        else {
            val settings = mutable.value.settings
            if (settings.automatic && settings.background) MonitoringService.start(app)
            refreshStats()
        }
    }
    fun toggleAutomatic() = action {
        if (mutable.value.settings.automatic) app.monitor.stop()
        else {
            app.monitor.start()
            if (mutable.value.settings.background && !MonitoringService.start(app)) message(R.string.periodic_fallback)
        }
    }
    fun background(enabled: Boolean) = action {
        app.preferences.background(enabled)
        if (enabled && mutable.value.settings.automatic && !MonitoringService.start(app)) message(R.string.periodic_fallback)
    }
    fun selectFolder(uri: Uri, flags: Int, source: Boolean) {
        if (!initialized.isCompleted) { viewModelScope.launch { initialized.await(); selectFolder(uri, flags, source) }; return }
        action {
            val folders = mutable.value.settings.folders
            app.copies.validateSelection(uri.toString(), if (source) folders.destination else folders.source)
            val needed = Intent.FLAG_GRANT_READ_URI_PERMISSION or (if (source) 0 else Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (flags and needed != needed) throw CopyProblem(Problem.PERMISSION)
            withContext(Dispatchers.IO) { app.contentResolver.takePersistableUriPermission(uri, needed) }
            app.copies.validateReadable(uri.toString())
            app.monitor.stop()
            app.preferences.save(source, uri.toString())
            val saved = app.preferences.settings.first()
            mutable.update { it.copy(settings = saved, oldFiles = emptyList(), oldSelected = emptySet()) }
            if (saved.folders.source.isNotBlank() && saved.folders.destination.isNotBlank()) app.copies.testFolders(saved.folders)
        }
    }
    fun chooseOld() = action {
        val files = app.copies.scan(mutable.value.settings.folders.source)
        mutable.update { it.copy(oldFiles = files, oldSelected = emptySet(), oldDialog = true) }
    }
    fun selectOld(uri: String) { mutable.update { it.copy(oldSelected = if (uri in it.oldSelected) it.oldSelected - uri else it.oldSelected + uri) } }
    fun closeOld() { mutable.update { it.copy(oldDialog = false) } }
    fun copyOld() = action {
        val selected = mutable.value.oldFiles.filter { it.uri in mutable.value.oldSelected }
        mutable.update { it.copy(oldDialog = false) }
        val folders = mutable.value.settings.folders
        app.copies.testFolders(folders)
        var lastFailure: Problem? = null
        for (file in selected) {
            val result = app.copies.copy(folders, file) { p -> mutable.update { it.copy(manualProgress = p) } }
            if (result.status !in setOf("SUCCESS", "SKIPPED")) lastFailure = runCatching { Problem.valueOf(result.problem) }.getOrDefault(Problem.UNKNOWN)
        }
        message(lastFailure?.let(::problemString) ?: R.string.copy_done)
        refreshStats()
    }
    fun language(value: String) = action { app.preferences.language(value) }
    fun theme(value: String) = action { app.preferences.theme(value) }
    fun deleteCopy(operation: Operation) = action {
        if (mutable.value.playingId == operation.id) stopPlayer()
        app.copies.deleteCopy(operation.id)
        refreshStats()
    }
    fun renameCopy(operation: Operation, name: String) = action {
        if (mutable.value.playingId == operation.id) stopPlayer()
        app.copies.renameCopy(operation.id, name)
    }
    fun details(operation: Operation) = action {
        val details = app.copies.details(operation.id)
        mutable.update { it.copy(detail = operation to details) }
    }
    fun closeDetails() { mutable.update { it.copy(detail = null) } }
    fun play(operation: Operation) {
        try {
            if (mutable.value.playingId == operation.id) {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
                return
            }
            player.setMediaItem(MediaItem.fromUri(operation.destinationUri))
            mutable.update { it.copy(playingId = operation.id, position = 0, duration = 0) }
            player.prepare(); player.play()
        } catch (error: Exception) { message(R.string.player_error) }
    }
    fun seek(position: Long) { player.seekTo(position); updatePlayer() }
    fun next(delta: Int, fromId: Long? = mutable.value.playingId) {
        val list = mutable.value.copies
        val index = list.indexOfFirst { it.id == fromId }
        list.getOrNull(index + delta)?.let { play(it) }
    }
    private fun stopPlayer() { player.stop(); mutable.update { it.copy(playingId = null, playing = false, position = 0, duration = 0) } }
    fun refreshStats() {
        if (statsJob?.isActive == true) return
        statsJob = viewModelScope.launch {
            try {
                initialized.await()
                val copies = app.copies.refreshCopies()
                val privateBytes = withContext(Dispatchers.IO) {
                    try {
                        val stats = app.getSystemService(StorageStatsManager::class.java).queryStatsForUid(StorageManager.UUID_DEFAULT, Process.myUid())
                        stats.appBytes + stats.dataBytes // dataBytes already includes cacheBytes.
                    } catch (_: Exception) {
                        val root = File(app.applicationInfo.dataDir)
                        root.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
                            (listOf(app.applicationInfo.sourceDir) + app.applicationInfo.splitSourceDirs.orEmpty()).distinct().sumOf { File(it).length() }
                    }
                }
                mutable.update { it.copy(storageBytes = copies + privateBytes) }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutable.update { it.copy(storageBytes = null) }
            }
        }
    }
    fun clearCache() = action {
        withContext(Dispatchers.IO) {
            // Only app cache; never databases, preferences, user-selected folders or audio copies.
            for (directory in listOf(app.cacheDir, app.codeCacheDir)) {
                directory.listFiles()?.forEach { if (!it.deleteRecursively()) throw CopyProblem(Problem.IO) }
            }
        }
        message(R.string.cache_cleared)
        refreshStats()
    }
    override fun onCleared() { player.release(); super.onCleared() }
}

data class CopierState(
    val settings: AppSettings = AppSettings(), val busy: Boolean = true,
    val copies: List<Operation> = emptyList(), val autoCount: Long = 0, val storageBytes: Long? = null,
    val monitorProblem: Problem? = null, val serviceRunning: Boolean = false,
    val autoProgress: CopyProgress? = null, val manualProgress: CopyProgress? = null,
    val message: Int? = null, val oldDialog: Boolean = false,
    val oldFiles: List<AudioDocument> = emptyList(), val oldSelected: Set<String> = emptySet(),
    val detail: Pair<Operation, AudioDocument>? = null,
    val playingId: Long? = null, val playing: Boolean = false, val position: Long = 0, val duration: Long = 0,
)
fun problemString(problem: Problem): Int = when (problem) {
    Problem.PERMISSION -> R.string.error_permission
    Problem.MISSING -> R.string.error_missing
    Problem.IO -> R.string.error_io
    Problem.SPACE -> R.string.error_space
    Problem.LOCAL_ONLY -> R.string.error_local_only
    Problem.OVERLAP -> R.string.error_overlap
    Problem.NOT_READY -> R.string.error_not_ready
    Problem.SOURCE_CHANGED -> R.string.error_source_changed
    Problem.VERIFY -> R.string.error_verify
    Problem.FOLDER_REQUIRED -> R.string.error_folders
    Problem.TOO_MANY -> R.string.error_many
    Problem.INTERRUPTED -> R.string.error_interrupted
    Problem.CLEANUP -> R.string.error_cleanup
    Problem.DATABASE -> R.string.error_database
    Problem.DUPLICATE -> R.string.error_duplicate
    Problem.UNKNOWN -> R.string.error_unknown
}
