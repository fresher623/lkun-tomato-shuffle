package com.fanqie.hunxiao

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fanqie.hunxiao.core.GilbertShuffle
import kotlin.math.roundToInt

private val Tomato = Color(0xFFD74C35)
private val Ink = Color(0xFF292C27)
private val Muted = Color(0xFF777B72)
private val Cream = Color(0xFFFAF8F4)
private val Green = Color(0xFF527454)
private val Line = Color(0xFFE9E6DE)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A killed process is meant to come back to a clean slate: drop the previous journal and any
        // staged results before the UI can show them. Rotation and backgrounding do not come here.
        BatchHost.resetStaleFiles(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Tomato, onPrimary = Color.White,
                background = Cream, surface = Cream, onSurface = Ink, secondary = Green, outlineVariant = Line,
                primaryContainer = Color(0xFFFFE8DA), onPrimaryContainer = Ink,
                secondaryContainer = Color(0xFFE9EEE5), onSecondaryContainer = Green,
                surfaceContainerHigh = Color(0xFFF3EFE7), surfaceContainerHighest = Color(0xFFEFEAE0),
                surfaceContainer = Color(0xFFF7F3EB), surfaceContainerLow = Cream,
                surfaceContainerLowest = Color.White)) {
                TomatoApp()
            }
        }
    }
}

@Composable
private fun TomatoApp(model: EditorViewModel = viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val batch by model.batch.state.collectAsStateWithLifecycle()
    val idle = state.busy == null && !batch.busy
    var batchMode by rememberSaveable { mutableStateOf(false) }
    var repeatPanel by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var settings by rememberSaveable { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var notificationGranted by rememberSaveable { mutableStateOf(false) }
    var pendingBatch by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snack = remember { SnackbarHostState() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(model::load) }
    val choose = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    // The foreground service needs the notification permission on Android 13+ to show progress.
    // Asking before the batch starts keeps processing in the foreground while the dialog is up.
    // Denying it is allowed: the service still runs, the progress notification is just invisible.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notificationGranted = true
        pendingBatch?.invoke()
        pendingBatch = null
    }
    val gate: ((() -> Unit)) -> Unit = { action ->
        if (notificationGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notificationGranted = true
            action()
        } else {
            pendingBatch = action
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(model, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            model.events.collect { event ->
                when (event) {
                    is EditorEvent.Message -> snack.showSnackbar(event.text)
                    is EditorEvent.Share -> {
                        try {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = event.mime
                                putExtra(Intent.EXTRA_STREAM, event.uri)
                                clipData = ClipData.newRawUri("l君の番茄混淆图片", event.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "分享图片 · 建议发送原文件"))
                        } catch (_: Exception) { snack.showSnackbar("未找到可用的分享应用，请先保存到相册。") }
                    }
                }
            }
        }
    }
    Scaffold(containerColor = Cream, snackbarHost = { SnackbarHost(snack) }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.tomato_mark), "番茄标志", Modifier.size(42.dp)
                    .background(Color(0xFFF5EBDD), RoundedCornerShape(14.dp)))
                Spacer(Modifier.width(10.dp))
                Text("l君の番茄混淆", fontWeight = FontWeight.Bold, fontSize = 19.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { settings = true }, enabled = idle) {
                    Icon(Icons.Outlined.Tune, "帮助与设置", tint = Muted)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !batchMode, onClick = { batchMode = false }, enabled = idle, label = { Text("单张处理") })
                FilterChip(selected = batchMode, onClick = { batchMode = true }, enabled = idle, label = { Text("批量处理") })
            }
            if (batchMode) {
                BatchPanel(batch, model, state, idle, gate) { settings = true }
            } else {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (state.image == null) {
                    Text("给图片换个样子。", fontSize = 29.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Text("轻轻一点，打乱像素；再点一下，还原画面。", color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                }
                Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Tag(Icons.Outlined.WifiOff, "离线处理")
                    Tag(Icons.Outlined.Lock, "图片不上传")
                }
            }
            Surface(shape = RoundedCornerShape(26.dp), color = Color.White,
                border = androidx.compose.foundation.BorderStroke(1.dp, Line)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.image == null) "图片工作台" else "当前图片", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(if (state.image == null) "01 / 导入" else "${state.image!!.width} × ${state.image!!.height}", fontSize = 11.sp, color = Muted)
                    }
                    if (state.image == null) {
                        Column(Modifier.fillMaxWidth().height(285.dp).clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFFF8F6F1)).clickable(enabled = idle, onClick = choose),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            PictureArtwork()
                            Spacer(Modifier.height(20.dp))
                            Text("从一张图片开始", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text("支持 JPG、PNG · 保留原文件", Modifier.padding(top = 7.dp), fontSize = 12.sp, color = Muted)
                        }
                        Button(onClick = choose, enabled = idle, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(15.dp)) {
                            Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp)); Text("选择图片", fontSize = 16.sp)
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(310.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFFF2F1ED))
                            .clickable(enabled = idle) { fullscreen = true }) {
                            Image(state.image!!.asImageBitmap(), "当前图片预览，点击放大", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            Icon(Icons.Outlined.OpenInFull, null, Modifier.align(Alignment.BottomEnd).padding(12.dp)
                                .background(Color.White.copy(alpha = .9f), CircleShape).padding(8.dp).size(16.dp), tint = Ink)
                        }
                        Text("本次操作：混淆 ${state.mixCount} 次 · 解混淆 ${state.restoreCount} 次", color = Muted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { model.transform(GilbertShuffle.Direction.MIX) }, enabled = idle,
                                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Outlined.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("混淆")
                            }
                            FilledTonalButton(onClick = { model.transform(GilbertShuffle.Direction.RESTORE) }, enabled = idle,
                                modifier = Modifier.weight(1f).height(52.dp), shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFFE9EEE5), contentColor = Green)) {
                                Icon(Icons.Outlined.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("解混淆")
                            }
                        }
                        OutlinedButton(onClick = { repeatPanel = true }, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("指定次数…") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = choose, enabled = idle) { Text("换一张图片") }
                            TextButton(onClick = model::reset, enabled = idle && state.mixCount + state.restoreCount > 0) { Text("重置到导入状态") }
                        }
                        TextButton(onClick = model::clearImage, enabled = idle, modifier = Modifier.fillMaxWidth()) { Text("清除当前图片") }
                    }
                }
            }
            state.busy?.let { title ->
                Surface(color = Color(0xFFF2EDE3), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, Modifier.weight(1f), fontSize = 14.sp)
                            if (state.cancellable) TextButton(onClick = model::cancel) { Text("取消") }
                        }
                        val displayedProgress = state.progress
                        if (displayedProgress != null) LinearProgressIndicator(progress = { displayedProgress }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (state.image != null) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("导出图片", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { settings = true }, enabled = idle) {
                            Text(if (state.format == ExportFormat.PNG) "PNG · 无损保存" else "JPEG · 质量 ${state.quality}", fontSize = 12.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { model.export(false) }, enabled = idle,
                            modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("保存到相册")
                        }
                        OutlinedButton(onClick = { model.export(true) }, enabled = idle,
                            modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Outlined.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("分享")
                        }
                    }
                    state.note?.let { Text(it, color = Muted, fontSize = 12.sp, lineHeight = 19.sp) }
                }
            }
            if (state.image == null) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("简单三步", color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Step("01", "选择图片", "从系统相册导入")
                    Step("02", "混淆 / 解混淆", "像素有序地变换")
                    Step("03", "保存与分享", "建议发送原文件")
                }
            }
            }
            HorizontalDivider(color = Line)
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.Info, null, Modifier.size(16.dp), tint = Muted)
                Text("混淆不等于密码加密。请保留原文件，避免裁剪、缩放或压缩后无法准确还原。", fontSize = 12.sp, color = Muted, lineHeight = 20.sp)
            }
            Text("留在你的设备，也留住你的安心。", Modifier.fillMaxWidth().padding(bottom = 24.dp),
                textAlign = TextAlign.Center, fontSize = 11.sp, color = Muted)
        }
    }
    if (repeatPanel) RepeatDialog({ direction, rounds -> model.transform(direction, rounds); repeatPanel = false }) { repeatPanel = false }
    if (settings) SettingsDialog(state, model) { settings = false }
    state.error?.let { error ->
        AlertDialog(onDismissRequest = model::clearError, title = { Text("暂时未能完成") },
            text = { Text(error) }, confirmButton = { TextButton(onClick = model::clearError) { Text("知道了") } })
    }
    if (fullscreen && state.image != null) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember(state.image) { mutableFloatStateOf(1f) }
            var offset by remember(state.image) { mutableStateOf(Offset.Zero) }
            Box(Modifier.fillMaxSize().background(Color(0xFF171A17))) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else Offset(
                            (offset.x + pan.x).coerceIn(-size.width * (scale - 1) / 2, size.width * (scale - 1) / 2),
                            (offset.y + pan.y).coerceIn(-size.height * (scale - 1) / 2, size.height * (scale - 1) / 2))
                    }
                }) {
                    Image(state.image!!.asImageBitmap(), "图片放大预览", Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                    }, contentScale = ContentScale.Fit)
                }
                IconButton(onClick = { fullscreen = false }, Modifier.align(Alignment.TopStart).padding(16.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "关闭预览", tint = Color.White)
                }
                Text("双指缩放 · 拖动查看", Modifier.align(Alignment.BottomCenter).padding(28.dp), color = Color.White, fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun Tag(icon: ImageVector, text: String) {
    Row(Modifier.background(Color(0xFFEEF1E8), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(13.dp), tint = Green)
        Text(text, fontSize = 11.sp, color = Green)
    }
}
@Composable private fun Step(number: String, title: String, caption: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(number, color = Tomato, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Text(caption, color = Muted, fontSize = 10.sp)
    }
}
@Composable private fun PictureArtwork() {
    Box(Modifier.size(132.dp, 98.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(97.dp, 77.dp).rotate(-12f).background(Color(0xFFE8D9C5), RoundedCornerShape(12.dp)))
        Canvas(Modifier.size(97.dp, 77.dp).rotate(7f).clip(RoundedCornerShape(12.dp)).background(Color.White).border(5.dp, Color.White, RoundedCornerShape(12.dp))) {
            drawRect(Color(0xFFE3E9DF))
            drawCircle(Color(0xFFE9B977), 9.dp.toPx(), Offset(size.width * .72f, size.height * .28f))
            drawPath(Path().apply { moveTo(0f, size.height); lineTo(size.width * .36f, size.height * .36f); lineTo(size.width * .72f, size.height); close() }, Green)
            drawPath(Path().apply { moveTo(size.width * .30f, size.height); lineTo(size.width * .73f, size.height * .5f); lineTo(size.width, size.height * .74f); lineTo(size.width, size.height); close() }, Color(0xFF91A385))
        }
        Icon(Icons.Outlined.Add, null, Modifier.align(Alignment.BottomEnd).background(Tomato, CircleShape).padding(8.dp).size(20.dp), tint = Color.White)
    }
}

@Composable private fun SettingsDialog(state: EditorState, model: EditorViewModel, close: () -> Unit) {
    var quality by remember(state.quality) { mutableFloatStateOf(state.quality.toFloat()) }
    var showLicense by remember { mutableStateOf(false) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = close, title = { Text("帮助与设置") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("保存格式", fontWeight = FontWeight.Bold)
            ExportFormat.entries.forEach { format ->
                Row(Modifier.fillMaxWidth().clickable { model.setFormat(format) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = state.format == format, onClick = { model.setFormat(format) })
                    Column {
                        Text(if (format == ExportFormat.PNG) "PNG · 推荐" else "JPEG · 更小体积")
                        Text(if (format == ExportFormat.PNG) "无损保存当前像素" else "有损压缩，可能产生色差", color = Muted, fontSize = 11.sp)
                    }
                }
            }
            if (state.format == ExportFormat.JPEG) {
                Text("JPEG 质量：${quality.roundToInt()}")
                Slider(value = quality, onValueChange = { quality = it }, valueRange = 80f..100f, steps = 19,
                    onValueChangeFinished = { model.setQuality(quality.roundToInt()) })
            }
            HorizontalDivider()
            Text("使用说明", fontWeight = FontWeight.Bold)
            Text("混淆几次，就需反向操作几次。外部图片的历史次数未知，App 仅记录本次操作。重置会回到本次导入时的画面。", lineHeight = 21.sp)
            Text("解混淆需要保留图片尺寸。请发送原文件；裁剪、缩放、截图和有损压缩可能破坏结果。", lineHeight = 21.sp)
            Text("本版本统一为 sRGB，透明背景合成黑色。单图预览仅保留在本次会话中；批量会保留任务引用和保存状态，清空列表不删除相册图片。批量处理只在本机计算，勾选后才会写入相册；未保存的处理结果在应用退出后需要重新处理。", lineHeight = 21.sp)
            Text("隐私说明", fontWeight = FontWeight.Bold)
            Text("无账号、无广告、未声明联网权限，应用无法联网，图片不会离开本机。只读取你选择的图片，原文件不被覆盖。批量处理时会显示进度通知，拒绝通知权限也能正常处理。分享时由你选择的应用接收图片；临时分享文件会在后续分享时清理超过 24 小时的缓存。", lineHeight = 21.sp)
            Text("l君の番茄混淆 ${BuildConfig.VERSION_NAME} · 内测版", color = Muted, fontSize = 12.sp)
            TextButton(onClick = { showLicense = true }) { Text("开源许可") }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
    if (showLicense) {
        val notice = remember { context.assets.open("THIRD_PARTY_NOTICES.txt").bufferedReader().use { it.readText() } }
        AlertDialog(onDismissRequest = { showLicense = false }, title = { Text("开源许可") },
            text = { Text(notice, Modifier.verticalScroll(rememberScrollState()), fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } })
    }
}
