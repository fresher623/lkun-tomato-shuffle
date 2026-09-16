package com.fanqie.hunxiao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps batch processing alive while the app is in the background.
 *
 * The service only hosts the notification and the foreground lifetime: the work itself belongs to
 * the application-scoped processor in [BatchHost], so the UI can come and go freely during a batch.
 */
internal class BatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Enter the foreground immediately: Android requires it shortly after a start request.
        promote(getString(R.string.batch_notification_title), null)
        if (watch == null) {
            val processor = BatchHost.processor(this)
            watch = scope.launch {
                processor.state.collectLatest { state ->
                    if (!state.busy) { stopSelf(); return@collectLatest }
                    promote(notificationText(state), state.progress)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notificationText(state: BatchState): String {
        val verb = if (state.saving) "正在保存" else "正在处理"
        val stage = state.stage
        return if (stage.isBlank()) verb else "$verb · $stage"
    }

    private fun promote(text: String, progress: Float?) {
        createChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.tomato_mark)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
        if (progress != null) builder.setProgress(1000, (progress * 1000).toInt(), false)
        else builder.setProgress(0, 0, true)
        val notification = builder.build()
        try {
            // minSdk is 29, so the dataSync service type is always available.
            ServiceCompat.startForeground(this, ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (error: Exception) {
            // A denied notification permission on Android 13+ still lets the service run; the
            // notification is simply not visible. Never let a notification problem stop the batch,
            // but do record it so a real misconfiguration is not silently swallowed.
            android.util.Log.w("BatchService", "前台通知未能显示，处理继续", error)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.batch_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = getString(R.string.batch_channel_description)
            setShowBadge(false)
        })
    }

    override fun onDestroy() {
        watch = null
        scope.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "batch-progress"
        private const val ID = 1001

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, BatchService::class.java)) }
        }
        fun stop(context: Context) {
            // Lint's ImplicitSamInstance warning is a false positive here: this resolves to
            // Context.stopService(Intent), which matches on the Intent's component.
            @Suppress("ImplicitSamInstance")
            runCatching { context.stopService(Intent(context, BatchService::class.java)) }
        }
    }
}
