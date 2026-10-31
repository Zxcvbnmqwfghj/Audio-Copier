package com.audiocopier.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.room.Room
import com.audiocopier.app.background.AutomaticMonitor
import com.audiocopier.app.copying.CopyRepository
import com.audiocopier.app.data.CopyDatabase
import com.audiocopier.app.data.FolderPreferences
import java.util.Locale
import kotlinx.coroutines.*

open class CopierApplication : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, error -> monitor.problem.value = com.audiocopier.app.copying.problemOf(error) })
    val preferences by lazy { FolderPreferences(this) }
    val database by lazy {
        Room.databaseBuilder(this, CopyDatabase::class.java, "copy-history.db")
            .addMigrations(CopyDatabase.MIGRATION_1_2).build()
    }
    val copies by lazy { CopyRepository(contentResolver, database.operations()) }
    val monitor by lazy { AutomaticMonitor(this) }
    override fun onCreate() {
        super.onCreate()
        monitor.observe()
    }
    fun textContext(): Context {
        val language = monitor.cached.language
        if (language.isBlank()) return this
        return createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale(language)) })
    }
}
