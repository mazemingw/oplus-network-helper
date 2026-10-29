package com.nvmex.networkhelper.util.modem

import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nvmex.networkhelper.ui.menu.BackendUiProbeActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BackendUiLayoutTest {
    @Test fun settingsRenderAtNormalAndLargeDisplayDensity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (scale in listOf(1f, 1.3f)) {
            val intent = Intent(context, BackendUiProbeActivity::class.java).putExtra("density", scale)
            ActivityScenario.launch<BackendUiProbeActivity>(intent).use {
                instrumentation.waitForIdleSync()
                // Compose schedules its first measure/draw after Activity creation.
                Thread.sleep(800)
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                assertNotNull(bitmap)
                File(context.cacheDir, "backend-settings-${(scale * 100).toInt()}.png").outputStream().use { output ->
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                }
                bitmap.recycle()
            }
        }
    }
}
