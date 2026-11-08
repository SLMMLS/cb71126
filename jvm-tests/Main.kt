package com.clipsync.jvmtests

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val vectors = args.getOrNull(0) ?: error("usage: Main <path to file-name-vectors.json>")
    ReceiverTests(vectors)
    SenderTests()
    ManagerTests()
    exitProcess(Kit.finish())
}
