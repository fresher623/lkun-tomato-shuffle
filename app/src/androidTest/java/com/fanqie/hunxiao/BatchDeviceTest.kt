package com.fanqie.hunxiao

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.fanqie.hunxiao.core.GilbertShuffle
import com.fanqie.hunxiao.core.GilbertShuffle.Direction
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Device-level checks for the batch pipeline. These are the tests that can verify claims Robolectric
 * cannot: that processing leaves the gallery untouched, that saving publishes exactly the selected
 * items, and that staged results survive on disk.
 */
@RunWith(AndroidJUnit4::class)
class BatchDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val repo = ImageRepository(context)
    private val owned = mutableListOf<Uri>()
    private val files = mutableListOf<File>()
    private val dirs = mutableListOf<File>()

    private fun source(index: Int, width: Int = 31, height: Int = 17): Uri {
        val bitmap = Bitmap.createBitmap(IntArray(width*height) { 0xff000000.toInt() or ((it*7919+index*17) and 0x00ffffff) },width,height,Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir,"batch-test-${UUID.randomUUID()}.png").also { files += it }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle()
        return Uri.fromFile(file)
    }
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width*bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
    private suspend fun idle(processor: BatchProcessor) = withTimeout(180_000) { while (processor.state.value.busy) delay(20) }
    private suspend fun act(processor: BatchProcessor, block: () -> Unit) { withContext(Dispatchers.Main) { block() }; idle(processor) }
    /** Starts work without waiting, so a stop can be issued mid-flight. */
    private suspend fun launch(processor: BatchProcessor, block: () -> Unit) { withContext(Dispatchers.Main) { block() } }
    private suspend fun fixture(block: suspend (CoroutineScope) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { block(scope) } finally {
            scope.cancel()
            owned.distinct().forEach { context.contentResolver.delete(it,null,null) }
            files.forEach { it.delete() }
            dirs.forEach { it.deleteRecursively() }
        }
    }
    private fun rememberOutputs(processor: BatchProcessor) { owned += processor.state.value.items.mapNotNull { it.output?.let(Uri::parse) } }
    private fun statuses(processor: BatchProcessor) = processor.state.value.items.map { it.status }
    private fun stagingDir(processor: BatchProcessor) =
        File(BatchProcessor.stagingRoot(context), processor.state.value.id).also { dirs += it }
    /** The staged file the user previews and that saving must reuse. */
    private fun staged(processor: BatchProcessor, id: String): File? =
        File(stagingDir(processor), id).takeIf { it.isFile }
    /** Counts published rows carrying the app-generated output name. */
    private fun galleryRows(processor: BatchProcessor): Int = processor.state.value.items.sumOf { item ->
        val name = processor.state.value.outputName(item)
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",arrayOf(name),null)?.use { it.count } ?: 0
    }

    @Test fun processingStagesResultsWithoutPublishingAndSavesOnlySelected(): Unit = runBlocking { fixture { scope ->
        val p = BatchProcessor(context,scope); idle(p)
        val sources = List(3) { source(it) }
        act(p) { p.add(sources) }
        assertEquals(3,p.state.value.items.size)
        act(p) { p.process() }
        // Processing stages every result and must leave the gallery untouched.
        assertEquals(3,p.state.value.items.count { it.status == ItemStatus.READY && it.staged })
        assertEquals("处理不应写入相册",0,galleryRows(p))
        assertEquals(0,p.state.value.items.count { it.output != null })
        for (item in p.state.value.items) {
            val file = staged(p,item.id)
            assertNotNull("每项都应有暂存结果",file)
            assertTrue(file!!.length() > 0)
        }
        // Saving exactly one selected item publishes exactly one file.
        val target = p.state.value.items.first()
        act(p) { p.save(listOf(target.id)) }
        rememberOutputs(p)
        assertEquals(ItemStatus.SUCCESS,p.state.value.items.first { it.id == target.id }.status)
        assertEquals(1,galleryRows(p))
        assertEquals("保存后暂存应被清理",null,staged(p,target.id))
        assertEquals(2,p.state.value.items.count { it.status == ItemStatus.READY && it.staged })
        // The published bytes are the staged bytes, and the pixels are the transformed original.
        val published = repo.load(Uri.parse(p.state.value.items.first { it.id == target.id }.output)).bitmap
        val original = repo.load(Uri.parse(target.input)).bitmap
        assertArrayEquals(GilbertShuffle.transform(pixels(original),original.width,original.height,Direction.MIX),pixels(published))
        original.recycle(); published.recycle()
        // Saving the same item again must not create a second copy.
        act(p) { p.save(listOf(target.id)) }
        assertEquals(1,galleryRows(p))
        // Saving the rest completes the batch.
        act(p) { p.save(p.state.value.items.filter { it.output == null }.map { it.id }) }
        rememberOutputs(p)
        assertEquals(3,p.state.value.items.count { it.status == ItemStatus.SUCCESS })
        assertEquals(3,galleryRows(p))
    } }

    @Test fun finalizeDropsStagedResultsAndJournalButKeepsGallery(): Unit = runBlocking { fixture { scope ->
        val p = BatchProcessor(context,scope); idle(p)
        act(p) { p.add(listOf(source(1),source(2))) }
        act(p) { p.process() }
        act(p) { p.save(listOf(p.state.value.items.first().id)) }
        rememberOutputs(p)
        val dir = stagingDir(p)
        assertTrue(dir.isDirectory)
        act(p) { p.finalize() }
        assertTrue(p.state.value.items.isEmpty())
        assertFalse("清空列表应删除暂存目录",dir.exists())
        // Clearing the list never deletes album images.
        assertEquals(1,galleryRowsByName(owned.mapNotNull { outputName(it) }))
    } }
    private fun outputName(uri: Uri): String? = context.contentResolver.query(uri,
        arrayOf(MediaStore.Images.Media.DISPLAY_NAME),null,null,null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    private fun galleryRowsByName(names: List<String>): Int = names.distinct().sumOf { name ->
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ?",arrayOf(name),null)?.use { it.count } ?: 0
    }

    @Test fun stagedFileMissingIsRegeneratedOnSave(): Unit = runBlocking { fixture { scope ->
        val p = BatchProcessor(context,scope); idle(p)
        act(p) { p.add(listOf(source(1),source(2))) }
        act(p) { p.process() }
        // Simulate the system clearing staged results while the list still references them.
        staged(p,p.state.value.items.first().id)!!.delete()
        act(p) { p.save(p.state.value.items.map { it.id }) }
        rememberOutputs(p)
        assertEquals(2,p.state.value.items.count { it.status == ItemStatus.SUCCESS })
        assertEquals(2,galleryRows(p))
        for (item in p.state.value.items) {
            val original = repo.load(Uri.parse(item.input)).bitmap
            val actual = repo.load(Uri.parse(item.output)).bitmap
            assertArrayEquals(GilbertShuffle.transform(pixels(original),original.width,original.height,Direction.MIX),pixels(actual))
            original.recycle(); actual.recycle()
        }
    } }

    @Test fun limitRejectsMoreThanTwentyAndBadFileRetryUsesOriginal(): Unit = runBlocking { fixture { scope ->
        val p = BatchProcessor(context,scope); idle(p)
        act(p) { p.add(List(BATCH_LIMIT + 1) { Uri.parse("content://invalid/$it") }) }
        assertTrue(p.state.value.items.isEmpty())
        assertTrue(p.state.value.message!!.contains(BATCH_LIMIT.toString()))
        val valid = source(1)
        val broken = File(context.cacheDir,"batch-broken-${UUID.randomUUID()}.png").also { files += it; it.writeText("broken") }
        act(p) { p.add(listOf(valid,Uri.fromFile(broken),source(3))) }
        act(p) { p.configure(Direction.RESTORE,ExportFormat.PNG,95) }
        act(p) { p.process() }
        assertEquals(listOf(ItemStatus.READY,ItemStatus.FAILED,ItemStatus.READY),statuses(p))
        assertEquals(0,galleryRows(p))
        // Repairing the input lets the retry succeed from the original, not from a broken state.
        File(valid.path!!).copyTo(broken,overwrite = true)
        act(p) { p.configure(Direction.MIX,ExportFormat.JPEG,80) }
        assertEquals(Direction.RESTORE,p.state.value.direction)
        assertEquals(ExportFormat.PNG,p.state.value.format)
        act(p) { p.process(true) }
        act(p) { p.save(p.state.value.items.filter { it.output == null }.map { it.id }) }
        rememberOutputs(p)
        assertEquals(3,p.state.value.items.count { it.status == ItemStatus.SUCCESS })
        val original = repo.load(valid).bitmap
        val result = repo.load(Uri.parse(p.state.value.items[1].output)).bitmap
        assertArrayEquals(GilbertShuffle.transform(pixels(original),original.width,original.height,Direction.RESTORE),pixels(result))
        original.recycle(); result.recycle()
    } }

    @Test fun stopDuringComputeKeepsPendingAndDiscardsNothingStaged(): Unit = runBlocking { fixture { scope ->
        val entered = CompletableDeferred<Unit>()
        val slow = object: ImageRepository(context) {
            override suspend fun transform(bitmap: Bitmap, direction: Direction, rounds: Int, retainedBytes: Long, progress: (Float)->Unit): Bitmap {
                entered.complete(Unit); delay(30_000)
                return super.transform(bitmap,direction,rounds,retainedBytes,progress)
            }
        }
        val p = BatchProcessor(context,scope,slow); idle(p)
        act(p) { p.add(listOf(source(1),source(2))) }
        launch(p) { p.process() }; withTimeout(10_000) { entered.await() }
        act(p) { p.stop() }
        assertEquals(listOf(ItemStatus.PENDING,ItemStatus.PENDING),statuses(p))
        assertEquals(0,galleryRows(p))
        assertFalse(p.state.value.busy)
    } }

    @Test fun resourceFailureStopsSavingInsteadOfFailingEveryItem(): Unit = runBlocking { fixture { scope ->
        val full = object: ImageRepository(context) {
            override fun save(encoded: ByteArray, name: String, format: ExportFormat): Uri { throw java.io.IOException("ENOSPC") }
        }
        val p = BatchProcessor(context,scope,full); idle(p)
        act(p) { p.add(listOf(source(1),source(2))) }
        act(p) { p.process() }
        assertEquals(2,p.state.value.items.count { it.status == ItemStatus.READY })
        act(p) { p.save(p.state.value.items.map { it.id }) }
        // The first failed save is uncertain, so it asks to be checked; the queue stops there and the
        // rest stay simply ready rather than all being marked failed.
        assertEquals(listOf(ItemStatus.CHECK,ItemStatus.READY),statuses(p))
        assertTrue(p.state.value.items.all { it.staged })
    } }

    @Test fun largeBatchStagesSequentiallyAndRoundTrips(): Unit = runBlocking { fixture { scope ->
        val p = BatchProcessor(context,scope); idle(p)
        val bigSource = source(1,1200,900)
        val inputs = List(6) { i -> File(context.cacheDir,"large-${UUID.randomUUID()}-$i.png").also {
            files += it; File(bigSource.path!!).copyTo(it)
        }.let(Uri::fromFile) }
        act(p) { p.add(inputs) }
        val start = System.currentTimeMillis()
        act(p) { p.process() }
        assertEquals(p.state.value.message,6,p.state.value.items.count { it.status == ItemStatus.READY && it.staged })
        assertEquals("处理不应写入相册",0,galleryRows(p))
        android.util.Log.i("TomatoValidation","6 x 1200x900 processed+staged elapsedMs=${System.currentTimeMillis()-start}")
        act(p) { p.save(p.state.value.items.map { it.id }) }
        rememberOutputs(p)
        assertEquals(6,p.state.value.items.count { it.status == ItemStatus.SUCCESS })
        assertEquals(6,galleryRows(p))
        // Saving reused the staged bytes, so nothing was recomputed: all staged files are gone.
        assertTrue(p.state.value.items.none { staged(p,it.id) != null })
    } }
}
