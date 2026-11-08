package com.clipsync.jvmtests

import com.clipsync.app.files.FileKind
import com.clipsync.app.files.LinkResult
import com.clipsync.app.files.PcLink
import com.clipsync.app.files.StagedSource
import com.clipsync.app.files.TState
import com.clipsync.app.files.TransferManager
import com.clipsync.app.files.TransferRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private fun waitUntil(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for: $what")
        Thread.sleep(10)
    }
}

class ManagerRig(val pc: FakePc = FakePc(), var link: () -> LinkResult = { LinkResult.Unavailable("unset") }) {
    val registry = TransferRegistry(null)
    val activity = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
    val cleaned = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val ids = AtomicInteger(1)

    init {
        link = { LinkResult.Ok(PcLink(JdkTransport(pc.base), PC_KEY)) }
    }

    val manager = TransferManager(
        registry = registry,
        link = { link() },
        activity = { activity.add(it) },
        cleanup = { cleaned.add(it.name) },
        newId = { "aaaaaaaa-0000-0000-0000-%012d".format(ids.getAndIncrement()) },
        pausesMs = listOf(2, 2, 2),
        sleep = { Thread.sleep(1) },
    )

    fun settled() = registry.snapshot().none { it.state == TState.QUEUED || it.state == TState.ACTIVE }
}

fun ManagerTests() {
    println("Queue")

    Kit.test("files are sent one after another and the service is on exactly while there is work") {
        val rig = ManagerRig()
        val ids = rig.manager.enqueue(listOf(BytesSource("a.bin", sample(5000, 1)), BytesSource("b.bin", sample(70000, 2)), BytesSource("c.txt", "hi".toByteArray())))
        eq(ids.size, 3)
        waitUntil("all done") { rig.settled() && rig.activity.lastOrNull() == false }
        eq(rig.registry.snapshot().map { it.state }.toSet(), setOf(TState.COMPLETE))
        eqBytes(File(rig.pc.inbox, "b.bin").readBytes(), sample(70000, 2))
        eq(rig.activity.toList(), listOf(true, false), "switched on once and off once")
        eq(rig.pc.seen.map { it.first }.filter { it == FileKind.OFFER }.size, 3)
        eq(rig.cleaned.sorted(), listOf("a.bin", "b.bin", "c.txt"), "sources are released after success")
        // Order: the first offer is for the first file (it is a one-at-a-time queue).
        eq(rig.registry.snapshot().last().name, "a.bin", "the list is newest first, the queue oldest first")
        rig.pc.stop()
    }

    Kit.test("work that arrives after the queue emptied switches the service on again") {
        val rig = ManagerRig()
        rig.manager.enqueue(listOf(BytesSource("one.bin", sample(100))))
        waitUntil("first done") { rig.settled() && rig.activity.lastOrNull() == false }
        rig.manager.enqueue(listOf(BytesSource("two.bin", sample(100))))
        waitUntil("second done") { rig.settled() && rig.activity.size == 4 && rig.activity.last() == false }
        eq(rig.activity.toList(), listOf(true, false, true, false))
        rig.pc.stop()
    }

    Kit.test("many threads enqueueing at once never cross the service on and off") {
        val rig = ManagerRig()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val total = 40
        repeat(total) { i ->
            pool.execute {
                start.await()
                rig.manager.enqueue(listOf(BytesSource("f$i.bin", sample(200 + i, i))))
            }
        }
        start.countDown()
        pool.shutdown()
        check(pool.awaitTermination(30, TimeUnit.SECONDS))
        waitUntil("all done", 40_000) { rig.registry.snapshot().size == total && rig.settled() && rig.activity.lastOrNull() == false }
        eq(rig.registry.snapshot().count { it.state == TState.COMPLETE }, total)
        // The log must alternate true/false and end with false: never two "on"s in a row, never "off" while work remains.
        val log = rig.activity.toList()
        check(log.first() && !log.last())
        for (i in 1 until log.size) check(log[i] != log[i - 1], "on/off crossed at $i: $log")
        rig.pc.stop()
    }

    Kit.test("an unavailable PC fails the file with the reason, and a retry works once the PC is back") {
        val rig = ManagerRig()
        val good = rig.link
        rig.link = { LinkResult.Unavailable("ClipSync на ПК не умеет принимать файлы") }
        val id = rig.manager.enqueue(listOf(BytesSource("later.bin", sample(300))))[0]
        waitUntil("failed") { rig.settled() }
        val failed = rig.registry.get(id)!!
        eq(failed.state, TState.FAILED)
        check(failed.error!!.contains("не умеет принимать файлы"))
        check(rig.registry.snapshot()[0].canRetry)
        eq(rig.cleaned.size, 0, "the source is kept for the retry")

        rig.link = good
        eq(rig.manager.retry(id), null)
        waitUntil("done") { rig.settled() && rig.activity.lastOrNull() == false }
        eq(rig.registry.get(id)!!.state, TState.COMPLETE)
        eqBytes(File(rig.pc.inbox, "later.bin").readBytes(), sample(300))
        eq(rig.registry.snapshot().size, 1, "one row")
        eq(rig.cleaned, listOf("later.bin"))
        rig.pc.stop()
    }

    Kit.test("a link that throws does not take the worker down") {
        val rig = ManagerRig()
        val good = rig.link
        rig.link = { throw IllegalStateException("boom") }
        val a = rig.manager.enqueue(listOf(BytesSource("a.bin", sample(10))))[0]
        waitUntil("a failed") { rig.settled() }
        check(rig.registry.get(a)!!.error!!.contains("boom"))
        rig.link = good
        val b = rig.manager.enqueue(listOf(BytesSource("b.bin", sample(10))))[0]
        waitUntil("b done") { rig.settled() && rig.activity.lastOrNull() == false }
        eq(rig.registry.get(b)!!.state, TState.COMPLETE)
        rig.pc.stop()
    }

    Kit.test("a file that waits in the queue can be cancelled; the others go on") {
        val rig = ManagerRig()
        val gate = CountDownLatch(1)
        rig.pc.faults = { kind, index, _ ->
            if (kind == FileKind.OFFER) gate.await(10, TimeUnit.SECONDS)
            Fault.None
        }
        val ids = rig.manager.enqueue(listOf(BytesSource("first.bin", sample(1000)), BytesSource("second.bin", sample(1000)), BytesSource("third.bin", sample(1000))))
        waitUntil("first reached the PC") { rig.pc.requests(FileKind.OFFER) >= 1 }
        check(rig.manager.cancel(ids[1]))
        gate.countDown()
        waitUntil("settled") { rig.settled() && rig.activity.lastOrNull() == false }
        eq(rig.registry.get(ids[0])!!.state, TState.COMPLETE)
        eq(rig.registry.get(ids[1])!!.state, TState.CANCELLED)
        eq(rig.registry.get(ids[2])!!.state, TState.COMPLETE)
        check(!File(rig.pc.inbox, "second.bin").exists())
        check(!rig.manager.cancel(ids[0]), "a finished transfer cannot be cancelled")
        rig.pc.stop()
    }

    Kit.test("a cancelled file is retried as a new transfer") {
        val rig = ManagerRig()
        val gate = CountDownLatch(1)
        rig.pc.faults = { kind, _, _ ->
            if (kind == FileKind.OFFER) gate.await(10, TimeUnit.SECONDS)
            Fault.None
        }
        val ids = rig.manager.enqueue(listOf(BytesSource("a.bin", sample(500)), BytesSource("b.bin", sample(500))))
        waitUntil("first at the PC") { rig.pc.requests(FileKind.OFFER) >= 1 }
        rig.manager.cancel(ids[1])
        gate.countDown()
        waitUntil("settled") { rig.settled() }
        val oldTransferId = rig.registry.get(ids[1])!!.transferId
        eq(rig.manager.retry(ids[1]), null)
        waitUntil("done") { rig.settled() && rig.activity.lastOrNull() == false }
        eq(rig.registry.get(ids[1])!!.state, TState.COMPLETE)
        check(rig.registry.get(ids[1])!!.transferId != oldTransferId, "a cancelled transfer must not reuse its id")
        rig.pc.stop()
    }

    Kit.test("retry is refused for what cannot be retried, with a sentence") {
        val rig = ManagerRig()
        val id = rig.manager.enqueue(listOf(BytesSource("ok.bin", sample(10))))[0]
        waitUntil("done") { rig.settled() }
        check(rig.manager.retry(id)!!.contains("нельзя"), "a finished one")
        check(rig.manager.retry("out-nothing")!!.isNotEmpty())
        rig.pc.stop()
    }

    Kit.test("clearing finished rows forgets the jobs and releases what was kept for a retry") {
        val rig = ManagerRig()
        rig.link = { LinkResult.Unavailable("no PC") }
        val ids = rig.manager.enqueue(listOf(BytesSource("x.bin", sample(10)), BytesSource("y.bin", sample(10))))
        waitUntil("failed") { rig.settled() }
        eq(rig.cleaned.size, 0)
        rig.manager.clear(ids[0])
        eq(rig.cleaned, listOf("x.bin"))
        eq(rig.registry.snapshot().map { it.name }, listOf("y.bin"))
        check(rig.manager.retry(ids[0]) != null, "its job is gone")
        rig.manager.clear(null)
        eq(rig.cleaned.sorted(), listOf("x.bin", "y.bin"))
        rig.pc.stop()
    }

    Kit.test("unpairing stops what is running") {
        val rig = ManagerRig()
        val gate = CountDownLatch(1)
        rig.pc.faults = { kind, index, _ ->
            if (kind == FileKind.CHUNK && index == 1L) {
                rig.manager.dropAll()
                gate.countDown()
            }
            Fault.None
        }
        val id = rig.manager.enqueue(listOf(BytesSource("big.bin", sample(com.clipsync.app.files.FileProtocol.DEFAULT_CHUNK_BYTES * 5))))[0]
        gate.await(10, TimeUnit.SECONDS)
        waitUntil("settled") { rig.settled() }
        eq(rig.registry.get(id)!!.state, TState.CANCELLED)
        rig.pc.stop()
    }

    Kit.test("a staged copy is deleted after a successful send") {
        val rig = ManagerRig()
        val copy = File(tempDir("staged"), "copy.bin").also { it.writeBytes(sample(4000)) }
        val manager = TransferManager(
            registry = rig.registry,
            link = { LinkResult.Ok(PcLink(JdkTransport(rig.pc.base), PC_KEY)) },
            activity = { },
            cleanup = { (it as? StagedSource)?.delete() },
            pausesMs = listOf(2),
            sleep = { Thread.sleep(1) },
        )
        manager.enqueue(listOf(StagedSource(copy, "report.pdf")))
        waitUntil("done") { rig.settled() }
        waitUntil("deleted") { !copy.exists() }
        eqBytes(File(rig.pc.inbox, "report.pdf").readBytes(), sample(4000))
        rig.pc.stop()
    }
}
