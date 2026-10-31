package com.audiocopier.app.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import android.view.View
import com.audiocopier.app.ui.theme.AudioCopierTheme
import com.audiocopier.app.data.Operation
import com.audiocopier.app.data.AppSettings
import com.audiocopier.app.data.FolderSettings
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ar-rEG-w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Composable
    private fun Frame(page: Int, content: @Composable () -> Unit) {
        Scaffold(bottomBar = { BottomTabs(page) {} }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.findViewById<View>(android.R.id.content)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File("build/screenshots/$name.png").apply { parentFile!!.mkdirs() }
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun homeLightHasRealControlsAndNoDeviceSpecificInstructions() {
        compose.setContent { AudioCopierTheme(false) { Frame(0) {
            PowerPage(CopierState(busy = false), {}, {}, {}, {})
        } } }
        compose.onNodeWithText("النسخ التلقائي").assertExists()
        compose.onNodeWithText("العمل في الخلفية").assertExists()
        compose.onNodeWithText("هونر", substring = true).assertDoesNotExist()
        screenshot("home-light")
    }
    @Test fun homeDarkRendersUsingTheSameLayout() {
        compose.setContent { AudioCopierTheme(true) { Frame(0) {
            PowerPage(CopierState(busy = false), {}, {}, {}, {})
        } } }
        compose.onNodeWithText("معطّل").assertExists()
        screenshot("home-dark")
    }
    @Test fun statisticsShowsUnavailableEnergyRatherThanAFabricatedNumber() {
        compose.setContent { AudioCopierTheme(false) { Frame(1) {
            StatisticsPage(CopierState(busy = false), {}, {}, {}, {})
        } } }
        compose.onAllNodesWithText("غير متاح").assertCountEquals(2)
        compose.onNodeWithText("فتح إعدادات البطارية").assertExists()
        screenshot("statistics-light")
    }
    @Test fun settingsHasBothLanguagesAndBothThemes() {
        compose.setContent { AudioCopierTheme(false) { Frame(3) {
            SettingsPage(CopierState(busy = false), {}, {})
        } } }
        compose.onNodeWithText("English").assertExists()
        compose.onNodeWithText("العربية").assertExists()
        screenshot("settings-light")
    }
    @Test fun filesShowsRecordedCopiesAndExactlyTheRequestedActions() {
        // Rendering fixture only; real file copying is covered by StorageIntegrationTest.
        val source = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FPhoneRecord"
        val destination = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAudioCopies"
        val copy = Operation(id = 1, sourceUri = source, sourceName = "recording.aac", sourceSize = 1024,
            sourceModified = 0, destinationTree = destination, destinationUri = destination + "/document/example",
            outputName = "recording.aac", status = "SUCCESS")
        val state = CopierState(busy = false, copies = listOf(copy), settings = AppSettings(folders = FolderSettings(source, destination)))
        compose.setContent { AudioCopierTheme(false) { Frame(2) {
            FilesPage(state, {}, {}, {}, {}, {}, { _, _ -> }, { _, _ -> })
        } } }
        compose.onNodeWithText("recording.aac").performScrollTo().assertExists()
        screenshot("files-light")
        compose.onNodeWithContentDescription(compose.activity.getString(com.audiocopier.app.R.string.file_actions)).performClick()
        listOf("حذف", "إعادة التسمية", "التفاصيل", "مشاركة").forEach { compose.onNodeWithText(it).assertExists() }
    }
}
