package com.audiocopier.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.folderStore by preferencesDataStore("folders")
data class FolderSettings(val source: String = "", val destination: String = "")
data class AppSettings(
    val folders: FolderSettings = FolderSettings(), val automatic: Boolean = false,
    val background: Boolean = true, val session: String = "",
    val language: String = "", val theme: String = "",
)
class FolderPreferences(private val context: Context) {
    private val sourceKey = stringPreferencesKey("source_tree")
    private val destinationKey = stringPreferencesKey("destination_tree")
    private val autoKey = booleanPreferencesKey("automatic")
    private val backgroundKey = booleanPreferencesKey("background")
    private val sessionKey = stringPreferencesKey("session")
    private val languageKey = stringPreferencesKey("language")
    private val themeKey = stringPreferencesKey("theme")
    val settings: Flow<AppSettings> = context.folderStore.data.map {
        AppSettings(FolderSettings(it[sourceKey].orEmpty(), it[destinationKey].orEmpty()),
            it[autoKey] ?: false, it[backgroundKey] ?: true, it[sessionKey].orEmpty(),
            it[languageKey].orEmpty(), it[themeKey].orEmpty())
    }
    val flow = settings.map { it.folders }
    suspend fun save(source: Boolean, value: String) {
        context.folderStore.edit { it[if (source) sourceKey else destinationKey] = value }
    }
    suspend fun automatic(enabled: Boolean, session: String? = null) {
        context.folderStore.edit { it[autoKey] = enabled; if (session != null) it[sessionKey] = session }
    }
    suspend fun background(enabled: Boolean) { context.folderStore.edit { it[backgroundKey] = enabled } }
    suspend fun language(value: String) { context.folderStore.edit { it[languageKey] = value } }
    suspend fun theme(value: String) { context.folderStore.edit { it[themeKey] = value } }
}

@Entity(tableName = "operations")
data class Operation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceUri: String, val sourceName: String, val sourceSize: Long,
    val sourceModified: Long, val destinationTree: String,
    val destinationUri: String = "", val outputName: String = "",
    val status: String = "CHECKING", val problem: String = "", val sha256: String = "",
    val startedAt: Long = System.currentTimeMillis(), val finishedAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val automatic: Boolean = false,
    @ColumnInfo(defaultValue = "0") val copySize: Long = 0,
)
@Entity(tableName = "monitor_session")
data class MonitorSession(@PrimaryKey val id: Int = 1, val token: String, val source: String)
@Entity(tableName = "observed_audio", primaryKeys = ["session", "uri"])
data class ObservedAudio(
    val session: String, val uri: String, val size: Long, val modified: Long,
    val stableSince: Long, val baseline: Boolean = false, val done: Boolean = false,
    val retryAfter: Long = 0, val failures: Int = 0,
)
@Dao
interface OperationDao {
    @Insert suspend fun insert(operation: Operation): Long
    @Update suspend fun update(operation: Operation)
    @Query("SELECT * FROM operations ORDER BY id DESC LIMIT 20") fun recent(): Flow<List<Operation>>
    @Query("SELECT * FROM operations WHERE id = :id") suspend fun get(id: Long): Operation?
    @Query("SELECT * FROM operations WHERE status = 'SUCCESS' AND destinationUri != '' ORDER BY id DESC")
    fun copies(): Flow<List<Operation>>
    @Query("SELECT * FROM operations WHERE status = 'SUCCESS' AND destinationUri != ''")
    suspend fun allCopies(): List<Operation>
    @Query("SELECT COUNT(*) FROM operations WHERE automatic = 1 AND status IN ('SUCCESS','DELETED','MISSING_COPY')")
    fun autoCount(): Flow<Long>
    @Query("SELECT * FROM operations WHERE sourceUri = :source AND destinationTree = :destination AND sourceSize = :size AND sourceModified = :modified AND status = 'SUCCESS' ORDER BY id DESC LIMIT 1")
    suspend fun previous(source: String, destination: String, size: Long, modified: Long): Operation?
    @Query("SELECT * FROM operations WHERE status IN ('CHECKING', 'COPYING') OR (problem = 'CLEANUP' AND destinationUri != '')")
    suspend fun interrupted(): List<Operation>
}
@Dao
interface MonitorDao {
    @Query("SELECT * FROM monitor_session WHERE id = 1") suspend fun session(): MonitorSession?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun session(value: MonitorSession)
    @Query("DELETE FROM observed_audio") suspend fun clear()
    @Query("SELECT * FROM observed_audio WHERE session = :session") suspend fun all(session: String): List<ObservedAudio>
    @Query("DELETE FROM observed_audio WHERE session = :session AND uri = :uri") suspend fun remove(session: String, uri: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun observe(value: ObservedAudio)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun observeAll(values: List<ObservedAudio>)
    @Query("SELECT * FROM observed_audio WHERE session = :session AND uri = :uri")
    suspend fun observed(session: String, uri: String): ObservedAudio?
}
@Database(entities = [Operation::class, MonitorSession::class, ObservedAudio::class], version = 2, exportSchema = true)
abstract class CopyDatabase : RoomDatabase() {
    abstract fun operations(): OperationDao
    abstract fun monitor(): MonitorDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE operations ADD COLUMN automatic INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE operations ADD COLUMN copySize INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE operations SET copySize = sourceSize WHERE status = 'SUCCESS'")
                db.execSQL("CREATE TABLE IF NOT EXISTS monitor_session (id INTEGER NOT NULL, token TEXT NOT NULL, source TEXT NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS observed_audio (session TEXT NOT NULL, uri TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, stableSince INTEGER NOT NULL, baseline INTEGER NOT NULL, done INTEGER NOT NULL, retryAfter INTEGER NOT NULL, failures INTEGER NOT NULL, PRIMARY KEY(session, uri))")
            }
        }
    }
}
