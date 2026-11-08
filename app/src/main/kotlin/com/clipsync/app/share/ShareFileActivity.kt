package com.clipsync.app.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.files.Transfers
import com.clipsync.app.network.NetworkErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ClipSync"

/**
 * "Файл на ПК" in Android's share sheet: sends the files themselves (not their pictures or text, which
 * [ShareReceiverActivity] makes clipboard entries of) to the paired PC.
 *
 * This activity is exported - any app can send it an intent - so what arrives is untrusted: only content://
 * addresses are opened (never file://, which a hostile app could point at this app's private files), and
 * each file is copied into the app's own cache first. That copy is also what makes a long transfer possible:
 * the access that a share gives lasts only as long as this screen, and the transfer, with its retries,
 * outlives it. The sending itself happens in `Transfers` / `TransferService`, so this screen goes away at once.
 */
class ShareFileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            // The sheet offers this target for every share (the manifest cannot say "only if there is a file").
            // Text is not a file: say where it should go instead of leaving the person guessing.
            toast(
                if (intent?.hasExtra(Intent.EXTRA_TEXT) == true) "Это текст, а не файл. Для текста выберите «Отправить на ПК»."
                else "Нечего отправлять",
            )
            finish()
            return
        }

        lifecycleScope.launch {
            try {
                val paired = withContext(Dispatchers.IO) { AppDatabase.get(applicationContext).pairedPcDao().getPc() }
                if (paired == null) {
                    toast("Сначала сопрягите телефон с ПК")
                    return@launch
                }
                toast(if (uris.size == 1) "Подготовка файла…" else "Подготовка файлов: ${uris.size}…")
                val prepared = withContext(Dispatchers.IO) { Transfers.prepare(applicationContext, uris, stage = true) }
                if (prepared.sources.isNotEmpty()) {
                    Transfers.manager.enqueue(prepared.sources)
                    toast("Отправка на ПК началась. Ход виден в уведомлении и на экране «Файлы».")
                }
                if (prepared.problems.isNotEmpty()) toast("Пропущено: " + prepared.problems.joinToString("; "))
            } catch (e: Exception) {
                Log.w(TAG, "file share failed", e)
                toast("Не удалось отправить: ${NetworkErrors.explain(e)}")
            } finally {
                finish()
            }
        }
    }

    /** Everything the other app offered: the stream extras of a share, and the clip data some apps use instead. */
    private fun extractUris(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val found = LinkedHashSet<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { found.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { found.addAll(it) }
        }
        val clip = intent.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { found.add(it) }
        }
        return found.toList()
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }
}
