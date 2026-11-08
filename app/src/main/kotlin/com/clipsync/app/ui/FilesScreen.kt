package com.clipsync.app.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipsync.app.files.Direction
import com.clipsync.app.files.FileNames
import com.clipsync.app.files.FilesSettings
import com.clipsync.app.files.PublicDownloads
import com.clipsync.app.files.TState
import com.clipsync.app.files.TransferView
import com.clipsync.app.files.Transfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Файлы" screen: send files to the PC, and see what was sent and received. Files that come from the
 * PC arrive by themselves (the PC's window has a drop zone) and are listed here too; the list is the
 * same one the notifications and the sending queue use (`Transfers`).
 */
@Composable
fun FilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val transfers by Transfers.registry.views.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var preparing by remember { mutableStateOf(false) }
    val acceptIncoming = remember { FilesSettings.acceptIncoming(context) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            preparing = true
            val prepared = withContext(Dispatchers.IO) { Transfers.prepare(context, uris, stage = false) }
            preparing = false
            if (prepared.sources.isNotEmpty()) Transfers.manager.enqueue(prepared.sources)
            if (prepared.problems.isNotEmpty()) snackbar.showSnackbar("Пропущено: " + prepared.problems.joinToString("; "))
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Файлы") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
                actions = { TextButton(onClick = { Transfers.manager.clear(null) }) { Text("Очистить") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    enabled = !preparing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (preparing) "Подготовка…" else "Отправить файлы на ПК")
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Файлы идут напрямую по Wi-Fi между вашими устройствами, в зашифрованном виде. " +
                        "С компьютера их можно отправить, перетащив в окно ClipSync: они появятся в «${PublicDownloads.whereToLook()}». " +
                        "Отправить можно и из любого приложения через «Поделиться» → «Файл на ПК».",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!acceptIncoming) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Приём файлов с ПК выключен (Настройки → «Принимать файлы с ПК»).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (transfers.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
                    Text("Здесь появятся отправленные и полученные файлы.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    items(transfers, key = { it.id }) { view ->
                        TransferRow(
                            view = view,
                            onOpen = { message -> scope.launch { snackbar.showSnackbar(message) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TransferRow(view: TransferView, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val running = view.state == TState.ACTIVE || view.state == TState.QUEUED
    val incoming = view.direction == Direction.IN

    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(if (incoming) "↓" else "↑", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(view.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(metaLine(view), style = MaterialTheme.typography.bodySmall)
                if (view.state == TState.ACTIVE) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { fraction(view) }, modifier = Modifier.fillMaxWidth())
                }
                val note = view.error
                if (!note.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (view.state == TState.FAILED || view.state == TState.CANCELLED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (running) {
                        TextButton(onClick = { cancel(view) }) { Text("Отменить") }
                    }
                    if (view.canRetry && !running) {
                        TextButton(onClick = {
                            Transfers.manager.retry(view.id)?.let { onOpen(it) }
                        }) { Text("Повторить") }
                    }
                    if (incoming && view.state == TState.COMPLETE) {
                        if (!FileNames.isRisky(view.name) && view.location?.startsWith("content://") == true) {
                            TextButton(onClick = { openReceived(context, view)?.let { onOpen(it) } }) { Text("Открыть") }
                        }
                        TextButton(onClick = { openDownloads(context)?.let { onOpen(it) } }) { Text("В Загрузках") }
                    }
                    if (!running) {
                        TextButton(onClick = { Transfers.manager.clear(view.id) }) { Text("Убрать") }
                    }
                }
            }
        }
    }
}

/** "Отменить": an outgoing transfer is stopped by the queue, an incoming one by the receiver. */
private fun cancel(view: TransferView) {
    if (view.direction == Direction.OUT) Transfers.manager.cancel(view.id) else Transfers.receiver.cancelIncoming(view.id)
}

private fun fraction(view: TransferView): Float =
    if (view.size <= 0) 0f else (view.transferred.toDouble() / view.size).toFloat().coerceIn(0f, 1f)

private fun metaLine(view: TransferView): String {
    val who = if (view.direction == Direction.IN) "с ПК" else "на ПК"
    val size = formatBytes(view.size)
    return when (view.state) {
        TState.QUEUED -> "$size · $who · в очереди"
        TState.ACTIVE -> buildString {
            append("${formatBytes(view.transferred)} из $size · ${(fraction(view) * 100).toInt()}%")
            if (view.rateBps > 0) {
                append(" · ${formatBytes(view.rateBps)}/с")
                val left = ((view.size - view.transferred) / view.rateBps).coerceAtLeast(0)
                append(" · осталось ${formatDuration(left)}")
            }
            append(" · $who")
        }
        TState.COMPLETE -> "$size · $who · ${view.finishedAt?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) } ?: ""}".trimEnd(' ', '·')
        TState.CANCELLED -> "$size · $who · отменено"
        TState.FAILED -> "$size · $who · не удалось"
    }
}

/** 1536 -> "1,5 КБ"; the same style as the PC window. */
internal fun formatBytes(bytes: Long): String {
    if (bytes < 0) return ""
    val units = listOf("Б", "КБ", "МБ", "ГБ")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i += 1
    }
    val digits = if (i == 0) 0 else if (value < 10) 1 else 0
    return String.format(Locale("ru"), "%.${digits}f %s", value, units[i])
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "${maxOf(1, seconds)} с"
    seconds < 3600 -> "${seconds / 60} мин ${seconds % 60} с"
    else -> "${seconds / 3600} ч ${(seconds % 3600) / 60} мин"
}

/** Opens a received file in the app that handles its type. Programs and installers are never opened from here. Returns a sentence when it cannot. */
private fun openReceived(context: Context, view: TransferView): String? {
    val location = view.location ?: return "Файл не найден"
    if (FileNames.isRisky(view.name)) {
        return "ClipSync не открывает программы и установочные файлы, пришедшие по сети. Откройте «Загрузки» и решите сами."
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(location), FileNames.mimeType(view.name))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        "На телефоне нет приложения, которое открывает такие файлы"
    } catch (_: SecurityException) {
        "Файл больше недоступен: его переместили или удалили"
    }
}

private fun openDownloads(context: Context): String? =
    try {
        context.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: Exception) {
        "Откройте приложение «Файлы» и папку ${PublicDownloads.whereToLook()}"
    }
