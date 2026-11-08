package com.clipsync.app.ui

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.clipsync.app.data.Repository
import com.clipsync.app.files.FilesSettings
import com.clipsync.app.service.ReceiverControl
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repository: Repository, onBack: () -> Unit, onUnpaired: () -> Unit) {
    val scope = rememberCoroutineScope()
    val pairedPc by repository.pairedPc.collectAsState(initial = null)
    var diagnosis by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    var autoCapture by remember { mutableStateOf(repository.autoCapture) }
    val context = LocalContext.current
    val receiverEnabled by ReceiverControl.enabled(context).collectAsState()
    var stopOnClose by remember { mutableStateOf(ReceiverControl.stopOnClose(context)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Назад") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text("Сопряжённый ПК", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            val pc = pairedPc
            if (pc == null) {
                Text("Нет подключённого ПК")
            } else {
                Text(pc.name)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { scope.launch { repository.unpair(); onUnpaired() } }) {
                    Text("Отключить")
                }

                Spacer(Modifier.height(16.dp))
                OutlinedButton(
                    enabled = !checking,
                    onClick = {
                        scope.launch {
                            checking = true
                            diagnosis = repository.diagnose()
                            checking = false
                        }
                    },
                ) {
                    Text(if (checking) "Проверяю..." else "Проверить связь с ПК")
                }
                diagnosis?.let { report ->
                    Spacer(Modifier.height(8.dp))
                    Text(report, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (pc != null) {
                Spacer(Modifier.height(24.dp))
                Text("Приём с ПК", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Принимать буфер с ПК")
                        Text(
                            if (receiverEnabled) {
                                "Включено: ClipSync слушает ПК в фоне, пока видно уведомление «ClipSync активен». " +
                                    "Выключить можно здесь или кнопкой «Выключить» в этом уведомлении."
                            } else {
                                "Выключено: ПК знает об этом и ничего не отправляет. Отправка с телефона на ПК " +
                                    "работает по-прежнему."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = receiverEnabled,
                        onCheckedChange = { if (it) ReceiverControl.turnOn(context) else ReceiverControl.turnOff(context) },
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Останавливать при закрытии приложения")
                        Text(
                            "Если включено, приём выключается, когда вы смахиваете ClipSync из списка недавних " +
                                "приложений, и снова включается при его открытии. Если выключено (по умолчанию), " +
                                "приём продолжает работать в фоне, чтобы ПК мог присылать записи.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = stopOnClose,
                        onCheckedChange = {
                            stopOnClose = it
                            ReceiverControl.setStopOnClose(context, it)
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                var acceptFiles by remember { mutableStateOf(FilesSettings.acceptIncoming(context)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Принимать файлы с ПК")
                        Text(
                            "Файлы, которые вы отправляете с компьютера (перетаскиванием в окно ClipSync), " +
                                "сохраняются в «Загрузки/ClipSync». Выключите, если с ПК нужен только буфер обмена. " +
                                "Программы и установочные файлы ClipSync никогда не запускает сам.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(
                        checked = acceptFiles,
                        onCheckedChange = {
                            acceptFiles = it
                            FilesSettings.setAcceptIncoming(context, it)
                        },
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
            Text("История буфера телефона", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Запоминать буфер при открытии")
                    Text(
                        "Android не даёт приложениям следить за буфером в фоне (только то, которое сейчас " +
                            "на экране), поэтому ClipSync добавляет в историю то, что скопировано на телефоне, " +
                            "в момент, когда вы его открываете. На Android 12+ система при этом может показать " +
                            "«ClipSync вставил из буфера обмена». Выделенный текст можно отправить сразу, без " +
                            "копирования: «Отправить на ПК» в меню выделения.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = autoCapture,
                    onCheckedChange = {
                        autoCapture = it
                        repository.autoCapture = it
                    },
                )
            }

            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { scope.launch { repository.clearUnpinned() } }) {
                Text("Очистить неотмеченную историю")
            }
        }
    }
}
