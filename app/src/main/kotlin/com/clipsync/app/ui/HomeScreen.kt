package com.clipsync.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.clipsync.app.data.CaptureResult
import com.clipsync.app.data.EntryMeta
import com.clipsync.app.data.Repository
import com.clipsync.app.network.NetworkErrors
import com.clipsync.app.service.ReceiverControl
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TAB_TITLES = listOf("Все", "Телефон", "С ПК")

private val timeFormat = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())

/** "Where it came from · when · pinned" - shared by the list rows and the preview screen. */
internal fun entrySubtitle(entry: EntryMeta): String {
    val origin = when (entry.direction) {
        "incoming" -> "С ПК"
        "outgoing" -> "Отправлено на ПК"
        else -> "Буфер телефона"
    }
    return buildString {
        append(origin)
        append(" · ")
        append(timeFormat.format(Date(entry.createdAt)))
        if (entry.pinned) append(" · закреплено")
    }
}

private fun emptyText(tab: Int): String = when (tab) {
    1 -> "Здесь появится то, что вы скопировали на телефоне или отправили на ПК.\n\n" +
        "Скопируйте что-нибудь и откройте ClipSync: запись добавится сама. " +
        "Выделенный в любом приложении текст можно сразу отправить пунктом «Отправить на ПК» в его меню."
    2 -> "С ПК пока ничего не пришло."
    else -> "История пуста.\n\nСкопируйте что-нибудь на телефоне или на ПК."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(repository: Repository, onSettings: () -> Unit, onOpenEntry: (String) -> Unit, onFiles: () -> Unit) {
    val entries by repository.observeEntries().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    // Follows the switch whoever flips it - here, in Settings, or the "Выключить" button of the notification.
    val receiverOn by ReceiverControl.enabled(context).collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by remember { mutableStateOf(0) }
    var sendingId by remember { mutableStateOf<String?>(null) }
    var sendingCurrent by remember { mutableStateOf(false) }

    val visible = remember(entries, tab) {
        when (tab) {
            1 -> entries.filter { it.direction != "incoming" } // what originated on this phone
            2 -> entries.filter { it.direction == "incoming" }
            else -> entries
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("ClipSync") },
                actions = {
                    Text(
                        if (receiverOn) "Приём" else "Приём выкл.",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Switch(
                        checked = receiverOn,
                        onCheckedChange = { if (it) ReceiverControl.turnOn(context) else ReceiverControl.turnOff(context) },
                    )
                    TextButton(onClick = onFiles) { Text("Файлы") }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    // "Send what is on the clipboard right now": read it into the history first (so it
                    // is not lost if the PC is unreachable), then send that very entry.
                    if (!sendingCurrent) {
                        scope.launch {
                            sendingCurrent = true
                            val message = try {
                                when (val captured = repository.captureClipboard()) {
                                    is CaptureResult.NoClip -> "Буфер обмена пуст"
                                    is CaptureResult.Added -> {
                                        repository.sendEntry(captured.id)
                                        "Отправлено на ПК"
                                    }
                                    is CaptureResult.Existing -> {
                                        repository.sendEntry(captured.id)
                                        "Отправлено на ПК"
                                    }
                                }
                            } catch (e: Exception) {
                                "Не отправлено: ${NetworkErrors.explain(e)}"
                            }
                            sendingCurrent = false
                            snackbar.showSnackbar(message)
                        }
                    }
                },
                icon = { Icon(Icons.Filled.Send, contentDescription = null) },
                text = { Text(if (sendingCurrent) "Отправка…" else "Отправить буфер") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                TAB_TITLES.forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(title) })
                }
            }

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(emptyText(tab), textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visible, key = { it.id }) { entry ->
                        EntryRow(
                            repository = repository,
                            entry = entry,
                            sending = sendingId == entry.id,
                            onOpen = { onOpenEntry(entry.id) },
                            onCopy = {
                                scope.launch {
                                    val text = repository.loadText(entry.id)
                                    if (text == null) {
                                        snackbar.showSnackbar("Не удалось прочитать запись")
                                    } else {
                                        clipboardManager.setText(AnnotatedString(text))
                                        snackbar.showSnackbar("Скопировано")
                                    }
                                }
                            },
                            onSend = {
                                scope.launch {
                                    sendingId = entry.id
                                    val failure = try {
                                        repository.sendEntry(entry.id)
                                        null
                                    } catch (e: Exception) {
                                        e
                                    }
                                    sendingId = null
                                    snackbar.showSnackbar(
                                        if (failure == null) "Отправлено на ПК" else "Не отправлено: ${NetworkErrors.explain(failure)}",
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    repository: Repository,
    entry: EntryMeta,
    sending: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onSend: () -> Unit,
) {
    val isImage = entry.entryType == "image"
    val preview by produceState(initialValue = "", key1 = entry.id) {
        value = if (isImage) "" else repository.loadPreview(entry.id)
    }

    ListItem(
        modifier = Modifier.clickable(onClick = onOpen),
        leadingContent = if (isImage) {
            { Thumbnail(repository, entry.id) }
        } else {
            null
        },
        headlineContent = {
            if (isImage) {
                Text("Изображение", maxLines = 1)
            } else {
                Text(preview.ifEmpty { "(пусто)" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        },
        supportingContent = { Text(entrySubtitle(entry), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            Row {
                if (!isImage) {
                    IconButton(onClick = onCopy) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Копировать")
                    }
                }
                // Only what originated on this phone: sending back what the PC just sent makes no sense.
                if (entry.direction != "incoming") {
                    IconButton(onClick = onSend, enabled = !sending) {
                        if (sending) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = "Отправить на ПК")
                        }
                    }
                }
            }
        },
    )
}

/** Square thumbnail of an image entry. Decoded off the main thread, once per entry (see Repository.loadThumbnail). */
@Composable
private fun Thumbnail(repository: Repository, id: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = id) {
        value = repository.loadThumbnail(id)?.asImageBitmap()
    }
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Изображение",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
