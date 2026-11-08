package com.clipsync.app.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.clipsync.app.ui.MainActivity

/**
 * The notifications of file transfer: progress while a file is sent or received (so a transfer is visible
 * with the screen off), and a line when a file has arrived or has failed. Everything is best effort: when
 * the notification permission is refused, `notify` simply shows nothing and the transfers go on.
 */
object TransferNotifications {
    private const val CHANNEL_PROGRESS = "clipsync_files"
    private const val CHANNEL_RESULT = "clipsync_files_result"

    /** The foreground notification of [TransferService] (files going to the PC). */
    const val SENDING_ID = 4101
    private const val RECEIVING_ID = 4102
    private const val RESULT_BASE = 4200

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_PROGRESS) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Передача файлов", NotificationManager.IMPORTANCE_LOW))
        }
        if (manager.getNotificationChannel(CHANNEL_RESULT) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, "Полученные файлы", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun openFilesScreen(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, MainActivity.ROUTE_FILES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun percent(view: TransferView): Int =
        if (view.size <= 0) 0 else ((view.transferred * 100) / view.size).toInt().coerceIn(0, 100)

    /** The notification of [TransferService]: what is being sent to the PC now. */
    fun sendingNotification(context: Context, views: List<TransferView>): Notification {
        val active = views.filter { it.direction == Direction.OUT && (it.state == TState.ACTIVE || it.state == TState.QUEUED) }
        val current = active.firstOrNull { it.state == TState.ACTIVE } ?: active.firstOrNull()
        val title = if (active.size > 1) "Отправка на ПК: ${active.size} файла(ов)" else "Отправка файла на ПК"
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setContentTitle(title)
            .setContentText(current?.let { "${it.name} — ${percent(it)}%" } ?: "Подготовка…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openFilesScreen(context, 11))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (current != null && current.state == TState.ACTIVE) builder.setProgress(100, percent(current), false)
        return builder.build()
    }

    /** Keeps the progress notifications in step with the list. Called a few times a second at most. */
    fun sync(context: Context, views: List<TransferView>) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val receiving = views.firstOrNull { it.direction == Direction.IN && it.state == TState.ACTIVE }
        if (receiving == null) {
            manager.cancel(RECEIVING_ID)
        } else {
            val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setContentTitle("Получение файла с ПК")
                .setContentText("${receiving.name} — ${percent(receiving)}%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(openFilesScreen(context, 12))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(100, percent(receiving), false)
            manager.notify(RECEIVING_ID, builder.build())
        }
        if (TransferService.isRunning) manager.notify(SENDING_ID, sendingNotification(context, views))
    }

    fun received(context: Context, name: String, location: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setContentTitle("Получен файл с ПК")
            .setContentText("$name — ${PublicDownloads.whereToLook()}")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openFilesScreen(context, 13))
            .setAutoCancel(true)
            .build()
        manager.notify(RESULT_BASE + (location.hashCode() and 0xFFF), notification)
    }

    fun failed(context: Context, name: String, reason: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setContentTitle("Файл не получен")
            .setContentText("$name: $reason")
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentIntent(openFilesScreen(context, 14))
            .setAutoCancel(true)
            .build()
        manager.notify(RESULT_BASE + (name.hashCode() and 0xFFF), notification)
    }
}
