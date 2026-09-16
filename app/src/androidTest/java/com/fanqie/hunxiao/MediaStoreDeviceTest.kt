package com.fanqie.hunxiao

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Requires a device/emulator: checks a real MediaStore, not Robolectric's substitute. */
@RunWith(AndroidJUnit4::class)
class MediaStoreDeviceTest {
    @Test fun sharedContentUriCanBeRead(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = ImageRepository(context)
        val bitmap = Bitmap.createBitmap(31,17,Bitmap.Config.ARGB_8888).apply { eraseColor(0xffd74c35.toInt()) }
        val uri = repository.share(bitmap,ExportFormat.PNG,95)
        assertEquals("content",uri.scheme)
        assertEquals("com.fanqie.hunxiao.files",uri.authority)
        val decoded = repository.load(uri).bitmap
        assertEquals(bitmap.getPixel(10,10),decoded.getPixel(10,10))
        context.contentResolver.delete(uri,null,null)
        bitmap.recycle(); decoded.recycle()
    }
    @Test fun savePublishesReadablePng(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = ImageRepository(context)
        val bitmap = Bitmap.createBitmap(31,17,Bitmap.Config.ARGB_8888).apply { eraseColor(0xffd74c35.toInt()) }
        val uri = repository.save(bitmap,ExportFormat.PNG,95)
        try {
            val restored = repository.load(uri).bitmap
            assertEquals(31,restored.width); assertEquals(17,restored.height)
            assertEquals(bitmap.getPixel(10,10),restored.getPixel(10,10))
            context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.Images.Media.IS_PENDING),null,null,null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0))
            }
            restored.recycle()
        } finally {
            context.contentResolver.delete(uri,null,null)
            bitmap.recycle()
        }
    }
}
