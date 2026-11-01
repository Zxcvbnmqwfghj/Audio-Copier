package com.audiocopier.app.ui

import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.audiocopier.app.CopierApplication
import com.audiocopier.app.MainActivity
import com.audiocopier.app.R
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34, 35], application = CopierApplication::class, qualifiers = "ar-rEG-w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @After fun cleanUp() {
        val app = ApplicationProvider.getApplicationContext<CopierApplication>()
        app.scope.cancel()
        app.database.close()
    }

    @Test fun realActivityOpensAndRemainsUsableAfterChangingLanguage() {
        compose.onNodeWithText("النسخ التلقائي").assertIsDisplayed()
        lateinit var model: CopierViewModel
        compose.runOnIdle {
            model = ViewModelProvider(compose.activity)[CopierViewModel::class.java]
        }
        compose.waitUntil(15_000) { compose.runOnIdle { !model.state.value.busy } }
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.tab_settings)).performClick()
        compose.onNodeWithText("English").assertIsEnabled()
        compose.onNodeWithText("English").performClick()
        // Pump the Android looper while DataStore posts results from its IO thread.
        compose.waitUntil(15_000) { compose.runOnIdle { model.state.value.settings.language == "en" } }
        compose.onNodeWithContentDescription("Files and folders").performClick()
        compose.onNodeWithText("Source folder").assertIsDisplayed()
        compose.onNodeWithText("Choose source folder").performClick()
        compose.runOnIdle {
            val request = shadowOf(compose.activity).nextStartedActivityForResult.intent
            assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, request.action)
            shadowOf(compose.activity).receiveResult(request, Activity.RESULT_CANCELED, null)
        }
        compose.onNodeWithText("Source folder").assertIsDisplayed()
    }
}
