package com.audiocopier.app.integration

import android.app.Application
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.audiocopier.app.copying.*
import com.audiocopier.app.data.*
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageIntegrationTest {
    private lateinit var context: Context
    private lateinit var provider: TestDocuments
    private lateinit var db: CopyDatabase
    private lateinit var repo: CopyRepository
    private val source = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Source").toString()
    private val target = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:Copies").toString()
    private val folders get() = FolderSettings(source, target)
    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        provider = TestDocuments(File(context.cacheDir, UUID.randomUUID().toString()))
        provider.attachInfo(context, ProviderInfo().apply {
            authority = "com.android.externalstorage.documents"; exported = true; grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("com.android.externalstorage.documents", provider)
        db = Room.inMemoryDatabaseBuilder(context, CopyDatabase::class.java).allowMainThreadQueries().build()
        repo = CopyRepository(context.contentResolver, db.operations())
    }
    @After fun tearDown() { db.close(); provider.root.deleteRecursively() }

    @Test fun copyVerifyDeduplicateRenameAndDeleteKeepTheOriginalIntact() = runBlocking {
        val original = provider.recording("call.aac", ByteArray(160000) { (it % 127).toByte() })
        val originalBytes = original.readBytes()
        repo.testFolders(folders)
        assertEquals(0, provider.copies.listFiles()!!.size)
        val file = repo.scan(source).single()
        val copied = repo.copy(folders, file) {}
        assertEquals("SUCCESS", copied.status)
        assertArrayEquals(originalBytes, File(provider.copies, copied.outputName).readBytes())
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals("SKIPPED", repo.copy(folders, file) {}.status)
        assertEquals(1, provider.copies.listFiles()!!.size)
        repo.renameCopy(copied.id, "renamed.aac")
        assertTrue(File(provider.copies, "renamed.aac").exists())
        repo.deleteCopy(copied.id)
        assertEquals(0, provider.copies.listFiles()!!.size)
        assertArrayEquals(originalBytes, original.readBytes())
        assertEquals(0, provider.sourceWriteAttempts)
    }
    @Test fun aGrowingSourceCannotBeMarkedSuccessfulAndItsPartialOutputIsRemoved() = runBlocking {
        val original = provider.recording("growing.aac", ByteArray(100) { 1 })
        provider.growOnRead = true
        val result = repo.copy(folders, repo.scan(source).single()) {}
        assertEquals("WAITING", result.status)
        assertEquals("SOURCE_CHANGED", result.problem)
        assertEquals(101L, original.length()) // The simulated recorder wrote one byte.
        assertEquals(0, provider.copies.listFiles()!!.size)
    }
    @Test fun conflictingNamesNeverOverwriteAnExistingDestination() = runBlocking {
        provider.recording("same.aac", byteArrayOf(1, 2, 3))
        val existing = File(provider.copies, "same.aac").apply { writeBytes(byteArrayOf(9, 9)) }
        val result = repo.copy(folders, repo.scan(source).single()) {}
        assertEquals("same (1).aac", result.outputName)
        assertArrayEquals(byteArrayOf(9, 9), existing.readBytes())
    }
    @Test fun destructiveOperationsRejectARecordPointingAtTheOriginal() = runBlocking {
        val original = provider.recording("protected.aac", byteArrayOf(1, 2, 3))
        val file = repo.scan(source).single()
        val id = db.operations().insert(Operation(sourceUri = file.uri, sourceName = file.name, sourceSize = file.size,
            sourceModified = file.modified, destinationTree = target, destinationUri = file.uri, status = "SUCCESS"))
        try { repo.deleteCopy(id); fail("Original must be protected") }
        catch (error: CopyProblem) { assertEquals(Problem.PERMISSION, error.problem) }
        assertTrue(original.exists())
    }
    @Test fun migrationRetainsExistingCopiesAndDoesNotCountManualCopiesAsAutomatic() = runBlocking {
        val name = "migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(javaClass.classLoader!!.getResource("schema-v1.json")!!.readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entity = schema.getJSONArray("entities").getJSONObject(0)
            old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            old.execSQL("INSERT INTO operations VALUES (1,'source','call.aac',123,1,'tree','copy','call.aac','SUCCESS','','hash',1,2)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, CopyDatabase::class.java, name)
            .addMigrations(CopyDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val saved = migrated.operations().get(1)!!
            assertEquals("copy", saved.destinationUri)
            assertEquals(123L, saved.copySize)
            assertFalse(saved.automatic)
            assertEquals(0L, migrated.operations().autoCount().first())
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}

class TestDocuments(val root: File) : DocumentsProvider() {
    val source = File(root, "Source").apply { mkdirs() }
    val copies = File(root, "Copies").apply { mkdirs() }
    var growOnRead = false
    var sourceWriteAttempts = 0
    fun recording(name: String, bytes: ByteArray): File = File(source, name).apply { writeBytes(bytes) }
    override fun onCreate() = true
    private fun file(id: String): File {
        val f = File(root, id.removePrefix("primary:")).canonicalFile
        check(f.toPath().startsWith(root.canonicalFile.toPath()))
        return f
    }
    private fun id(file: File) = "primary:" + file.relativeTo(root).invariantSeparatorsPath
    private fun cursor(files: List<File>, projection: Array<out String>?): Cursor {
        val columns = projection ?: arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified")
        return MatrixCursor(columns).apply {
            for (f in files) addRow(columns.map { column -> when (column) {
                "document_id" -> id(f); "_display_name" -> f.name
                "mime_type" -> if (f.isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else "audio/aac"
                "_size" -> f.length(); "last_modified" -> f.lastModified(); "flags" -> 0; else -> null
            } }.toTypedArray())
        }
    }
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf("root_id"))
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = file(documentId); if (!f.exists()) throw FileNotFoundException()
        return cursor(listOf(f), projection)
    }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = cursor(file(parentDocumentId).listFiles()!!.toList(), projection)
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = file(documentId).toPath().startsWith(file(parentDocumentId).toPath())
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        if (f.parentFile == source) {
            if (mode.contains('w')) { sourceWriteAttempts++; error("Source write attempted") }
            if (growOnRead) { f.appendBytes(byteArrayOf(2)); growOnRead = false }
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val folder = file(parentDocumentId)
        check(folder != source)
        val f = File(folder, displayName); check(f.createNewFile()); return id(f)
    }
    override fun renameDocument(documentId: String, displayName: String): String {
        val f = file(documentId); check(f.parentFile != source)
        val to = File(f.parentFile, displayName); check(!to.exists()); check(f.renameTo(to)); return id(to)
    }
    override fun deleteDocument(documentId: String) {
        val f = file(documentId); check(f.parentFile != source); check(f.delete())
    }
}
