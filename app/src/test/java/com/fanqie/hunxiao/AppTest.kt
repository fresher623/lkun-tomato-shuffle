package com.fanqie.hunxiao

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.fanqie.hunxiao.core.GilbertShuffle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun landingAndSettingsRender() {
        compose.onNodeWithText("番茄混淆").assertIsDisplayed()
        compose.onNode(hasText("选择图片") and hasClickAction()).assertIsDisplayed()
        screenshot("home")
        compose.onNodeWithContentDescription("帮助与设置").performClick()
        compose.onNodeWithText("PNG · 推荐").assertIsDisplayed()
        compose.onNodeWithText("JPEG · 更小体积").performClick()
        compose.waitUntil(10_000) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodesWithText("JPEG 质量：95").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("JPEG 质量：95").assertExists()
        screenshot("settings")
    }

    @Test fun importMixRestoreAndResetThroughUi() {
        val file = sampleFile()
        lateinit var model: EditorViewModel
        compose.runOnUiThread {
            model = ViewModelProvider(compose.activity)[EditorViewModel::class.java]
            model.load(Uri.fromFile(file))
        }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        assertNull("Import failed", model.state.value.error)
        assertNotNull("Import produced no image", model.state.value.image)
        val before = pixels(model.state.value.image!!)
        compose.activityRule.scenario.recreate()
        compose.runOnIdle { assertArrayEquals(before, pixels(model.state.value.image!!)) }
        compose.onNodeWithText("混淆", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        assertNull(model.state.value.error)
        assertEquals(1,model.state.value.mixCount)
        assertFalse(before.contentEquals(pixels(model.state.value.image!!)))
        screenshot("mixed")
        compose.onNodeWithText("解混淆", useUnmergedTree = true).performScrollTo().performClick()
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        assertNull(model.state.value.error)
        assertEquals(1,model.state.value.restoreCount)
        assertArrayEquals(before, pixels(model.state.value.image!!))
        compose.onNodeWithText("重置到导入状态").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, model.state.value.mixCount); assertEquals(0, model.state.value.restoreCount) }
        screenshot("restored")
    }

    @Test fun pngFileRoundTripPreservesPixels(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val repo = ImageRepository(context)
        val loaded = repo.load(Uri.fromFile(sampleFile())).bitmap
        val mixed = repo.transform(loaded, GilbertShuffle.Direction.MIX) {}
        val file = File(context.cacheDir,"round-trip.png")
        file.outputStream().use { assertTrue(mixed.compress(Bitmap.CompressFormat.PNG,95,it)) }
        val reloaded = repo.load(Uri.fromFile(file)).bitmap
        val restored = repo.transform(reloaded, GilbertShuffle.Direction.RESTORE) {}
        assertArrayEquals(pixels(loaded), pixels(restored))
        Unit
    }

    private fun sampleFile(): File {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(context.cacheDir, "test-source.png")
        val bitmap = Bitmap.createBitmap(127, 83, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(127*83) { i ->
            val x=i%127; val y=i/127
            when {
                (x-96)*(x-96)+(y-21)*(y-21)<121 -> 0xffedbd7e.toInt()
                y>68-x/5 -> 0xff64876c.toInt()
                y>38+kotlin.math.abs(x-43)/2 -> 0xff93a994.toInt()
                else -> 0xff000000.toInt() or ((205+y/4) shl 16) or ((225+y/5) shl 8) or 228
            }
        },0,127,0,0,127,83)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        return file
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width*bitmap.height).also {
        bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
    }
    private fun screenshot(name: String) {
        lateinit var output: Bitmap
        compose.runOnIdle {
            val view = if (name == "settings") ShadowDialog.getLatestDialog().window!!.decorView
                else compose.activity.window.decorView
            output = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(output))
        }
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir,"$name.png").outputStream().use { output.compress(Bitmap.CompressFormat.PNG,100,it) }
        output.recycle()
    }
}
