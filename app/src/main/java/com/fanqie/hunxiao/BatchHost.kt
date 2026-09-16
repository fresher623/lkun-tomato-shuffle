package com.fanqie.hunxiao

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Owns the single batch processor for the whole application.
 *
 * The processor must outlive the Activity, otherwise a queued batch would die with the UI while the
 * foreground service claims it is still running. The service and the UI therefore share this one
 * instance instead of each holding its own.
 */
internal object BatchHost {
    // The processor deliberately outlives every Activity, so this static reference is intended. It
    // only ever holds the application context, never an Activity.
    @SuppressLint("StaticFieldLeak")
    @Volatile private var instance: BatchProcessor? = null
    @Volatile private var instanceDir: File? = null

    fun processor(context: Context): BatchProcessor {
        val app = context.applicationContext
        val dir = app.filesDir
        instance?.let { if (instanceDir == dir) return it }
        return synchronized(this) {
            val current = instance
            if (current != null && instanceDir == dir) current
            else BatchProcessor(app,
                CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
                    // A failing item must never tear down the scope and strand the remaining queue.
                    CoroutineExceptionHandler { _, error -> android.util.Log.e("BatchHost", "未捕获的批处理异常", error) }))
                .also { instance = it; instanceDir = dir }
        }
    }

    /**
     * Called from `onCreate`: a killed process is meant to come back to a clean slate.
     *
     * Only unattached leftovers are removed. The live batch is deliberately spared - `onCreate` also
     * runs when the Activity is recreated for a configuration change, and wiping the current batch's
     * staged results there would destroy work the running processor still refers to.
     */
    fun resetStaleFiles(context: Context) {
        val app = context.applicationContext
        val live = instance?.state?.value?.takeIf { it.items.isNotEmpty() }?.id
        runCatching {
            val journal = BatchJournal(app)
            if (live == null) { journal.delete() } else if (journal.read()?.id != live) { journal.delete() }
        }
        runCatching {
            BatchProcessor.stagingRoot(app).listFiles()?.forEach { dir ->
                if (dir.name != live) dir.deleteRecursively()
            }
        }
    }

    /**
     * Number of processors, and bytes the last reset removed. Exposed so tests can reason about the
     * cached instance without reaching into private state.
     */
    internal fun hasInstance() = instance != null
}

internal fun Application.batchProcessor(): BatchProcessor = BatchHost.processor(this)
