package com.clipsync.app.files

import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Everything needed to talk to the paired PC for one file: the connection and the key shared with it. */
class PcLink(val transport: FileTransport, val key: ByteArray)

sealed class LinkResult {
    class Ok(val link: PcLink) : LinkResult()

    /** [reason] is a sentence for the person: not paired, an older ClipSync on the PC, no network. */
    class Unavailable(val reason: String) : LinkResult()
}

/**
 * The files going from this phone to the PC: a queue, one at a time (several files at once to the
 * same Wi-Fi would only make each of them slower), with "cancel" and "retry" per file. The sending
 * itself is [FileSender]; this decides which file is next and keeps the foreground service (which
 * keeps the process alive while the screen is off) running exactly while there is work.
 *
 * Nothing here knows about Android, so it is tested on a plain JVM.
 */
class TransferManager(
    private val registry: TransferRegistry,
    /** Resolves the PC for the next file (reads the pairing, checks the PC can take files). Runs on the worker thread. */
    private val link: () -> LinkResult,
    /** Called with `true` when there is work and `false` when there is not: starts and stops the foreground service. */
    private val activity: (Boolean) -> Unit,
    /** Called when a source is no longer needed (a copy made for sending is deleted). */
    private val cleanup: (FileSource) -> Unit = {},
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val pausesMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val executor: Executor = Executors.newSingleThreadExecutor { r -> Thread(r, "clipsync-files-send").also { it.isDaemon = true } },
) {
    private val lock = Any()
    private val jobs = LinkedHashMap<String, SendJob>() // view id -> job; kept while the row exists, so "Повторить" works
    private val waiting = ArrayDeque<String>()
    private var working = false

    /** Queues [sources] for the PC and returns the ids of their rows. */
    fun enqueue(sources: List<FileSource>): List<String> {
        val ids = ArrayList<String>()
        for (source in sources) {
            val transferId = newId()
            val viewId = "out-$transferId"
            val now = clock()
            registry.add(
                TransferView(
                    id = viewId, transferId = transferId, direction = Direction.OUT, name = source.name, size = source.size,
                    state = TState.QUEUED, startedAt = now, updatedAt = now,
                ),
            )
            synchronized(lock) {
                jobs[viewId] = SendJob(viewId, transferId, source)
                waiting.addLast(viewId)
            }
            ids.add(viewId)
        }
        startWorkerIfNeeded()
        return ids
    }

    /** "Отменить": takes effect between two chunks, or at once for a file still waiting. */
    fun cancel(viewId: String): Boolean {
        val view = registry.get(viewId) ?: return false
        if (view.state.isTerminal) return false
        val job = synchronized(lock) { jobs[viewId] } ?: return false
        job.cancel.set(true)
        return true
    }

    /**
     * "Повторить": a failed transfer continues where the PC says it got to; a cancelled one starts
     * again under a new id (the PC refuses an id it was told to cancel). Returns a sentence when it cannot.
     */
    fun retry(viewId: String): String? {
        val view = registry.get(viewId) ?: return "Передача не найдена"
        if (view.direction != Direction.OUT || !view.state.isTerminal || view.state == TState.COMPLETE) {
            return "Эту передачу нельзя повторить"
        }
        val job = synchronized(lock) { jobs[viewId] }
            ?: return "Передачу нельзя повторить: после перезапуска ClipSync она забыта. Отправьте файл заново."
        if (!job.canResume) job.transferId = newId()
        job.cancel.set(false)
        registry.markRetryable(viewId, false)
        registry.update(viewId) { it.copy(transferId = job.transferId, state = TState.QUEUED, error = null, finishedAt = null) }
        synchronized(lock) { waiting.addLast(viewId) }
        startWorkerIfNeeded()
        return null
    }

    /** Removes finished rows from the list (never the files already saved on the PC): one, or all of them. */
    fun clear(viewId: String?) {
        registry.removeFinished(viewId)
        val alive = registry.snapshot().map { it.id }.toSet()
        val forgotten = synchronized(lock) {
            val dead = jobs.filter { (id, _) -> id !in alive && id !in waiting }
            dead.keys.forEach { jobs.remove(it) }
            dead.values
        }
        forgotten.forEach { cleanup(it.source) }
    }

    /** The PC was unpaired: everything stops. */
    fun dropAll() {
        synchronized(lock) { jobs.values.forEach { it.cancel.set(true) } }
    }

    private fun startWorkerIfNeeded() {
        synchronized(lock) {
            if (working) return
            working = true
            // Under the same lock that decides `working`, so "on" and "off" can never cross.
            activity(true)
        }
        executor.execute { work() }
    }

    private fun work() {
        while (true) {
            val job: SendJob = synchronized(lock) {
                val id = waiting.removeFirstOrNull()
                if (id == null) {
                    working = false
                    activity(false)
                    return
                }
                jobs[id]
            } ?: continue
            try {
                run(job)
            } catch (e: Throwable) {
                // Whatever went wrong with one file must not stop the others - or the service from being switched off.
                job.canResume = true
                registry.finish(job.viewId, TState.FAILED, "Неожиданная ошибка: ${e.message ?: e.javaClass.simpleName}")
                registry.markRetryable(job.viewId, true)
            }
        }
    }

    private fun run(job: SendJob) {
        if (job.cancel.get()) {
            job.canResume = false
            registry.finish(job.viewId, TState.CANCELLED, "Отменено вами")
            registry.markRetryable(job.viewId, true)
            return
        }
        when (val result = link()) {
            is LinkResult.Unavailable -> {
                job.canResume = true
                registry.finish(job.viewId, TState.FAILED, result.reason)
                registry.markRetryable(job.viewId, true)
            }
            is LinkResult.Ok -> {
                val outcome = FileSender(result.link.transport, result.link.key, registry, pausesMs, sleep).run(job)
                if (outcome == SendOutcome.DONE) {
                    synchronized(lock) { jobs.remove(job.viewId) }
                    cleanup(job.source)
                }
            }
        }
    }
}
