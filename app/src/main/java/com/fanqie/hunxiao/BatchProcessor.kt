package com.fanqie.hunxiao

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.fanqie.hunxiao.core.GilbertShuffle.Direction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Upper bound for one batch. Also the limit enforced by [BatchProcessor.add]. */
internal const val BATCH_LIMIT = 20

internal enum class ItemStatus(val label: String) {
    PENDING("未处理"), READING("读取中"), PROCESSING("处理中"), READY("已处理"),
    SAVING("保存中"), SUCCESS("已保存到相册"), FAILED("失败"), CHECK("待核对")
}

/** Locally processed results can be written to the gallery on demand. */
internal val ItemStatus.isSaveable: Boolean
    get() = this == ItemStatus.READY || this == ItemStatus.CHECK

internal data class BatchItem(
    val id: String = UUID.randomUUID().toString(), val input: String, val name: String,
    val status: ItemStatus = ItemStatus.PENDING, val output: String? = null, val error: String? = null,
    /** True while the processed result exists in this batch's staging directory. */
    val staged: Boolean = false
) {
    /** Serves as both the viewable result and the exact bytes that get published. */
    fun stagingFile(dir: File): File? = if (staged) File(dir, id) else null
}

internal data class BatchState(
    val id: String = UUID.randomUUID().toString(), val created: Long = System.currentTimeMillis(),
    val items: List<BatchItem> = emptyList(), val direction: Direction = Direction.MIX,
    val format: ExportFormat = ExportFormat.PNG, val quality: Int = 95, val frozen: Boolean = false,
    val busy: Boolean = false, val saving: Boolean = false, val stage: String = "", val progress: Float? = null,
    val message: String? = null
) {
    /** Deterministic for a batch+item pair, so retrying a save maps to the same gallery file. */
    fun outputName(item: BatchItem) =
        "Tomato_${if (direction == Direction.MIX) "mix1" else "restore1"}_${created}_${id}_${item.id}.${format.extension}"
}

/**
 * Keeps only references and statuses, never pixels. It exists so an interrupted save can be told
 * apart from an untouched item *within one run*; it deliberately carries nothing across process
 * death, because a killed process is meant to come back to a clean slate.
 */
internal class BatchJournal(context: Context, name: String = "batch-v2.json") {
    private val file = File(context.filesDir, name)
    fun delete() { runCatching { file.delete() } }
    fun read(): BatchState? {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        check(json.getInt("schema") == 2) { "任务记录版本无法识别。" }
        val list = json.getJSONArray("items")
        require(list.length() <= BATCH_LIMIT)
        return BatchState(id = json.getString("id"), created = json.getLong("created"),
            direction = Direction.valueOf(json.getString("direction")), format = ExportFormat.valueOf(json.getString("format")),
            quality = json.getInt("quality").also { require(it in 80..100) }, frozen = json.getBoolean("frozen"),
            items = List(list.length()) { i -> list.getJSONObject(i).let {
                BatchItem(it.getString("id"), it.getString("input"), it.getString("name"),
                    ItemStatus.valueOf(it.getString("status")), it.optString("output").ifBlank { null },
                    it.optString("error").ifBlank { null }, it.optBoolean("staged"))
            } })
    }
    fun write(state: BatchState) {
        val json = JSONObject().put("schema",2).put("id",state.id).put("created",state.created)
            .put("direction",state.direction.name).put("format",state.format.name).put("quality",state.quality).put("frozen",state.frozen)
            .put("items",JSONArray().apply { state.items.forEach { item ->
                put(JSONObject().put("id",item.id).put("input",item.input).put("name",item.name)
                    .put("status",item.status.name).put("output",item.output ?: "").put("error",item.error ?: "")
                    .put("staged",item.staged))
            } })
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) { file.writeText(json.toString()); tmp.delete() }
    }
}

/**
 * Processes a queue and publishes to the gallery only when asked.
 *
 * A processed result lives in this batch's staging directory, which is what the user previews and
 * what gets written to the gallery, so saving never recomputes the pixels and a result survives the
 * UI being recreated.
 */
internal class BatchProcessor(
    /** Always the application context: this processor outlives any Activity. */
    private val app: Context,
    private val scope: CoroutineScope,
    repository: ImageRepository? = null,
    journal: BatchJournal? = null
) {
    private val repository = repository ?: ImageRepository(app)
    private val journal = journal ?: BatchJournal(app)
    private val mutable = MutableStateFlow(BatchState())
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var stopMessage = "任务已停止；已处理结果仍可选择保存，未处理项可继续。"

    /**
     * Bytes the editor session still holds. The processor outlives any Activity, so the editor
     * publishes this instead of the processor reaching into a view model.
     */
    @Volatile var retainedBytes: Long = 0L

    private fun stagingDir(batchId: String) = File(File(app.filesDir, "batch"), batchId)
    private fun stagedFor(state: BatchState, item: BatchItem): File? {
        val file = item.stagingFile(stagingDir(state.id)) ?: return null
        return if (file.isFile) file else null
    }
    private suspend fun commit(next: BatchState) {
        withContext(NonCancellable + Dispatchers.IO) { runCatching { journal.write(next) } }
        mutable.value = next
    }
    private suspend fun item(id: String, status: ItemStatus, output: String? = null, error: String? = null, staged: Boolean? = null) {
        commit(mutable.value.copy(items = mutable.value.items.map {
            if (it.id == id) it.copy(status = status, output = output ?: it.output, error = error, staged = staged ?: it.staged) else it
        }))
    }
    private fun current(id: String) = mutable.value.items.first { it.id == id }

    fun add(uris: List<Uri>) {
        if (mutable.value.frozen) return
        val existing = mutable.value.items.map { it.input }.toSet()
        val incoming = uris.distinct().filter { it.toString() !in existing }
        if (incoming.isEmpty()) return
        if (incoming.size + existing.size > BATCH_LIMIT) {
            mutable.update { it.copy(message = "一批最多 $BATCH_LIMIT 张；本次添加未导入，请减少数量。") }
            return
        }
        mutable.update { it.copy(busy = true, message = null) }
        job = scope.launch {
            try {
                val additions = withContext(Dispatchers.IO) { incoming.map { uri ->
                    // Photo Picker persistence may be unavailable for some providers.
                    runCatching { app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val name = runCatching { app.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } }.getOrNull() ?: uri.lastPathSegment ?: "图片"
                    BatchItem(input = uri.toString(), name = name)
                } }
                commit(mutable.value.copy(items = mutable.value.items + additions, message = null))
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun replaceInput(id: String, uri: Uri) {
        val old = mutable.value.items.find { it.id == id } ?: return
        if (old.status != ItemStatus.FAILED && old.status != ItemStatus.PENDING) return
        if (mutable.value.items.any { it.id != id && it.input == uri.toString() }) {
            mutable.update { it.copy(message = "这张图片已在列表中。") }
            return
        }
        mutable.update { it.copy(busy = true) }
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) { runCatching { app.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
                withContext(Dispatchers.IO) { old.stagingFile(stagingDir(mutable.value.id))?.delete() }
                commit(mutable.value.copy(items = mutable.value.items.map {
                    if (it.id == id) it.copy(input = uri.toString(), error = "已重新选择原图，请继续或重试。",
                        status = ItemStatus.PENDING, output = null, staged = false) else it
                }))
                withContext(Dispatchers.IO) { if (old.input != uri.toString()) runCatching {
                    app.contentResolver.releasePersistableUriPermission(Uri.parse(old.input),Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } }
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    /** Drops one item and its staged result. Never touches the original file or the gallery. */
    fun remove(id: String) {
        if (mutable.value.frozen) return
        mutable.update { it.copy(busy = true) }
        job = scope.launch {
            try {
                val snapshot = mutable.value
                snapshot.items.find { it.id == id }?.stagingFile(stagingDir(snapshot.id))?.let { file ->
                    withContext(Dispatchers.IO) { runCatching { file.delete() } }
                }
                commit(snapshot.copy(items = snapshot.items.filterNot { it.id == id }))
            } finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun configure(direction: Direction, format: ExportFormat, quality: Int) {
        if (mutable.value.busy || mutable.value.frozen) return
        mutable.update { it.copy(direction = direction, format = format, quality = quality.coerceIn(80,100)) }
    }

    fun stop() {
        stopMessage = "任务已停止；已处理结果仍可选择保存，未处理项可继续。"
        job?.cancel()
    }

    /** Ends the task: drops the journal and every staged result. Gallery images are untouched. */
    fun finalize() = scope.launch {
        val old = mutable.value
        mutable.value = BatchState(busy = true)
        withContext(Dispatchers.IO) {
            runCatching { old.items.forEach { item ->
                app.contentResolver.releasePersistableUriPermission(Uri.parse(item.input),Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } }
            runCatching { stagingDir(old.id).deleteRecursively() }
            journal.delete()
        }
        mutable.value = BatchState()
    }

    /** Computes the selected items and stages their results. Publishes nothing to the gallery. */
    fun process(retryFailures: Boolean = false) {
        if (mutable.value.busy || mutable.value.items.isEmpty()) return
        val targets = mutable.value.items.filter { if (retryFailures) it.status == ItemStatus.FAILED else it.status == ItemStatus.PENDING }
        if (targets.isEmpty()) {
            mutable.update { it.copy(message = "没有需要处理的图片。") }
            return
        }
        job = scope.launch {
            var activeId: String? = null
            try {
                commit(mutable.value.copy(busy = true, saving = false, message = null, stage = "准备任务", frozen = true))
                val dir = withContext(Dispatchers.IO) { stagingDir(mutable.value.id).apply { mkdirs() } }
                for (entry in targets) {
                    currentCoroutineContext().ensureActive()
                    activeId = entry.id
                    val index = mutable.value.items.indexOfFirst { it.id == entry.id } + 1
                    val prefix = "第 $index / ${mutable.value.items.size} 张"
                    var source: Bitmap? = null
                    var result: Bitmap? = null
                    try {
                        item(entry.id, ItemStatus.READING)
                        mutable.update { it.copy(stage = "$prefix · 读取", progress = null) }
                        source = repository.load(Uri.parse(entry.input), retainedBytes).bitmap
                        item(entry.id, ItemStatus.PROCESSING)
                        mutable.update { it.copy(stage = "$prefix · 处理") }
                        result = repository.transform(source, mutable.value.direction) { p -> mutable.update { it.copy(progress = p) } }
                        currentCoroutineContext().ensureActive()
                        val encoded = repository.encode(result, mutable.value.format, mutable.value.quality)
                        currentCoroutineContext().ensureActive()
                        // Staging is the source of truth for both previewing and publishing.
                        withContext(Dispatchers.IO) { File(dir, entry.id).writeBytes(encoded) }
                        item(entry.id, ItemStatus.READY, staged = true)
                        mutable.update { it.copy(progress = null) }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Throwable) {
                        item(entry.id, ItemStatus.FAILED,
                            error = when {
                                error is SecurityException -> "无法访问原图，请重新选择该项图片。"
                                error is OutOfMemoryError -> "内存不足，请关闭其他应用后重试。"
                                else -> error.message ?: "读取或处理失败。"
                            })
                        if (error is OutOfMemoryError) {
                            mutable.update { it.copy(message = "内存不足，整批已停止，已处理结果仍可保存。") }; break
                        }
                        if (isResourceFailure(error)) {
                            mutable.update { it.copy(message = "任务已停止，请检查存储空间和可用内存。") }; break
                        }
                    } finally { result?.recycle(); source?.recycle() }
                    activeId = null
                    yield()
                }
                mutable.update { it.copy(message = "处理完成。点「查看」确认效果，再决定保存或删除。") }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) {
                    activeId?.let { id ->
                        val status = current(id).status
                        if (status == ItemStatus.READING || status == ItemStatus.PROCESSING) item(id,ItemStatus.PENDING)
                    }
                }
                mutable.update { it.copy(message = stopMessage) }
            } catch (error: Throwable) {
                activeId?.let { id -> mutable.update { s -> s.copy(items = s.items.map {
                    if (it.id != id) it else if (it.status == ItemStatus.READING || it.status == ItemStatus.PROCESSING)
                        it.copy(status = ItemStatus.PENDING) else it
                }) } }
                mutable.update { it.copy(message = "任务记录未能保存，已停止。${error.message.orEmpty()}") }
            } finally { mutable.update { it.copy(busy = false, saving = false, stage = "", progress = null) } }
        }
    }

    /** Writes the given items to the gallery from their staged bytes. Already saved items are skipped. */
    fun save(ids: List<String>) {
        if (mutable.value.busy || ids.isEmpty()) return
        val targets = mutable.value.items.filter { it.id in ids && it.status.isSaveable && it.output == null }
        if (targets.isEmpty()) {
            mutable.update { it.copy(message = "选中的图片没有需要保存的内容。") }
            return
        }
        job = scope.launch {
            var activeId: String? = null
            try {
                mutable.update { it.copy(busy = true, saving = true, message = null, stage = "准备保存") }
                for (entry in targets) {
                    currentCoroutineContext().ensureActive()
                    activeId = entry.id
                    val name = mutable.value.outputName(entry)
                    try {
                        // Check the gallery first: a retry must never publish a second copy. An item whose
                        // state cannot be verified stays saveable so the user can simply try again.
                        val existing = try {
                            withContext(Dispatchers.IO) { repository.findPublished(name) }
                        } catch (error: Exception) {
                            item(entry.id, ItemStatus.READY, error = "暂时无法核对相册，请再点一次保存。")
                            mutable.update { it.copy(message = "暂时无法核对相册状态，请稍后重试。") }
                            yield()
                            continue
                        }
                        if (existing != null) {
                            deleteStaged(mutable.value, entry)
                            item(entry.id, ItemStatus.SUCCESS, existing.toString(), staged = false)
                        } else {
                            val staged = stagedFor(mutable.value, entry)
                            val data = if (staged != null) withContext(Dispatchers.IO) { staged.readBytes() }
                                else {
                                    mutable.update { it.copy(stage = "重新生成（暂存结果已不在）") }
                                    regenerate(mutable.value, entry)
                                }
                            publish(entry, name, data)
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (error: Throwable) {
                        val uncertain = current(entry.id).status == ItemStatus.SAVING
                        item(entry.id, if (uncertain) ItemStatus.CHECK else ItemStatus.READY,
                            error = when {
                                error is SecurityException -> "无法访问原图，请重新选择该项图片。"
                                error is OutOfMemoryError -> "内存不足，请关闭其他应用后重试。"
                                else -> error.message ?: "保存失败。"
                            })
                        mutable.update { it.copy(message = when {
                            error is OutOfMemoryError -> "内存不足，保存已停止，已完成的结果保留。"
                            uncertain -> "保存状态无法确认，请核对相册后重试。"
                            else -> "保存停止，请检查存储空间。"
                        }) }
                        if (uncertain || error is OutOfMemoryError || isResourceFailure(error)) break
                    }
                    yield()
                }
                mutable.update { it.copy(message = "保存完成。") }
            } catch (cancel: CancellationException) {
                withContext(NonCancellable) {
                    activeId?.let { id -> if (current(id).status == ItemStatus.SAVING) {
                        item(id, ItemStatus.CHECK, error = "保存被中断，请核对相册后重试。")
                    } }
                }
                mutable.update { it.copy(message = "保存已停止；已写入的图片保留，其余可再次保存。") }
            } catch (error: Throwable) {
                activeId?.let { id -> mutable.update { s -> s.copy(items = s.items.map {
                    if (it.id != id) it else if (it.status == ItemStatus.SAVING) it.copy(status = ItemStatus.CHECK) else it
                }) } }
                mutable.update { it.copy(message = "任务记录未能保存，已停止。${error.message.orEmpty()}") }
            } finally { mutable.update { it.copy(busy = false, saving = false, stage = "", progress = null) } }
        }
    }

    /**
     * Records the intent, then publishes. The intent is written before the insert so an interruption
     * between the two is visible instead of looking like an untouched item.
     */
    private suspend fun publish(entry: BatchItem, name: String, data: ByteArray) {
        item(entry.id, ItemStatus.SAVING)
        mutable.update { it.copy(stage = "保存到相册") }
        val uri = repository.save(data, name, mutable.value.format)
        deleteStaged(mutable.value, entry)
        item(entry.id, ItemStatus.SUCCESS, uri.toString(), staged = false)
    }

    private suspend fun deleteStaged(state: BatchState, entry: BatchItem) {
        entry.stagingFile(stagingDir(state.id))?.let { file -> withContext(Dispatchers.IO) { runCatching { file.delete() } } }
    }

    /**
     * Rebuilds a staged result from the original when the staged file is gone, and re-stages it so the
     * row keeps a preview. Performs no UI or journal updates so it can run inside an IO context.
     */
    private suspend fun regenerate(state: BatchState, entry: BatchItem): ByteArray {
        var source: Bitmap? = null
        var result: Bitmap? = null
        try {
            source = repository.load(Uri.parse(entry.input), retainedBytes).bitmap
            result = withContext(Dispatchers.Default) { repository.transform(source,state.direction) { } }
            val encoded = withContext(Dispatchers.Default) { repository.encode(result,state.format,state.quality) }
            withContext(Dispatchers.IO) {
                runCatching { File(stagingDir(state.id).apply { mkdirs() }, entry.id).writeBytes(encoded) }
            }
            return encoded
        } finally { result?.recycle(); source?.recycle() }
    }

    private fun isResourceFailure(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is android.database.sqlite.SQLiteFullException || it.message.orEmpty().contains("ENOSPC",true) ||
            it.message.orEmpty().contains("No space",true) || it.message.orEmpty().contains("space left",true)
    }

    companion object {
        fun stagingRoot(context: Context) = File(context.filesDir, "batch")
    }
}
