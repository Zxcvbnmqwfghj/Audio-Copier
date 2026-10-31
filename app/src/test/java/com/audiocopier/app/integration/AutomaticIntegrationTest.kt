package com.audiocopier.app.integration

import android.content.pm.ProviderInfo
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.audiocopier.app.CopierApplication
import com.audiocopier.app.background.AutomaticMonitor
import com.audiocopier.app.background.MonitorRules
import java.io.File
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

class QuietCopierApplication : CopierApplication() {
    override fun onCreate() { /* Tests drive the monitor; do not launch OS scheduling. */ }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = QuietCopierApplication::class)
class AutomaticIntegrationTest {
    @Test fun baselineNewAacRestartAndManualStopHaveTheExpectedBoundaries() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<QuietCopierApplication>()
        val provider = TestDocuments(File(app.cacheDir, UUID.randomUUID().toString()))
        provider.attachInfo(app, ProviderInfo().apply {
            authority = "com.android.externalstorage.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", provider)
        val source = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Source").toString()
        val destination = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Copies").toString()
        try {
            app.preferences.save(true, source); app.preferences.save(false, destination)
            provider.recording("existing.aac", byteArrayOf(1, 2, 3))
            app.monitor.visible.value = true
            app.monitor.start()
            app.monitor.cycle()
            assertTrue(provider.copies.listFiles()!!.isEmpty())
            provider.recording("new.aac", byteArrayOf(4, 5, 6))
            app.monitor.cycle()
            assertTrue(provider.copies.listFiles()!!.isEmpty()) // One observation is not enough.
            val session = app.preferences.settings.first().session
            val entry = app.database.monitor().all(session).single { !it.baseline }
            app.database.monitor().observe(entry.copy(stableSince = System.currentTimeMillis() - MonitorRules.STABILITY_MS - 100))
            app.monitor.cycle()
            assertEquals(listOf("new.aac"), provider.copies.listFiles()!!.map { it.name })
            assertEquals(1L, app.database.operations().autoCount().first())
            val restarted = AutomaticMonitor(app)
            restarted.visible.value = true
            restarted.cycle()
            assertEquals(1, provider.copies.listFiles()!!.size)
            app.preferences.automatic(false)
            provider.recording("while-stopped.aac", byteArrayOf(7, 8, 9))
            restarted.start()
            restarted.cycle()
            assertEquals(1, provider.copies.listFiles()!!.size)
            assertTrue(File(provider.source, "existing.aac").exists())
            assertTrue(File(provider.source, "new.aac").exists())
        } finally {
            app.preferences.automatic(false)
            app.database.close(); app.scope.cancel(); provider.root.deleteRecursively()
        }
    }
}
