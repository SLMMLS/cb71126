package com.clipsync.app.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import android.util.Base64
import android.util.LruCache
import android.util.Log
import androidx.room.withTransaction
import com.clipsync.app.crypto.CryptoUtils
import com.clipsync.app.crypto.Identity
import com.clipsync.app.network.HelloSender
import com.clipsync.app.network.NetworkErrors
import com.clipsync.app.network.PairingQrPayload
import com.clipsync.app.network.PushPayload
import com.clipsync.app.network.SyncClient
import com.clipsync.app.service.ReceiverControl
import com.clipsync.app.service.ReceiverService
import com.clipsync.app.service.SYNC_PORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** What reading the phone's own clipboard found. */
sealed interface CaptureResult {
    /** Empty, not readable right now (Android only lets the focused app read it), or not something ClipSync stores. */
    data object NoClip : CaptureResult

    /** A new entry was added to the history. */
    data class Added(val id: String) : CaptureResult

    /** This exact content is already in the history (or was already captured): nothing new was added. */
    data class Existing(val id: String) : CaptureResult
}

/** The content-hash index (see [ClipHashIndex]) persisted in the app's SharedPreferences. */
private class PrefsStore(private val prefs: SharedPreferences) : StringStore {
    override fun read(): String? = prefs.getString("hash_index", null)

    override fun write(value: String) {
        prefs.edit().putString("hash_index", value).apply()
    }
}

class Repository(private val context: Context) {
    private val appContext: Context = context.applicationContext
    private val db = AppDatabase.get(context)
    private val identity: Identity.KeyPairRaw by lazy { Identity.loadOrCreate(context) }
    private val localKey: ByteArray by lazy { Identity.loadOrCreateLocalStorageKey(context) }
    private val deviceId: String by lazy { getOrCreateDeviceId(context) }

    private val prefs: SharedPreferences
        get() = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val pairedPc: Flow<PairedPc?> = db.pairedPcDao().observePc()

    /**
     * Whether the phone's clipboard is added to the history each time the app is opened.
     * Android does not let an app watch the clipboard in the background (since Android 10 only the
     * app that has focus may read it), so "the clipboard history of this phone" can only be built
     * from what is on the clipboard whenever ClipSync is in front.
     */
    var autoCapture: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CAPTURE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CAPTURE, value).apply()
        }

    // ------------------------------------------------------------------ list

    /** The history, newest first, pinned on top - *without* the content of any entry (see [EntryMeta]). */
    fun observeEntries(): Flow<List<EntryMeta>> = db.clipDao().observeMeta(MAX_LIST)

    fun observeEntry(id: String): Flow<EntryMeta?> = db.clipDao().observeMetaById(id)

    // --------------------------------------------------------------- content

    /**
     * Encrypted content of an entry. Read in slices: one query for a BLOB larger than
     * Android's ~2 MB CursorWindow throws, and a screenshot easily is larger than that.
     */
    private suspend fun readEncrypted(id: String): ByteArray? {
        val dao = db.clipDao()
        val total = dao.contentLength(id) ?: return null
        if (total == 0) return ByteArray(0)
        val out = ByteArray(total)
        var offset = 0
        while (offset < total) {
            val slice = dao.contentSlice(id, offset + 1, minOf(SLICE_BYTES, total - offset)) ?: return null
            if (slice.isEmpty()) return null
            System.arraycopy(slice, 0, out, offset, slice.size)
            offset += slice.size
        }
        return out
    }

    /** Decrypted content: UTF-8 bytes of a text, or PNG bytes of an image. Null if missing or unreadable. */
    suspend fun loadContent(id: String): ByteArray? = withContext(Dispatchers.IO) {
        val encrypted = readEncrypted(id) ?: return@withContext null
        try {
            CryptoUtils.decrypt(localKey, encrypted)
        } catch (e: Exception) {
            // e.g. a row left over from before the app's data was cleared: drop it from view, don't crash.
            Log.w(TAG, "could not decrypt entry $id", e)
            null
        }
    }

    suspend fun loadText(id: String): String? = loadContent(id)?.let { String(it, Charsets.UTF_8) }

    /** The short text shown in a list row; cached, because a row is composed again every time it scrolls into view. */
    suspend fun loadPreview(id: String): String {
        previewCache.get(id)?.let { return it }
        val text = loadText(id) ?: return ""
        val preview = Previews.make(text)
        previewCache.put(id, preview)
        return preview
    }

    /** Small bitmap for a list row. Decoded at most once per entry per process (see [thumbnailCache]). */
    suspend fun loadThumbnail(id: String): Bitmap? {
        thumbnailCache.get(id)?.let { return it }
        val bytes = loadContent(id) ?: return null
        val bitmap = withContext(Dispatchers.Default) { ImageUtils.decodeToCover(bytes, THUMB_TARGET) } ?: return null
        thumbnailCache.put(id, bitmap)
        return bitmap
    }

    /** Bitmap for the preview screen, subsampled so that a 48 MP photo cannot exhaust the heap. */
    suspend fun loadBitmap(id: String, maxSide: Int = PREVIEW_MAX_SIDE): Bitmap? {
        val bytes = loadContent(id) ?: return null
        return withContext(Dispatchers.Default) { ImageUtils.decodeToFit(bytes, maxSide) }
    }

    // ------------------------------------------------------------- mutations

    suspend fun togglePin(id: String, pinned: Boolean) {
        withContext(Dispatchers.IO) { db.clipDao().setPinned(id, pinned) }
    }

    suspend fun delete(id: String) {
        withContext(Dispatchers.IO) { db.clipDao().delete(id) }
        thumbnailCache.remove(id)
        previewCache.remove(id)
    }

    suspend fun clearUnpinned() {
        withContext(Dispatchers.IO) { db.clipDao().clearUnpinned() }
        thumbnailCache.evictAll()
        previewCache.evictAll()
    }

    /**
     * Adds an entry, or - if the very same content is already in the history - just moves that
     * entry to the top. Returns (entry id, true if a new row was created).
     */
    private suspend fun addOrBump(entryType: String, content: ByteArray, direction: String): Pair<String, Boolean> =
        writeLock.withLock {
            val dao = db.clipDao()
            val hash = ClipHashIndex.hashOf(entryType, content)
            val now = System.currentTimeMillis()

            val existing = hashIndex().find(hash)
            if (existing != null && dao.getMeta(existing) != null) {
                dao.touch(existing, now)
                return@withLock existing to false
            }

            val id = UUID.randomUUID().toString()
            dao.insert(
                ClipEntryRow(
                    id = id,
                    entryType = entryType,
                    encryptedContent = CryptoUtils.encrypt(localKey, content),
                    createdAt = now,
                    pinned = false,
                    direction = direction,
                ),
            )
            hashIndex().put(hash, id)
            dao.pruneUnpinned(MAX_UNPINNED)
            id to true
        }

    /** Something the PC pushed. Returns true if it is new (so the caller can notify), false if it was already here. */
    suspend fun storeIncoming(entryType: String, content: ByteArray): Boolean = withContext(Dispatchers.IO) {
        addOrBump(entryType, content, "incoming").second
    }

    // -------------------------------------------------------- phone clipboard

    private class ClipContent(val type: String, val bytes: ByteArray)

    private fun readClip(clip: ClipData, description: ClipDescription): ClipContent? {
        if (clip.itemCount == 0) return null
        val item = clip.getItemAt(0)
        val uri = item.uri

        val hasImageMime = (0 until description.mimeTypeCount).any { description.getMimeType(it).startsWith("image/") }
        if (hasImageMime && uri != null) {
            return try {
                ClipContent("image", ImageUtils.prepareFromUri(appContext.contentResolver, uri).png)
            } catch (e: ImageRejected) {
                Log.w(TAG, "clipboard image skipped: ${e.message}")
                null
            } catch (e: Exception) { // SecurityException, FileNotFoundException, ...
                Log.w(TAG, "clipboard image could not be read", e)
                null
            }
        }

        // A clip that is only a content:// URI to something that is not an image (a file, a contact...) has
        // no text worth storing: coerceToText() would just hand back the URI string.
        val text = item.text?.toString() ?: (if (uri == null) item.coerceToText(appContext)?.toString() else null)
        if (text.isNullOrBlank()) return null
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_TEXT_BYTES) {
            Log.w(TAG, "clipboard text skipped: ${bytes.size} bytes")
            return null
        }
        return ClipContent("text", bytes)
    }

    /**
     * Reads the phone's clipboard into the history. Works only while ClipSync's window has focus
     * (Android 10+); call it from the activity's onWindowFocusChanged.
     *
     * The clipboard's own timestamp is checked first: if it is the same as last time, the clip is
     * not read again - which also spares the "ClipSync pasted from your clipboard" notice that
     * Android 12+ shows on every read.
     */
    suspend fun captureClipboard(): CaptureResult = withContext(Dispatchers.IO) {
        val manager = appContext.getSystemService(ClipboardManager::class.java)
            ?: return@withContext CaptureResult.NoClip
        val description = manager.primaryClipDescription ?: return@withContext CaptureResult.NoClip

        val stamp = description.timestamp
        val lastId = prefs.getString(KEY_LAST_CLIP_ID, null)
        if (stamp != 0L && stamp == prefs.getLong(KEY_LAST_CLIP_STAMP, 0L) && lastId != null &&
            db.clipDao().getMeta(lastId) != null
        ) {
            return@withContext CaptureResult.Existing(lastId)
        }

        val clip = manager.primaryClip ?: return@withContext CaptureResult.NoClip
        val content = readClip(clip, description) ?: return@withContext CaptureResult.NoClip
        val (id, isNew) = addOrBump(content.type, content.bytes, "local")
        prefs.edit().putLong(KEY_LAST_CLIP_STAMP, stamp).putString(KEY_LAST_CLIP_ID, id).apply()
        if (isNew) CaptureResult.Added(id) else CaptureResult.Existing(id)
    }

    // ----------------------------------------------------------------- sending

    private fun pushContent(pc: PairedPc, entryType: String, content: ByteArray, width: Int?, height: Int?) {
        val isImage = entryType == "image"
        val dims = if (isImage && (width == null || height == null)) ImageUtils.pngDimensions(content) else null
        val contentB64 = if (isImage) Base64.encodeToString(content, Base64.NO_WRAP) else String(content, Charsets.UTF_8)
        val payload = PushPayload(
            entryType,
            contentB64,
            width ?: dims?.first,
            height ?: dims?.second,
            System.currentTimeMillis(),
        )
        SyncClient.push(pc, identity, deviceId, payload)
    }

    /** Sends one specific entry of the history to the PC ("pick the one you need"). */
    suspend fun sendEntry(id: String) {
        withContext(Dispatchers.IO) {
            val pc = db.pairedPcDao().getPc() ?: error("Нет сопряжённого ПК")
            val meta = db.clipDao().getMeta(id) ?: error("Запись не найдена")
            val content = loadContent(id) ?: error("Не удалось прочитать запись")
            pushContent(pc, meta.entryType, content, null, null)
            if (meta.direction == "local") db.clipDao().setDirection(id, "outgoing")
        }
    }

    /** Sends something that is not (yet) in the history - the share sheet, "process text" - and records it. */
    suspend fun sendToPc(entryType: String, content: ByteArray, imageWidth: Int? = null, imageHeight: Int? = null) {
        withContext(Dispatchers.IO) {
            val pc = db.pairedPcDao().getPc() ?: error("Нет сопряжённого ПК")
            pushContent(pc, entryType, content, imageWidth, imageHeight)

            val (id, isNew) = addOrBump(entryType, content, "outgoing")
            if (!isNew && db.clipDao().getMeta(id)?.direction == "local") {
                db.clipDao().setDirection(id, "outgoing")
            }
        }
    }

    // ------------------------------------------------------------------ pairing

    suspend fun unpair() = withContext(Dispatchers.IO) {
        db.pairedPcDao().clear()
        // Files on their way to or from that PC stop with the pairing.
        com.clipsync.app.files.Transfers.onUnpaired(appContext)
    }

    suspend fun pair(qrJson: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = PairingQrPayload.fromJson(qrJson)
            val result = SyncClient.pair(payload, identity, deviceId, deviceName())
            val pc = PairedPc(
                deviceId = result.response.deviceId,
                name = result.response.deviceName,
                publicKey = Base64.decode(result.response.publicKey, Base64.NO_WRAP),
                address = result.address, // the address that actually answered, not just the QR's first guess
                certHash = Base64.decode(payload.certHash, Base64.NO_WRAP),
            )
            // This app pairs with exactly one PC. Without the clear(), re-pairing with a PC whose
            // device_id changed (reinstalled / data folder wiped) would leave the old row behind,
            // and "SELECT ... LIMIT 1" could keep returning the stale one.
            db.withTransaction {
                db.pairedPcDao().clear()
                db.pairedPcDao().upsert(pc)
            }
            // Pairing is an explicit "make it work": do not stay silenced by an old position of
            // the "Приём" switch. (The app then starts the receiver because the paired PC changed.)
            ReceiverControl.setEnabled(context, true)
        }.onFailure { Log.w(TAG, "pairing failed", it) }
    }

    /**
     * One screen's worth of facts for "why doesn't it connect": can this phone
     * reach the PC (TCP + TLS + pinned certificate), is this phone's own
     * receiver listening, and which networks is the phone on (a VPN here is a
     * very common culprit).
     */
    suspend fun diagnose(): String = withContext(Dispatchers.IO) {
        val pc = db.pairedPcDao().getPc() ?: return@withContext "ПК не сопряжён."
        val lines = ArrayList<String>()
        lines.add("ПК: ${pc.name} (${pc.address})")

        var pcReachable = false
        lines.add(
            try {
                val code = SyncClient.ping(pc)
                pcReachable = true
                "Телефон → ПК: работает (адрес доступен, сертификат совпал, HTTP $code)"
            } catch (e: Exception) {
                Log.w(TAG, "diagnose: ping failed", e)
                "Телефон → ПК: НЕ работает.\n${NetworkErrors.explain(e)}"
            },
        )
        // "Я в сети": the message the receiver sends on start and on network changes, sent now so
        // that the result below is fresh and shows whether this PC understands it.
        if (pcReachable) HelloSender.send(context, "manual", receiver = ReceiverService.isRunning, quick = true)

        val receiverState = when {
            !ReceiverControl.isEnabled(context) ->
                "приём выключен вами (переключатель «Приём»). ПК об этом знает и ничего не отправляет"
            ReceiverService.isRunning -> "приёмник запущен, слушает порт $SYNC_PORT"
            else -> "приёмник НЕ запущен" + (ReceiverService.lastError?.let { " ($it)" } ?: "")
        }
        lines.add("ПК → телефон: $receiverState")
        HelloSender.describeLast()?.let { lines.add(it) }

        val addresses = NetworkErrors.localAddresses(context)
        lines.add(
            "Адреса телефона: " +
                (if (addresses.isEmpty()) "нет сети" else addresses.joinToString { "${it.kind} ${it.ip}" }),
        )
        if (addresses.any { it.kind == "VPN" }) {
            lines.add("Включён VPN: он может уводить трафик к ПК в туннель. Отключите его на время проверки.")
        }
        lines.joinToString("\n\n")
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        private const val TAG = "ClipSync"
        private const val PREFS = "clipsync_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_AUTO_CAPTURE = "auto_capture"
        private const val KEY_LAST_CLIP_STAMP = "last_clip_stamp"
        private const val KEY_LAST_CLIP_ID = "last_clip_id"

        private const val MAX_LIST = 300
        private const val MAX_UNPINNED = 300
        private const val SLICE_BYTES = 512 * 1024
        private const val THUMB_TARGET = 256
        private const val PREVIEW_MAX_SIDE = 2048
        private const val MAX_TEXT_BYTES = 8 * 1024 * 1024

        // One set per process: the activity, the receiver service and the share activity each
        // build their own Repository, and they must agree on what is already in the history.
        private val writeLock = Mutex()

        private val thumbnailCache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        }
        private val previewCache = LruCache<String, String>(600)

        @Volatile
        private var sharedIndex: ClipHashIndex? = null

        fun getOrCreateDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, id).apply()
            return id
        }
    }

    private fun hashIndex(): ClipHashIndex =
        sharedIndex ?: synchronized(Repository::class.java) {
            sharedIndex ?: ClipHashIndex(PrefsStore(prefs)).also { sharedIndex = it }
        }
}
