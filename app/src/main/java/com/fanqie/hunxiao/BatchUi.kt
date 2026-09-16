package com.fanqie.hunxiao

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fanqie.hunxiao.core.GilbertShuffle.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun RepeatDialog(execute: (Direction,Int) -> Unit, close: () -> Unit) {
    var direction by remember { mutableStateOf(Direction.MIX) }
    var input by remember { mutableStateOf("1") }
    val count = input.toIntOrNull()
    val valid = count != null && count in 1..100
    AlertDialog(onDismissRequest = close, title = { Text("指定次数") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("仅处理当前这张图片，完成后更新预览。外部图片历史次数未知。")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(direction == Direction.MIX, { direction = Direction.MIX }, label = { Text("混淆") })
                FilterChip(direction == Direction.RESTORE, { direction = Direction.RESTORE }, label = { Text("解混淆") })
            }
            OutlinedTextField(input, { input = it.take(4) }, label = { Text("次数（1～100）") }, singleLine = true,
                isError = !valid, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { if (!valid) Text("请输入 1～100 的整数") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton({ input = ((count ?: 1) - 1).coerceIn(1,100).toString() }, enabled = count != 1) { Text("−1") }
                OutlinedButton({ input = ((count ?: 0) + 1).coerceIn(1,100).toString() }, enabled = count != 100) { Text("+1") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(3,5,10).forEach { n -> SuggestionChip({ input = n.toString() }, label = { Text("$n 次") }) }
            }
            Text("只生成最终画面，不保存中间图片。次数不代表加密强度。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton({ if (valid) execute(direction,count!!) }, enabled = valid) {
        Text("${if (direction == Direction.MIX) "混淆" else "解混淆"} ${count ?: "—"} 次")
    } }, dismissButton = { TextButton(close) { Text("取消") } })
}

@Composable
internal fun BatchPanel(
    batch: BatchState, model: EditorViewModel, editor: EditorState, idle: Boolean,
    gate: ((() -> Unit)) -> Unit, settings: () -> Unit
) {
    val context = LocalContext.current
    var clearConfirm by remember { mutableStateOf(false) }
    var viewError by remember { mutableStateOf<String?>(null) }
    var replaceId by remember { mutableStateOf<String?>(null) }
    var previewId by remember { mutableStateOf<String?>(null) }
    // Selection decides what gets written to the gallery, not what gets computed.
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    val stagingDir = remember(batch.id) { File(BatchProcessor.stagingRoot(context), batch.id) }
    val replacement = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val id = replaceId
        if (uri != null && id != null) model.batch.replaceInput(id,uri)
        replaceId = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(BATCH_LIMIT)) { model.batch.add(it) }
    LaunchedEffect(editor.format,editor.quality,batch.busy,batch.frozen) {
        if (!batch.frozen && !batch.busy) model.batch.configure(batch.direction,editor.format,editor.quality)
    }
    val pending = batch.items.count { it.status == ItemStatus.PENDING }
    val ready = batch.items.filter { it.status == ItemStatus.READY }
    val failed = batch.items.count { it.status == ItemStatus.FAILED }
    // Everything staged can be saved or discarded; already saved items are excluded.
    val saveable = batch.items.filter { it.status == ItemStatus.READY && it.staged }
    val chosen = saveable.filter { it.id in selected }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("一次选好，先处理再看结果。", style = MaterialTheme.typography.headlineSmall)
        Text("每张处理 1 次 · 最多 $BATCH_LIMIT 张 · JPEG / PNG\n处理不会写入相册；处理完成后点「查看」确认效果，再决定保存或删除。" +
            "\n处理期间切到后台也会继续，可在通知栏看进度。", style = MaterialTheme.typography.bodyMedium)
        if (!batch.frozen) {
            Button({ picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = idle,
                modifier = Modifier.fillMaxWidth()) { Text(if (batch.items.isEmpty()) "选择多张图片" else "继续添加图片（${batch.items.size}/$BATCH_LIMIT）") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(batch.direction == Direction.MIX, { model.batch.configure(Direction.MIX,editor.format,editor.quality) }, enabled = idle, label = { Text("批量混淆") })
                FilterChip(batch.direction == Direction.RESTORE, { model.batch.configure(Direction.RESTORE,editor.format,editor.quality) }, enabled = idle, label = { Text("批量解混淆") })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (batch.direction == Direction.MIX) "混淆" else "解混淆"} 1 次 · ${batch.format.name}" +
                if (batch.format == ExportFormat.JPEG) " · 质量 ${batch.quality}" else " · 无损", modifier = Modifier.weight(1f))
            if (!batch.frozen) TextButton(settings,enabled = idle) { Text("设置") }
        }
        Text("结果先保存在应用内，保存后才写入相册的 Pictures/TomatoShuffle；原图不变。", style = MaterialTheme.typography.bodySmall)
        batch.message?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        if (batch.busy) {
            val progress = batch.progress
            if (progress == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            else LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Text(batch.stage, style = MaterialTheme.typography.bodySmall)
            if (batch.frozen && !batch.saving) OutlinedButton({ model.batch.stop() }, modifier = Modifier.fillMaxWidth()) { Text("停止任务") }
        }
        if (batch.items.isNotEmpty()) {
            val success = batch.items.count { it.status == ItemStatus.SUCCESS }
            val checks = batch.items.count { it.status == ItemStatus.CHECK }
            Text("${batch.items.size} 张 · 已处理 ${ready.size} · 已保存 $success · 失败 $failed · 未处理 $pending · 待核对 $checks",
                style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(batch.items,key = { it.id }) { item ->
                    val staged = item.status == ItemStatus.READY && item.staged
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (staged) Checkbox(checked = item.id in selected,
                                onCheckedChange = { on -> selected = if (on) selected + item.id else selected - item.id },
                                enabled = idle)
                            // Processed results preview themselves; untouched items still show the original.
                            Thumbnail(source = if (staged) File(stagingDir,item.id) else null,
                                fallbackUri = item.output ?: item.input,
                                onClick = if (item.status == ItemStatus.READY || item.status == ItemStatus.SUCCESS) {
                                    { previewId = item.id }
                                } else null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.name, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                Text(if (item.status == ItemStatus.READY && !item.staged) "结果已失效" else item.status.label,
                                    color = if (item.status == ItemStatus.FAILED || item.status == ItemStatus.CHECK) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                                item.error?.let { Text(it,style = MaterialTheme.typography.bodySmall) }
                                if (batch.frozen && (item.status == ItemStatus.FAILED || item.status == ItemStatus.PENDING)) {
                                    TextButton({ replaceId = item.id; replacement.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },enabled = idle) { Text("重新选择原图") }
                                }
                            }
                            if (!batch.frozen) TextButton({ model.batch.remove(item.id) },enabled = idle) { Text("移除") }
                            if (item.status == ItemStatus.SUCCESS && item.output != null) TextButton({
                                try {
                                    val uri = Uri.parse(item.output)
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,batch.format.mime).apply {
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        clipData = ClipData.newRawUri("处理结果",uri)
                                    })
                                } catch (_: Exception) { viewError = "无法打开结果，请在相册的 TomatoShuffle 文件夹查看。" }
                            },enabled = idle) { Text("查看") }
                        }
                    }
                }
            }
            if (pending > 0) Button({ gate { model.startBatch() } },enabled = idle, modifier = Modifier.fillMaxWidth()) {
                Text(if (batch.frozen) "继续处理未处理项" else if (batch.direction == Direction.MIX) "开始混淆并处理" else "开始解混淆并处理")
            }
            if (failed > 0) OutlinedButton({ gate { model.startBatch(true) } },enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("仅重试失败项（$failed）") }
            if (saveable.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("选择要保存的图片", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton({ selected = saveable.map { it.id }.toSet() },enabled = idle) { Text("全选") }
                    TextButton({ selected = emptySet() },enabled = idle) { Text("全不选") }
                }
                Button({ gate { model.saveBatch(chosen.map { it.id }) } },enabled = idle && chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (chosen.isEmpty()) "请先勾选要保存的图片" else "保存选中的 ${chosen.size} 张到相册")
                }
                if (chosen.size != saveable.size) OutlinedButton({ gate { model.saveBatch(saveable.map { it.id }) } },enabled = idle, modifier = Modifier.fillMaxWidth()) {
                    Text("全部保存（${saveable.size} 张）")
                }
                Text("已保存的图片不会重复生成；未勾选的结果会保留在应用内，可稍后再决定。", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (batch.items.isNotEmpty()) TextButton({ clearConfirm = true },enabled = idle) { Text("清空列表 / 新建任务") }
    }
    if (clearConfirm) AlertDialog(onDismissRequest = { clearConfirm = false },title = { Text("清空任务列表？") },
        text = { Text("会丢弃应用内未保存的处理结果，已经保存到相册的图片会保留。") },
        confirmButton = { TextButton({ model.finalizeBatch(); clearConfirm = false }) { Text("清空列表") } },
        dismissButton = { TextButton({ clearConfirm = false }) { Text("取消") } })
    viewError?.let { AlertDialog(onDismissRequest = { viewError = null },text = { Text(it) },confirmButton = { TextButton({ viewError = null }) { Text("知道了") } }) }
    previewId?.let { id ->
        val item = batch.items.find { it.id == id }
        if (item == null) previewId = null
        else ResultPreview(item = item, staged = if (item.status == ItemStatus.READY) File(stagingDir,id) else null,
            uri = item.output ?: item.input, onClose = { previewId = null })
    }
}

/** Full-screen look at a processed result, so a decision to keep or discard is an informed one. */
@Composable
private fun ResultPreview(item: BatchItem, staged: File?, uri: String, onClose: () -> Unit) {
    val context = LocalContext.current
    // A full-size result is a real bitmap allocation, so a decode failure or a heap squeeze is
    // reported instead of crashing the preview.
    var loadFailed by remember(item.id) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, item.id, staged?.length()) {
        value = withContext(Dispatchers.IO) {
            try {
                val fromFile = staged?.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
                fromFile ?: runCatching { context.contentResolver.loadThumbnail(Uri.parse(uri),Size(2048,2048),null) }.getOrNull()
            } catch (_: OutOfMemoryError) { null } catch (_: Exception) { null }
        }
        loadFailed = value == null
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        Box(Modifier.fillMaxSize().background(Color(0xFF171A17))) {
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset = if (scale == 1f) Offset.Zero else Offset(
                        (offset.x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                        (offset.y + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
                }
            }) {
                bitmap?.let { Image(it.asImageBitmap(), "处理结果预览", Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                }, contentScale = ContentScale.Fit) }
            }
            IconButton(onClick = onClose, Modifier.align(Alignment.TopStart).padding(16.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭预览", tint = Color.White)
            }
            Text(item.name, Modifier.align(Alignment.TopCenter).padding(24.dp), color = Color.White,
                style = MaterialTheme.typography.bodySmall)
            if (loadFailed) Text("无法显示预览；结果仍可保存或删除。", Modifier.align(Alignment.Center), color = Color.White,
                style = MaterialTheme.typography.bodyMedium)
            Text("双指缩放 · 拖动查看", Modifier.align(Alignment.BottomCenter).padding(28.dp), color = Color.White,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Shows the processed result from [source] when it exists, otherwise the original thumbnail.
 * Tapping behaves the same as the row's 查看 button.
 */
@Composable
private fun Thumbnail(source: File?, fallbackUri: String, onClick: (() -> Unit)?) {
    val context = LocalContext.current
    val key = source?.let { "${it.path}:${it.length()}" } ?: fallbackUri
    val thumbnail by produceState<Bitmap?>(null, key) {
        value = withContext(Dispatchers.IO) {
            val fromFile = source?.takeIf { it.isFile }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
            fromFile ?: runCatching { context.contentResolver.loadThumbnail(Uri.parse(fallbackUri),Size(96,96),null) }.getOrNull()
        }
    }
    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center) {
        thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            ?: Text("图片", style = MaterialTheme.typography.labelSmall)
    }
}
