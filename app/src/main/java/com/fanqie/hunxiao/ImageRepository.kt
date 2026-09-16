package com.fanqie.hunxiao

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.fanqie.hunxiao.core.GilbertShuffle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
enum class ExportFormat(val extension: String, val mime: String, val codec: Bitmap.CompressFormat) {
    PNG("png", "image/png", Bitmap.CompressFormat.PNG),
    JPEG("jpg", "image/jpeg", Bitmap.CompressFormat.JPEG)
}

open class ImageRepository(private val context: Context) {
    /**
     * Bytes the pipeline needs per pixel at its peak, excluding the source bitmap itself.
     *
     * Measured on a real device (1.2 MP and 8 MP inputs): the decoded frame is recycled as soon as it
     * has been normalized, so the peak is the source bitmap plus four byte-per-pixel buffers - the
     * pixel array read from the bitmap, the permutation path, the permuted pixel array and the output
     * bitmap. Sixteen bytes per pixel covers that with margin.
     */
    private val bytesPerPixel = 16L

    /** Scratch space kept free so an accepted image can never be the allocation that exhausts the heap. */
    private val reserveBytes = 48L * 1024 * 1024

    /**
     * Largest image this device will accept.
     *
     * The binding constraint is the heap that is actually free, not a fraction of the total: on a
     * 256 MB heap an 8 MP photo peaks near 128 MB, so a one-size reserve leaves ordinary phone photos
     * usable while still refusing anything that could not be processed safely.
     */
    val maxPixels: Long = budgetFor(0L)

    /** Accepts images only while the whole pipeline fits in the heap left over from [retainedBytes]. */
    private fun budgetFor(retainedBytes: Long): Long {
        val headroom = Runtime.getRuntime().maxMemory() - retainedBytes - reserveBytes
        return minOf(8_000_000L, headroom / bytesPerPixel).coerceAtLeast(1L)
    }
    data class TooLarge(val pixels: Long, val limit: Long) : Exception() {
        override val message: String
            get() = "这张图片约 ${"%.1f".format(java.util.Locale.US, pixels / 1_000_000.0)} 百万像素，" +
                "超过本机上限（约 ${"%.1f".format(java.util.Locale.US, limit / 1_000_000.0)} 百万像素，单边 4000）。" +
                "为保证解混淆，不会自动缩小图片。"
    }
    data class Loaded(val bitmap: Bitmap, val flattened: Boolean)

    open suspend fun load(uri: Uri, retainedBytes: Long = 0): Loaded {
        var owned: Bitmap? = null
        try { return withContext(Dispatchers.IO) {
        val job = currentCoroutineContext()
        job.ensureActive()
        // Some document/cloud providers expose non-seekable streams. A bounded encoded
        // buffer avoids depending on a seekable file descriptor and caps hostile metadata.
        val maxEncodedBytes = minOf(32L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 16)
        val encoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            ByteArrayOutputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    job.ensureActive()
                    val count = stream.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= maxEncodedBytes) { "图片文件过大，请选择小于 ${maxEncodedBytes / 1024 / 1024} MB 的图片。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } ?: error("无法读取所选图片，请重新选择。")
        job.ensureActive()
        // Headroom is what is left after the editor session's own bitmaps, and it must cover the
        // whole per-image pipeline, not just the decoded frame.
        // Headroom shrinks while the editor holds bitmaps, so the limit is recomputed per import.
        val budget = budgetFor(retainedBytes)
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
            require(info.mimeType == "image/jpeg" || info.mimeType == "image/png") { "支持 JPEG 和 PNG，请选择静态图片。" }
            val pixels = info.size.width.toLong() * info.size.height
            if (pixels > budget || info.size.width > 4000 || info.size.height > 4000) {
                throw TooLarge(pixels, budget)
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        try {
            job.ensureActive()
            // Normalize to opaque sRGB: website encodes every operation as JPEG on black.
            // The decoded frame is recycled as soon as it has been drawn, so the two bitmaps never
            // both occupy the heap while the pixel array and traversal are later allocated.
            val bitmap = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            owned = bitmap
            bitmap.setHasAlpha(false)
            Canvas(bitmap).apply { drawColor(Color.BLACK); drawBitmap(decoded, 0f, 0f, null) }
            val flattened = decoded.hasAlpha()
            decoded.recycle()
            Loaded(bitmap, flattened)
        } catch (error: Throwable) { decoded.recycle(); throw error }
        } } catch (error: Throwable) { owned?.recycle(); throw error }
    }

    /**
     * The permutation allocates about five frames, so a source that was accepted while the editor
     * held nothing can still be too large once the editor session is holding bitmaps. Checking here
     * turns a raw OutOfMemoryError into a message the user can act on.
     */
    private fun requireTransformBudget(bitmap: Bitmap, retainedBytes: Long) {
        val budget = budgetFor(retainedBytes)
        val pixels = bitmap.width.toLong() * bitmap.height
        if (pixels > budget) throw TooLarge(pixels, budget)
    }

    open suspend fun transform(bitmap: Bitmap, direction: GilbertShuffle.Direction, rounds: Int = 1,
                               retainedBytes: Long = 0, progress: (Float) -> Unit): Bitmap {
        var owned: Bitmap? = null
        try { return withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()
        job.ensureActive()
        requireTransformBudget(bitmap, retainedBytes)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val output = GilbertShuffle.transform(pixels, bitmap.width, bitmap.height, direction, rounds) {
            job.ensureActive(); progress(it)
        }
        job.ensureActive()
        Bitmap.createBitmap(output, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply { owned = this; setHasAlpha(false) }
        } } catch (error: Throwable) { owned?.recycle(); throw error }
    }

    open fun save(bitmap: Bitmap, format: ExportFormat, quality: Int, name: String = filename(format)): Uri =
        save(encode(bitmap, format, quality), name, format)

    /** Encodes once so the batch can hold results in memory and publish them later. */
    open fun encode(bitmap: Bitmap, format: ExportFormat, quality: Int): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            check(bitmap.compress(format.codec, quality, buffer)) { "图片编码失败。" }
            buffer.toByteArray()
        }

    /** Publishes already-encoded bytes. The caller supplies the final name so a retry is idempotent. */
    open fun save(encoded: ByteArray, name: String, format: ExportFormat): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TomatoShuffle")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建图片，请检查剩余存储空间。")
        try {
            resolver.openOutputStream(uri)?.use { it.write(encoded) } ?: error("无法写入图片。")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                "图片未能完成保存。"
            }
            return uri
        } catch (error: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun share(bitmap: Bitmap, format: ExportFormat, quality: Int, operation: String = "original"): Uri {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        // Keep recent files alive for receiving apps. Only remove old share-cache files.
        dir.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
            ?.forEach { it.delete() }
        val file = File(dir, filename(format, operation))
        try {
            file.outputStream().use { check(bitmap.compress(format.codec, quality, it)) { "图片编码失败。" } }
            return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (error: Exception) { file.delete(); throw error }
    }

    fun filename(format: ExportFormat, operation: String = "original") = "Tomato_${operation}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.${format.extension}"

    /** Only exact app-generated names are reconciled. Pending rows are our incomplete writes. */
    @Suppress("DEPRECATION")
    fun findPublished(name: String): Uri? {
        require(name.matches(Regex("Tomato_(mix1|restore1)_[0-9]+_[a-f0-9-]+_[a-f0-9-]+\\.(png|jpg)")))
        val resolver = context.contentResolver
        val collection = MediaStore.setIncludePending(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        val matches = mutableListOf<Pair<Uri, Boolean>>()
        resolver.query(collection, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_PENDING),
            "${MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            arrayOf(name, "Pictures/TomatoShuffle/"), null)?.use { cursor ->
            while (cursor.moveToNext()) matches += android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cursor.getLong(0)) to (cursor.getInt(1) == 0)
        } ?: error("无法核对相册保存状态，请稍后重新核对。")
        check(matches.count { it.second } <= 1) { "发现同名输出，请先在相册核对。" }
        matches.filter { !it.second }.forEach { (uri, _) ->
            check(resolver.delete(uri,null,null) == 1) { "未完成图片无法清理，请稍后重新核对。" }
        }
        return matches.firstOrNull { it.second }?.first
    }
}
