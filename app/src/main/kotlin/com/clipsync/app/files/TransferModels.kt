package com.clipsync.app.files

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** From the PC to this phone ([IN]) or from this phone to the PC ([OUT]). */
enum class Direction(val json: String) {
    IN("in"),
    OUT("out");

    companion object {
        fun fromJson(value: String?): Direction = values().firstOrNull { it.json == value } ?: IN
    }
}

enum class TState(val json: String) {
    /** Waiting for its turn (outgoing only). */
    QUEUED("queued"),
    ACTIVE("active"),
    COMPLETE("complete"),
    FAILED("failed"),
    CANCELLED("cancelled");

    val isTerminal: Boolean get() = this == COMPLETE || this == FAILED || this == CANCELLED

    companion object {
        fun fromJson(value: String?): TState = values().firstOrNull { it.json == value } ?: FAILED
    }
}

/** One row of the "Файлы" screen. */
data class TransferView(
    val id: String,
    val transferId: String,
    /** The device on the other end (the paired PC). */
    val deviceId: String = "",
    val direction: Direction,
    /** For an incoming file the cleaned name it is saved under; for an outgoing one its own name. */
    val name: String,
    val size: Long,
    val transferred: Long = 0,
    val state: TState,
    /** Why it failed - or, while it is still active, what is being retried. */
    val error: String? = null,
    /** Incoming: where the finished file is (a content:// address or a path). Outgoing: not used. */
    val location: String? = null,
    val startedAt: Long,
    val updatedAt: Long,
    val finishedAt: Long? = null,
    /** Bytes per second over the last moments (0 when not moving). */
    val rateBps: Long = 0,
    /** May the screen offer "Повторить"? Not saved: after a restart the sender has forgotten the job. */
    val canRetry: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("transferId", transferId)
        put("deviceId", deviceId)
        put("direction", direction.json)
        put("name", name)
        put("size", size)
        put("transferred", transferred)
        put("state", state.json)
        if (error != null) put("error", error)
        if (location != null) put("location", location)
        put("startedAt", startedAt)
        put("updatedAt", updatedAt)
        if (finishedAt != null) put("finishedAt", finishedAt)
    }

    companion object {
        fun fromJson(o: JSONObject): TransferView = TransferView(
            id = o.getString("id"),
            transferId = o.optString("transferId", ""),
            deviceId = o.optString("deviceId", ""),
            direction = Direction.fromJson(o.optString("direction")),
            name = o.optString("name", "file"),
            size = o.optLong("size", 0),
            transferred = o.optLong("transferred", 0),
            state = TState.fromJson(o.optString("state")),
            error = if (o.has("error") && !o.isNull("error")) o.getString("error") else null,
            location = if (o.has("location") && !o.isNull("location")) o.getString("location") else null,
            startedAt = o.optLong("startedAt", 0),
            updatedAt = o.optLong("updatedAt", 0),
            finishedAt = if (o.has("finishedAt") && !o.isNull("finishedAt")) o.getLong("finishedAt") else null,
        )
    }
}

/**
 * The saved list of transfers: a plain JSON file, not the database (no schema change - the first
 * change to the Room schema must be a step of its own, see ROADMAP). Written next to itself and
 * renamed, so a crash in the middle leaves the old list and not half of a new one. A file that
 * cannot be read is treated as empty; a newer version's extra fields are ignored.
 */
class TransferLog(private val file: File) {
    fun load(): List<TransferView> {
        return try {
            if (!file.isFile) return emptyList()
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val array = root.optJSONArray("transfers") ?: return emptyList()
            (0 until array.length()).mapNotNull { i ->
                try {
                    TransferView.fromJson(array.getJSONObject(i))
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(views: List<TransferView>) {
        try {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            for (v in views) array.put(v.toJson())
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(JSONObject().put("transfers", array).toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IOException("cannot replace ${file.name}")
            }
        } catch (_: Exception) {
            // The list is a convenience: failing to save it must never fail a transfer.
        }
    }
}

/**
 * The list of transfers in both directions, with a smoothed speed for each. Observed by the screen
 * ([views]); changed by the receiver and the sender. Same behaviour as the list in `filehub.rs`.
 */
class TransferRegistry(
    private val log: TransferLog? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val nanos: () -> Long = { System.nanoTime() },
) {
    private class Sample(var atNanos: Long, var bytes: Long, var rate: Double)

    private val lock = Any()
    private val list = ArrayList<TransferView>() // newest first
    private val samples = HashMap<String, Sample>()
    private val retryable = HashSet<String>()
    private val state = kotlinx.coroutines.flow.MutableStateFlow<List<TransferView>>(emptyList())

    val views: kotlinx.coroutines.flow.StateFlow<List<TransferView>> get() = state

    init {
        val loaded = log?.load().orEmpty()
        val now = clock()
        for (v in loaded.take(MAX_VIEWS)) {
            // Whatever was still running when the app ended did not finish.
            list.add(
                if (v.state.isTerminal) v else v.copy(
                    state = TState.FAILED,
                    error = "Прервано: приложение было закрыто",
                    finishedAt = now,
                    rateBps = 0,
                ),
            )
        }
        if (loaded.any { !it.state.isTerminal }) save()
        publish()
    }

    private fun publish() {
        state.value = list.map { it.copy(canRetry = it.direction == Direction.OUT && it.state.isTerminal && it.id in retryable) }
    }

    private fun save() {
        // Finished rows, and incoming rows that are still running: after a restart those show as interrupted
        // (and the sender may continue them). An outgoing row that is still running cannot be continued.
        log?.save(list.filter { it.state.isTerminal || it.direction == Direction.IN }.take(MAX_VIEWS))
    }

    fun snapshot(): List<TransferView> = state.value

    fun get(id: String): TransferView? = synchronized(lock) { list.firstOrNull { it.id == id } }

    fun add(view: TransferView) = synchronized(lock) {
        list.add(0, view)
        if (list.size > MAX_VIEWS) {
            val victim = list.indexOfLast { it.state.isTerminal }
            if (victim >= 0) list.removeAt(victim)
        }
        save()
        publish()
    }

    /** Replaces a row by id with the result of [change]; a no-op when it is not there. */
    fun update(id: String, saveToDisk: Boolean = false, change: (TransferView) -> TransferView) = synchronized(lock) {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = change(list[i]).copy(updatedAt = clock())
            if (saveToDisk) save()
            publish()
        }
    }

    /** Records progress and keeps a smoothed speed. */
    fun progress(id: String, transferred: Long) = synchronized(lock) {
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return@synchronized
        val now = nanos()
        val sample = samples.getOrPut(id) { Sample(now, transferred, 0.0) }
        val elapsed = now - sample.atNanos
        if (elapsed >= RATE_WINDOW_NANOS) {
            val instant = (transferred - sample.bytes).coerceAtLeast(0) / (elapsed / 1e9)
            sample.rate = if (sample.rate <= 0.0) instant else 0.5 * sample.rate + 0.5 * instant
            sample.atNanos = now
            sample.bytes = transferred
        }
        val old = list[i]
        list[i] = old.copy(
            transferred = transferred,
            updatedAt = clock(),
            rateBps = sample.rate.toLong(),
            state = if (old.state == TState.QUEUED) TState.ACTIVE else old.state,
        )
        // Every step is published: the screen collects a StateFlow, which drops the values it had no time
        // for, so a fast transfer costs a redraw per frame, not per chunk - and the last value is never stale.
        publish()
    }

    /** Moves a row to a final state; [location] is where an incoming file ended up. */
    fun finish(id: String, newState: TState, error: String? = null, location: String? = null) = synchronized(lock) {
        samples.remove(id)
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            val old = list[i]
            val now = clock()
            list[i] = old.copy(
                state = newState,
                error = error,
                location = location ?: old.location,
                rateBps = 0,
                updatedAt = now,
                finishedAt = now,
                transferred = if (newState == TState.COMPLETE) old.size else old.transferred,
            )
        }
        save()
        publish()
    }

    fun markRetryable(id: String, yes: Boolean) = synchronized(lock) {
        if (yes) retryable.add(id) else retryable.remove(id)
        publish()
    }

    /** Forgets the speed measured so far (a transfer that starts over must not inherit it). */
    fun clearSamples(id: String) {
        synchronized(lock) { samples.remove(id) }
    }

    /** Removes finished rows from the list (never the files): one, or all of them. */
    fun removeFinished(id: String?) = synchronized(lock) {
        list.removeAll { it.state.isTerminal && (id == null || it.id == id) }
        retryable.retainAll(list.map { it.id }.toSet())
        save()
        publish()
    }

    companion object {
        const val MAX_VIEWS = 100
        private const val RATE_WINDOW_NANOS = 500_000_000L
    }
}
