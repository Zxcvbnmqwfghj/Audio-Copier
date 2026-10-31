package com.audiocopier.app.background

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.audiocopier.app.*
import com.audiocopier.app.copying.problemOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private val app get() = application as CopierApplication
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP_ACTION) {
            app.scope.launch { app.monitor.stop() }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, app.textContext().getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, MonitoringService::class.java).setAction(STOP_ACTION), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val text = app.textContext()
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification).setContentTitle(text.getString(R.string.auto_title))
                .setContentText(text.getString(R.string.monitor_notification)).setOngoing(true)
                .setContentIntent(open).setOnlyAlertOnce(true)
                .addAction(0, text.getString(R.string.stop), stop).build()
            ServiceCompat.startForeground(this, 41, notification, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            app.monitor.serviceRunning.value = true
            if (loop?.isActive != true) loop = scope.launch {
                while (isActive) {
                    val settings = app.preferences.settings.first()
                    if (!settings.automatic || !settings.background) { stopSelf(); break }
                    try { app.monitor.cycle() } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        app.monitor.problem.value = problemOf(error)
                    }
                    delay(20_000)
                }
            }
        } catch (error: Exception) {
            app.monitor.problem.value = problemOf(error)
            stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 dataSync quota: stop immediately; periodic WorkManager work remains.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        scope.cancel()
        app.monitor.serviceRunning.value = false
        super.onDestroy()
    }
    companion object {
        private const val CHANNEL = "automatic-copying"
        private const val STOP_ACTION = "com.audiocopier.app.STOP_MONITORING"
        fun canNotify(app: CopierApplication): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
            if (!NotificationManagerCompat.from(app).areNotificationsEnabled()) return false
            return app.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun start(app: CopierApplication): Boolean {
            if (!canNotify(app)) return false
            if (app.monitor.serviceRunning.value) return true
            return try {
                ContextCompat.startForegroundService(app, Intent(app, MonitoringService::class.java))
                true
            } catch (_: Exception) { false }
        }
    }
}
