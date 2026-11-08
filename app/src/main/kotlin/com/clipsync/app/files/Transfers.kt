package com.clipsync.app.files

import android.content.Context
import android.net.Uri
import android.util.Log
import com.clipsync.app.crypto.CryptoUtils
import com.clipsync.app.crypto.Identity
import com.clipsync.app.data.AppDatabase
import com.clipsync.app.data.Repository
import com.clipsync.app.network.HelloSender
import com.clipsync.app.network.PcFeatures
import com.clipsync.app.network.SyncClient
import com.clipsync.app.service.ReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

private const val TAG = "ClipSync"

/**
 * File transfer as the app sees it: one list of transfers ([registry]), one receiver ([receiver], plugged
 * into the server in `ReceiverService`) and one sending queue ([manager]), built once when the process
 * starts (`ClipSyncApp.onCreate`) so that the screen, the services and the share target all talk to the
 * same objects. The logic is in the classes it assembles; this only connects them to Android.
 */
object Transfers {
    @Volatile
    private var started = false

    lateinit var registry: TransferRegistry
        private set
    lateinit var receiver: FileReceiver
        private set
    lateinit var manager: TransferManager
        private set

    fun init(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            val app = context.applicationContext
            TransferNotifications.ensureChannels(app)
            FileSources.cleanStale(app)

            registry = TransferRegistry(TransferLog(File(app.filesDir, "file-transfers.json")))
            receiver = FileReceiver(
                dataDir = app.filesDir,
                registry = registry,
                sink = PublicDownloads(app),
                policy = {
                    ReceivePolicy(
                        acceptIncoming = FilesSettings.acceptIncoming(app),
                        maxBytes = FileProtocol.MAX_FILE_BYTES,
                        // A file is stored twice for a moment: the partial file, then its copy in Downloads.
                        freeBytes = app.filesDir.usableSpace / 2,
                    )
                },
                listener = object : ReceiveListener {
                    override fun received(name: String, location: String) = TransferNotifications.received(app, name, location)

                    override fun failed(name: String, reason: String) = TransferNotifications.failed(app, name, reason)
                },
            )
            manager = TransferManager(
                registry = registry,
                link = { pcLink(app) },
                activity = { busy -> if (busy) TransferService.start(app) else TransferService.stop(app) },
                cleanup = { (it as? StagedSource)?.delete() },
            )

            // The progress notifications follow the list. A collector that sleeps between values makes the
            // StateFlow hand it only the latest one, so a fast transfer does not flood the notification manager.
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                registry.views.collect { views ->
                    try {
                        TransferNotifications.sync(app, views)
                    } catch (e: Exception) {
                        Log.w(TAG, "could not update the file notifications", e)
                    }
                    delay(400)
                }
            }
            started = true
        }
    }

    /**
     * The paired PC for the next file: its address and pinned certificate, and the key shared with it. Fails
     * with a sentence when the phone is not paired, or the PC has not said it can take files - the ability
     * is relied on only after the PC itself listed it (PROTOCOL.md §3b), so an older ClipSync on the PC
     * gets a clear message instead of a 404.
     */
    private fun pcLink(app: Context): LinkResult {
        val pc = runBlocking { AppDatabase.get(app).pairedPcDao().getPc() }
            ?: return LinkResult.Unavailable("Телефон не сопряжён с ПК")

        if (!PcFeatures.has(app, pc.deviceId, FileProtocol.FEATURE)) {
            // The PC may have been updated since the last "я в сети": ask once, so that the answer is current.
            HelloSender.send(app, "files", receiver = ReceiverService.isRunning)
            if (!PcFeatures.has(app, pc.deviceId, FileProtocol.FEATURE)) {
                val last = HelloSender.last
                return LinkResult.Unavailable(
                    when {
                        last == null -> "Не удалось связаться с ПК"
                        last.ok -> "ClipSync на ПК не умеет принимать файлы. Обновите его на ПК до версии с передачей файлов."
                        else -> "Нет связи с ПК: ${last.message}"
                    },
                )
            }
        }

        val identity = Identity.loadOrCreate(app)
        val key = CryptoUtils.deriveTransportKey(Identity.sharedSecret(identity.privateKey, pc.publicKey))
        val transport = OkHttpFileTransport(SyncClient.filesClient(pc.certHash), "https://${pc.address}", Repository.getOrCreateDeviceId(app))
        return LinkResult.Ok(PcLink(transport, key))
    }

    /** What [prepare] made of the addresses it was given. */
    class Prepared(val sources: List<FileSource>, val problems: List<String>)

    /**
     * Turns addresses from the file picker or the share sheet into sources. Blocking (a share is copied):
     * call from a background thread. [stage] makes a private copy of each file - needed for a share, whose
     * access ends with the share screen.
     */
    fun prepare(context: Context, uris: List<Uri>, stage: Boolean): Prepared {
        val app = context.applicationContext
        val sources = ArrayList<FileSource>()
        val problems = ArrayList<String>()
        for (uri in uris) {
            try {
                if (!FileSources.isAcceptable(uri)) {
                    problems.add("${uri.lastPathSegment ?: uri}: этот адрес файла не поддерживается")
                    continue
                }
                sources.add(if (stage) FileSources.stage(app, uri) else FileSources.fromPicker(app, uri))
            } catch (e: Exception) {
                problems.add(e.message ?: e.javaClass.simpleName)
            }
        }
        return Prepared(sources, problems)
    }

    /** The PC was unpaired: stop what involves it and forget what it could do. */
    fun onUnpaired(context: Context) {
        if (!started) return
        manager.dropAll()
        receiver.dropAll()
        PcFeatures.clear(context)
    }
}
