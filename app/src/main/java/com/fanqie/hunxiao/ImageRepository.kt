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

class ImageRepository(private val context: Context) {
    // Allow space for original/current bitmaps, two pixel arrays, traversal and output.
    val maxPixels: Long = minOf(8_000_000L, (Runtime.getRuntime().maxMemory() * 0.60 / 32).toLong())
    data class Loaded(val bitmap: Bitmap, val flattened: Boolean)

    suspend fun load(uri: Uri): Loaded = withContext(Dispatchers.IO) {
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
        val decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
            require(info.mimeType == "image/jpeg" || info.mimeType == "image/png") { "第一版支持 JPEG 和 PNG，请选择静态图片。" }
            require(info.size.width.toLong() * info.size.height <= maxPixels && info.size.width <= 4000 && info.size.height <= 4000) {
                "这张图片超过本机处理上限（约 ${maxPixels / 1_000_000} 百万像素，单边 4000）。为保证解混淆，不会自动缩小图片。"
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        try {
            job.ensureActive()
            // Normalize to opaque sRGB: website encodes every operation as JPEG on black.
            val bitmap = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
            bitmap.setHasAlpha(false)
            Canvas(bitmap).apply { drawColor(Color.BLACK); drawBitmap(decoded, 0f, 0f, null) }
            Loaded(bitmap, decoded.hasAlpha())
        } finally { decoded.recycle() }
    }

    suspend fun transform(bitmap: Bitmap, direction: GilbertShuffle.Direction, progress: (Float) -> Unit): Bitmap = withContext(Dispatchers.Default) {
        val job = currentCoroutineContext()
        job.ensureActive()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val output = GilbertShuffle.transform(pixels, bitmap.width, bitmap.height, direction) {
            job.ensureActive(); progress(it)
        }
        job.ensureActive()
        Bitmap.createBitmap(output, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).apply { setHasAlpha(false) }
    }

    fun save(bitmap: Bitmap, format: ExportFormat, quality: Int): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename(format))
            put(MediaStore.Images.Media.MIME_TYPE, format.mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/TomatoShuffle")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("无法创建图片，请检查剩余存储空间。")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                check(bitmap.compress(format.codec, quality, stream)) { "图片编码失败。" }
            } ?: error("无法写入图片。")
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1) {
                "图片未能完成保存。"
            }
            return uri
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }

    fun share(bitmap: Bitmap, format: ExportFormat, quality: Int): Uri {
        val dir = File(context.cacheDir, "shares").apply { mkdirs() }
        // Keep recent files alive for receiving apps. Only remove old share-cache files.
        dir.listFiles()?.filter { it.isFile && System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000L }
            ?.forEach { it.delete() }
        val file = File(dir, filename(format))
        try {
            file.outputStream().use { check(bitmap.compress(format.codec, quality, it)) { "图片编码失败。" } }
            return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        } catch (error: Exception) { file.delete(); throw error }
    }

    private fun filename(format: ExportFormat) = "Tomato_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.${format.extension}"
}
