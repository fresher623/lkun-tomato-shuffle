package com.fanqie.hunxiao

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fanqie.hunxiao.core.GilbertShuffle.Direction
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EditorDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun idle(model: EditorViewModel) = compose.waitUntil(30_000) { model.state.value.busy == null && !model.batch.state.value.busy }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    @Test fun repeatUiRotationResetAndCancellationPreservePixels() {
        lateinit var model: EditorViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }; idle(model)
        val input = Bitmap.createBitmap(IntArray(1024*768) { 0xff000000.toInt() or (it*7919 and 0xffffff) },1024,768,Bitmap.Config.ARGB_8888)
        val source = File(compose.activity.cacheDir,"editor-device-test.png")
        source.outputStream().use { input.compress(Bitmap.CompressFormat.PNG,100,it) }
        val original = pixels(input); input.recycle()
        try {
            compose.runOnUiThread { model.load(Uri.fromFile(source)) }; idle(model)
            assertNull(model.state.value.error)
            compose.onNodeWithText("l君の番茄混淆").assertExists()
            compose.onNodeWithText("指定次数…").performScrollTo().performClick()
            compose.onNode(hasSetTextAction()).performTextReplacement("101")
            compose.onNodeWithText("混淆 101 次").assertIsNotEnabled()
            compose.onNodeWithText("5 次").performClick()
            compose.onNodeWithText("混淆 5 次").performClick(); idle(model)
            assertEquals(5,model.state.value.mixCount)
            compose.activityRule.scenario.recreate()
            assertEquals(5,model.state.value.mixCount)
            compose.runOnUiThread { model.transform(Direction.RESTORE,5) }; idle(model)
            assertArrayEquals(original,pixels(model.state.value.image!!))
            val before = model.state.value
            compose.runOnUiThread { model.transform(Direction.MIX,100); model.cancel() }; idle(model)
            assertEquals(before.mixCount,model.state.value.mixCount)
            assertArrayEquals(original,pixels(model.state.value.image!!))
            compose.onNodeWithText("重置到导入状态").performScrollTo().performClick()
            assertEquals(0,model.state.value.mixCount); assertEquals(0,model.state.value.restoreCount)
            compose.onNodeWithText("清除当前图片").performScrollTo().performClick()
            assertNull(model.state.value.image)
            compose.runOnUiThread { model.load(Uri.fromFile(source)) }; idle(model)
            assertNotNull(model.state.value.image)
            compose.onNodeWithText("批量处理").performScrollTo().performClick()
            compose.onNodeWithText("选择多张图片").assertExists()
            compose.onNodeWithText("每张处理 1 次",substring = true).assertExists()
            compose.onNodeWithText("处理不会写入相册",substring = true).assertExists()
        } finally { source.delete() }
    }
}
