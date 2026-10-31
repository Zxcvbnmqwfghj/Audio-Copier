package com.audiocopier.app.background

import android.content.Intent
import androidx.room.withTransaction
import androidx.work.*
import com.audiocopier.app.CopierApplication
import com.audiocopier.app.copying.*
import com.audiocopier.app.data.*
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AutomaticMonitor(private val app: CopierApplication) {
    private val scanMutex = Mutex()
    private val initMutex = Mutex()
    private var initialized = false
    val visible = MutableStateFlow(false)
    val serviceRunning = MutableStateFlow(false)
    val problem = MutableStateFlow<Problem?>(null)
    val progress = MutableStateFlow<CopyProgress?>(null)
    val preparing = MutableStateFlow(false)
    var cached = AppSettings()
        private set
    private val dao get() = app.database.monitor()

    suspend fun initialize() = initMutex.withLock {
        if (!initialized) {
            app.copies.recoverInterrupted()
            initialized = true
        }
    }
    fun observe() {
        app.scope.launch {
            app.preferences.settings.catch { problem.value = problemOf(it) }.collect { cached = it }
        }
        app.scope.launch {
            app.preferences.settings.map { Triple(it.automatic, it.background, it.session) }
                .distinctUntilChanged().collect { (enabled, background, session) ->
                    if (enabled && background) {
                        val request = PeriodicWorkRequestBuilder<AudioScanWorker>(15, TimeUnit.MINUTES)
                            .setInputData(workDataOf("session" to session)).build()
                        WorkManager.getInstance(app).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
                    } else {
                        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
                        app.stopService(Intent(app, MonitoringService::class.java))
                    }
                }
        }
        app.scope.launch {
            combine(app.preferences.settings.map { listOf(it.automatic, it.background, it.session) }.distinctUntilChanged(), visible, serviceRunning) {
                config, foreground, service -> Triple(config[0] == true, foreground, service)
            }.collectLatest { (enabled, foreground, service) ->
                if (enabled && foreground && !service) {
                    while (currentCoroutineContext().isActive) {
                        try { cycle() } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            problem.value = problemOf(error)
                        }
                        delay(20_000)
                    }
                }
            }
        }
    }
    suspend fun start() = scanMutex.withLock {
        initialize()
        preparing.value = true
        try {
            val settings = app.preferences.settings.first()
            app.copies.testFolders(settings.folders)
            val files = app.copies.scan(settings.folders.source).filter { MonitorRules.isAac(it.name, it.mime) }
            val token = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            app.database.withTransaction {
                dao.clear()
                dao.observeAll(files.map { ObservedAudio(token, it.uri, it.size, it.modified, now, baseline = true, done = true) })
                dao.session(MonitorSession(token = token, source = settings.folders.source))
            }
            problem.value = null
            app.preferences.automatic(true, token)
        } finally { preparing.value = false }
    }
    suspend fun stop() {
        app.preferences.automatic(false)
        app.stopService(Intent(app, MonitoringService::class.java))
        WorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
        problem.value = null
    }
    suspend fun cycle(expectedSession: String? = null) = scanMutex.withLock {
        initialize()
        val settings = app.preferences.settings.first()
        if (!settings.automatic || (expectedSession != null && expectedSession != settings.session)) return@withLock
        if (!visible.value && !settings.background) return@withLock
        val saved = dao.session()
        if (saved?.token != settings.session || saved.source != settings.folders.source) {
            problem.value = Problem.FOLDER_REQUIRED
            app.preferences.automatic(false)
            return@withLock
        }
        val files = app.copies.scan(settings.folders.source).filter { MonitorRules.isAac(it.name, it.mime) }
        problem.value = null
        val present = files.map { it.uri }.toHashSet()
        for (old in dao.all(settings.session)) {
            if (old.uri !in present) dao.remove(settings.session, old.uri)
        }
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val current = app.preferences.settings.first()
            if (!current.automatic || current.session != settings.session ||
                current.folders != settings.folders || (!visible.value && !current.background)) break
            val now = System.currentTimeMillis()
            val prior = dao.observed(settings.session, file.uri)
            if (prior == null) {
                dao.observe(ObservedAudio(settings.session, file.uri, file.size, file.modified, now))
                continue
            }
            if (prior.baseline) continue
            if (prior.size != file.size || prior.modified != file.modified || prior.stableSince > now) {
                dao.observe(prior.copy(size = file.size, modified = file.modified, stableSince = now, done = false, retryAfter = 0, failures = 0))
                continue
            }
            if (!MonitorRules.ready(prior, file.size, file.modified, now)) continue
            try {
                val operation = app.copies.copy(settings.folders, file, automatic = true) { progress.value = it }
                if (operation.status == "SUCCESS" || operation.status == "SKIPPED") {
                    dao.observe(prior.copy(done = true))
                } else {
                    dao.observe(prior.copy(retryAfter = now + MonitorRules.retryDelay(prior.failures), failures = prior.failures + 1))
                    val reason = runCatching { Problem.valueOf(operation.problem) }.getOrDefault(Problem.UNKNOWN)
                    if (reason !in setOf(Problem.NOT_READY, Problem.SOURCE_CHANGED)) problem.value = reason
                    if (reason in setOf(Problem.PERMISSION, Problem.SPACE)) break
                }
            } finally { progress.value = null }
        }
    }
    companion object { const val WORK_NAME = "automatic-aac-scan" }
}

class AudioScanWorker(context: android.content.Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CopierApplication
        val settings = app.preferences.settings.first()
        if (!settings.automatic || !settings.background || settings.session != inputData.getString("session")) return Result.success()
        if (app.monitor.serviceRunning.value) return Result.success()
        return try {
            // A worker checks twice so a completed new file can be copied in this run.
            app.monitor.cycle(settings.session)
            delay(MonitorRules.STABILITY_MS)
            app.monitor.cycle(settings.session)
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            app.monitor.problem.value = problemOf(error)
            Result.retry()
        }
    }
}
