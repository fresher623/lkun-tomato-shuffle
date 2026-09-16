package com.fanqie.hunxiao

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fanqie.hunxiao.core.GilbertShuffle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Application.preferences by preferencesDataStore("editor")
data class EditorState(
    val image: Bitmap? = null, val busy: String? = null, val progress: Float? = null,
    val cancellable: Boolean = false, val mixCount: Int = 0, val restoreCount: Int = 0,
    val format: ExportFormat = ExportFormat.PNG, val quality: Int = 95,
    val error: String? = null, val note: String? = null, val lastOperation: String = "original"
)
sealed interface EditorEvent {
    data class Message(val text: String) : EditorEvent
    data class Share(val uri: Uri, val mime: String) : EditorEvent
}
class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ImageRepository(application)
    private val mutable = MutableStateFlow(EditorState())
    val state = mutable.asStateFlow()
    private val eventsChannel = Channel<EditorEvent>(Channel.BUFFERED)
    val events = eventsChannel.receiveAsFlow()
    private var original: Bitmap? = null
    private var task: Job? = null
    /** Shared with the foreground service so a batch survives the Activity being recreated. */
    internal val batch: BatchProcessor = BatchHost.processor(application)
    internal fun startBatch(retryFailures: Boolean = false) {
        if (mutable.value.busy != null) return
        batch.retainedBytes = retained()
        BatchService.start(getApplication())
        batch.process(retryFailures)
    }
    internal fun saveBatch(ids: List<String>) {
        if (mutable.value.busy != null) return
        batch.retainedBytes = retained()
        BatchService.start(getApplication())
        batch.save(ids)
    }
    /** Ends the batch task and releases its staged results, then stops the progress notification. */
    internal fun finalizeBatch() {
        if (mutable.value.busy != null || batch.state.value.busy) return
        BatchService.stop(getApplication())
        batch.finalize()
    }
    private fun retained(): Long =
        listOfNotNull(original, mutable.value.image).distinct().sumOf { it.allocationByteCount.toLong() }
    private val formatKey = stringPreferencesKey("format")
    private val qualityKey = intPreferencesKey("quality")

    init {
        viewModelScope.launch {
            application.preferences.data.catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }.collect { prefs ->
                mutable.update { it.copy(format = ExportFormat.entries.find { f -> f.name == prefs[formatKey] } ?: ExportFormat.PNG,
                    quality = (prefs[qualityKey] ?: 95).coerceIn(80, 100)) }
            }
        }
    }

    fun load(uri: Uri) = runTask("正在读取图片", true) {
        val loaded = repository.load(uri)
        original = loaded.bitmap
        mutable.update { it.copy(image = loaded.bitmap, mixCount = 0, restoreCount = 0, lastOperation = "original",
            note = if (loaded.flattened) "透明通道已合成黑色背景，以适配网站的 JPEG 处理。" else null) }
    }

    fun transform(direction: GilbertShuffle.Direction, rounds: Int = 1) {
        if (rounds !in 1..100) { mutable.update { it.copy(error = "次数必须为 1～100 的整数。") }; return }
        val image = mutable.value.image ?: return
        runTask(if (direction == GilbertShuffle.Direction.MIX) "正在混淆" else "正在解混淆", true) {
            val held = retained() - image.allocationByteCount.toLong()
            val result = repository.transform(image, direction, rounds, held) { p -> mutable.update { it.copy(progress = p) } }
            mutable.update { it.copy(image = result, lastOperation = "${if (direction == GilbertShuffle.Direction.MIX) "mix" else "restore"}$rounds",
                mixCount = it.mixCount + if (direction == GilbertShuffle.Direction.MIX) rounds else 0,
                restoreCount = it.restoreCount + if (direction == GilbertShuffle.Direction.RESTORE) rounds else 0) }
        }
    }

    fun reset() {
        if (mutable.value.busy != null || batch.state.value.busy) return
        mutable.update { it.copy(image = original, mixCount = 0, restoreCount = 0, error = null, lastOperation = "original") }
    }
    /** Drops the current session image, leaving the exported files and the batch list untouched. */
    fun clearImage() {
        if (mutable.value.busy != null || batch.state.value.busy) return
        original = null
        mutable.update { it.copy(image = null, mixCount = 0, restoreCount = 0, error = null, note = null,
            lastOperation = "original", progress = null) }
    }
    fun cancel() { if (mutable.value.cancellable) task?.cancel() }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun export(share: Boolean) {
        val snapshot = mutable.value
        val bitmap = snapshot.image ?: return
        runTask(if (share) "正在准备分享" else "正在保存图片", false) {
            val uri = withContext(NonCancellable + Dispatchers.IO) {
                if (share) repository.share(bitmap, snapshot.format, snapshot.quality, snapshot.lastOperation)
                else repository.save(bitmap, snapshot.format, snapshot.quality, repository.filename(snapshot.format,snapshot.lastOperation))
            }
            eventsChannel.send(if (share) EditorEvent.Share(uri, snapshot.format.mime)
                else EditorEvent.Message("已保存到相册 · Pictures/TomatoShuffle"))
        }
    }
    fun setFormat(format: ExportFormat) = preferenceEdit {
        getApplication<Application>().preferences.edit { it[formatKey] = format.name }
    }
    fun setQuality(quality: Int) = preferenceEdit {
        getApplication<Application>().preferences.edit { it[qualityKey] = quality.coerceIn(80, 100) }
    }
    private fun preferenceEdit(block: suspend () -> Unit) {
        viewModelScope.launch { try { block() } catch (error: Exception) {
            if (error is CancellationException) throw error
            mutable.update { it.copy(error = "设置未能保存，请重试。") }
        } }
    }
    private fun runTask(label: String, cancellable: Boolean, block: suspend () -> Unit) {
        if (mutable.value.busy != null || batch.state.value.busy) return
        mutable.update { it.copy(busy = label, progress = null, cancellable = cancellable, error = null) }
        task = viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: OutOfMemoryError) { mutable.update { it.copy(error = "内存不足，请关闭其他应用后重试，或选择尺寸较小的原图。") } }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "处理失败，请重新选择图片。") } }
            finally { mutable.update { it.copy(busy = null, progress = null, cancellable = false) } }
        }
    }
}
