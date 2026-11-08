package com.clipsync.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.clipsync.app.data.ImageRejected
import com.clipsync.app.data.ImageUtils
import com.clipsync.app.data.Repository
import com.clipsync.app.network.NetworkErrors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ClipSync"

/**
 * Target for Android's native "Share" action from any app, and for "Отправить на ПК" in the
 * text-selection menu (ACTION_PROCESS_TEXT). This - not background clipboard watching, which the
 * OS does not allow past Android 10 - is the main way of sending content TO the PC.
 *
 * This activity is exported (any app can send it an intent), so what it receives is treated as
 * untrusted: only content:// URIs are opened (never file:// - a hostile app could point that at
 * this app's own private files), reads are size-capped, and decoding happens off the main thread
 * (all of that lives in [ImageUtils]).
 */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = Repository(applicationContext)

        lifecycleScope.launch {
            try {
                when {
                    // Text selected in any app -> "..." menu -> "Отправить на ПК". Checked first: this
                    // intent has type text/plain too, but the text is in a different extra.
                    intent?.action == Intent.ACTION_PROCESS_TEXT -> handleProcessText(repository)
                    intent?.type == "text/plain" -> handleText(repository)
                    intent?.type?.startsWith("image/") == true -> handleImage(repository)
                    else -> toast("Этот тип содержимого не поддерживается")
                }
            } catch (e: Exception) {
                Log.w(TAG, "share failed", e)
                toast("Не удалось отправить: ${NetworkErrors.explain(e)}")
            } finally {
                finish()
            }
        }
    }

    private suspend fun handleText(repository: Repository) {
        sendText(repository, intent.getStringExtra(Intent.EXTRA_TEXT))
    }

    private suspend fun handleProcessText(repository: Repository) {
        sendText(repository, intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
    }

    private suspend fun sendText(repository: Repository, text: String?) {
        if (text.isNullOrBlank()) {
            toast("Пустой текст")
            return
        }
        repository.sendToPc("text", text.toByteArray(Charsets.UTF_8))
        toast("Отправлено на ПК")
    }

    private suspend fun handleImage(repository: Repository) {
        val uri = extraStreamUri()
        if (uri == null) {
            toast("Не удалось прочитать изображение")
            return
        }
        val prepared = try {
            withContext(Dispatchers.IO) { ImageUtils.prepareFromUri(contentResolver, uri) }
        } catch (e: ImageRejected) {
            toast(e.message ?: "Не удалось прочитать изображение")
            return
        }
        repository.sendToPc("image", prepared.png, prepared.width, prepared.height)
        toast("Отправлено на ПК")
    }

    private fun extraStreamUri(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
