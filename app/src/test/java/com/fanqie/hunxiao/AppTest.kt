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
import org.junit.Before
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

    /** Removes animation scales, which otherwise keep Compose from reaching idle under Robolectric. */
    @Before fun isolateProcessState() {
        val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver
        android.provider.Settings.Global.putFloat(resolver,"window_animation_scale",0f)
        android.provider.Settings.Global.putFloat(resolver,"transition_animation_scale",0f)
        android.provider.Settings.Global.putFloat(resolver,"animator_duration_scale",0f)
    }

    @Test fun landingAndSettingsRender() {
        compose.onNodeWithText("l君の番茄混淆").assertIsDisplayed()
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

    /**
     * Robolectric cannot reach Compose idle once the count dialog is on screen: its OutlinedTextField
     * requests focus and the resulting cursor/IME bookkeeping keeps the test clock busy forever
     * (AppNotIdleException) - even though no text action is performed and even with animations
     * disabled. The Settings dialog, which has no text field, idles normally. Opening the panel,
     * typing into it, the 1~100 validation message and the shortcut chips are therefore covered by
     * EditorDeviceTest on a device; this test covers the commit and bounds rules behind the panel.
     */
    @Test fun repeatPanelValidatesAndCommitsAtomically() {
        lateinit var model: EditorViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); !model.batch.state.value.busy }
        compose.runOnUiThread { model.load(Uri.fromFile(sampleFile())) }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        val before = pixels(model.state.value.image!!)
        compose.runOnUiThread { model.transform(GilbertShuffle.Direction.MIX,5) }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        assertEquals(5,model.state.value.mixCount)
        assertArrayEquals(GilbertShuffle.transform(before,127,83,GilbertShuffle.Direction.MIX,5),pixels(model.state.value.image!!))
        compose.runOnUiThread { model.transform(GilbertShuffle.Direction.RESTORE,5) }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        assertArrayEquals(before,pixels(model.state.value.image!!))
        // Out-of-range counts are refused before touching the image or the session counters.
        for (invalid in listOf(0,-1,101,Int.MAX_VALUE)) compose.runOnUiThread { model.transform(GilbertShuffle.Direction.MIX,invalid) }
        compose.runOnIdle {
            assertEquals("次数必须为 1～100 的整数。",model.state.value.error)
            assertEquals(5,model.state.value.mixCount)
            assertArrayEquals(before,pixels(model.state.value.image!!))
        }
        // An accepted count resets the error and commits atomically in one step.
        compose.runOnIdle { model.clearError() }
        compose.runOnUiThread { model.transform(GilbertShuffle.Direction.MIX,3) }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        compose.runOnIdle {
            assertNull(model.state.value.error)
            assertEquals(8,model.state.value.mixCount)
        }
    }

    /**
     * Covers the split between processing and saving. Robolectric's MediaStore cannot answer
     * `query`, so the "nothing reaches the album until asked" claim is asserted by
     * BatchDeviceTest on a device rather than by counting rows here.
     */
    /**
     * Covers processing and staging, plus the save guards that do not need a real gallery.
     *
     * Robolectric cannot answer `MediaStore.query`, so `findPublished` cannot run here and an actual
     * publish is not observable. Saving, idempotency and the "gallery stays untouched until asked"
     * claim are therefore asserted by BatchDeviceTest on a device.
     */
    @Test fun batchStagesResultsAndKeepsThemSaveable(): Unit = runBlocking {
        lateinit var model: EditorViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        startFreshTask(model)
        val sources = List(3) { writeSample(it,it.toLong()) }
        try {
            compose.runOnUiThread { model.batch.add(sources.map { Uri.fromFile(it) }) }
            awaitBatch(model)
            assertEquals(3,model.batch.state.value.items.size)
            compose.runOnUiThread {
                model.batch.configure(GilbertShuffle.Direction.MIX,ExportFormat.PNG,95)
                model.startBatch()
            }
            awaitBatch(model)
            assertEquals("处理完成。点「查看」确认效果，再决定保存或删除。",model.batch.state.value.message)
            assertEquals(3,model.batch.state.value.items.count { it.status == ItemStatus.READY })
            // Processing stages every result locally and publishes nothing.
            assertTrue(model.batch.state.value.items.all { it.output == null })
            assertTrue(model.batch.state.value.items.all { it.staged })
            val probeDir = File(BatchProcessor.stagingRoot(compose.activity), model.batch.state.value.id)
            // Each staged file is the exact artefact the row previews and a save publishes.
            val stagedBytes = HashMap<String, ByteArray>()
            for (item in model.batch.state.value.items) {
                val file = File(probeDir,item.id)
                assertTrue("missing staged result: " + file.absolutePath,file.isFile)
                stagedBytes[item.id] = file.readBytes()
            }
            assertEquals(3,stagedBytes.size)
            assertTrue("staged results must not be empty",stagedBytes.values.all { it.isNotEmpty() })
            // A failed gallery check must leave the item saveable and keep its staged result, so the
            // user can simply tap save again rather than losing work.
            val target = model.batch.state.value.items.first().id
            compose.runOnUiThread { model.saveBatch(listOf(target)) }
            awaitBatch(model)
            val afterSave = model.batch.state.value.items.first { it.id == target }
            assertTrue("an unverifiable check must not make the result unsaveable",afterSave.status.isSaveable)
            assertTrue("staged result must survive a failed save",File(probeDir,target).isFile)
        } finally { sources.forEach { it.delete() } }
    }

    /** Leaves the process looking like it was killed mid-task, so startup cleanup can be observed. */
    @Test fun staleJournalAndStagedResultsAreDroppedOnStart(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val journal = BatchJournal(context)
        val stale = BatchState(items = listOf(BatchItem(input = "content://stale/1", name = "旧任务.png", staged = true)))
        journal.write(stale)
        val staleDir = File(BatchProcessor.stagingRoot(context), stale.id).apply { mkdirs() }
        File(staleDir, stale.items.first().id).writeBytes(byteArrayOf(1,2,3))
        assertTrue(staleDir.isDirectory)
        BatchHost.resetStaleFiles(context)
        assertNull("任务记录应被清除",BatchJournal(context).read())
        assertFalse("暂存结果应被清除",staleDir.exists())
    }

    @Test fun batchLimitRejectsMoreThanTwenty(): Unit = runBlocking {
        lateinit var model: EditorViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        startFreshTask(model)
        val sources = List(3) { writeSample(100 + it,it.toLong()) }
        try {
            compose.runOnUiThread { model.batch.add(sources.map { Uri.fromFile(it) }) }
            awaitBatch(model)
            assertEquals(3,model.batch.state.value.items.size)
            // Adding past the cap is refused as a whole, so the list never ends up partially updated.
            // A refusal is synchronous and never enters the busy phase.
            val extra = List(BATCH_LIMIT - 2) { Uri.parse("content://over/$it") }
            compose.runOnUiThread { model.batch.add(extra) }
            awaitRequest(model,waitForStart = false)
            assertEquals(3,model.batch.state.value.items.size)
            assertTrue("message must state the limit",model.batch.state.value.message!!.contains(BATCH_LIMIT.toString()))
        } finally {
            compose.runOnUiThread { model.finalizeBatch() }
            awaitBatch(model)
            sources.forEach { it.delete() }
        }
    }

    /**
     * Robolectric shares one data directory across a class, and the batch processor is application
     * scoped, so its list can survive from an earlier test. Clearing it here keeps every test on a
     * state the UI itself could reach, instead of relying on whichever test ran before.
     */
    private fun startFreshTask(model: EditorViewModel) {
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); !model.batch.state.value.busy }
        compose.runOnUiThread { model.finalizeBatch() }
        awaitRequest(model,waitForStart = false)
    }

    private fun stagedFile(model: EditorViewModel, id: String): File? =
        File(BatchProcessor.stagingRoot(compose.activity), model.batch.state.value.id).let { dir ->
            File(dir,id).takeIf { it.isFile }
        }

    @Test fun clearCurrentImageReturnsToEmptyWorkbench(): Unit = runBlocking {
        lateinit var model: EditorViewModel
        compose.runOnUiThread { model = ViewModelProvider(compose.activity)[EditorViewModel::class.java] }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); !model.batch.state.value.busy }
        compose.runOnUiThread { model.load(Uri.fromFile(sampleFile())) }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        assertNotNull(model.state.value.image)
        compose.onNodeWithText("清除当前图片").performScrollTo().performClick()
        compose.runOnIdle {
            assertNull(model.state.value.image)
            assertEquals(0,model.state.value.mixCount)
            assertEquals("original",model.state.value.lastOperation)
        }
        compose.onNode(hasText("选择图片") and hasClickAction()).assertIsDisplayed()
        // The workbench is usable again after clearing.
        compose.runOnUiThread { model.load(Uri.fromFile(sampleFile())) }
        compose.waitUntil(20_000) { shadowOf(Looper.getMainLooper()).idle(); model.state.value.busy == null }
        assertNotNull(model.state.value.image)
    }

    /**
     * Batch work starts asynchronously: `startBatch`/`saveBatch` return before the queued coroutine
     * sets `busy`. Waiting only for `!busy` would therefore pass immediately and read stale state,
     * so this waits for the busy phase first. Use [awaitRequest] when the request may be refused
     * synchronously, in which case no busy phase ever happens.
     */
    private fun awaitBatch(model: EditorViewModel) = awaitRequest(model, waitForStart = true)

    private fun awaitRequest(model: EditorViewModel, waitForStart: Boolean) {
        if (waitForStart) {
            runCatching { compose.waitUntil(5_000) { shadowOf(Looper.getMainLooper()).idle(); model.batch.state.value.busy } }
        }
        compose.waitUntil(30_000) { shadowOf(Looper.getMainLooper()).idle(); !model.batch.state.value.busy }
    }

    /** Like [sampleFile] but differs per index, so each batch entry has its own pixels. */    private fun writeSample(index: Int, seed: Long): File {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        val file = File(context.cacheDir, "batch-source-$index.png")
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(64*48) { i -> (0xff000000L or ((i * 7919 + seed * 104729) and 0xffffff)).toInt() },0,64,0,0,64,48)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
        return file
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
