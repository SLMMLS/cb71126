package com.clipsync.jvmtests

import java.io.File
import java.nio.file.Files

/** A very small test kit: the Android project has no JUnit (adding it would be a dependency decision), so tests are plain functions. */
object Kit {
    var passed = 0
    val failures = mutableListOf<String>()

    fun test(name: String, body: () -> Unit) {
        try {
            body()
            passed += 1
            println("  ok   $name")
        } catch (t: Throwable) {
            failures += name
            println("  FAIL $name\n       $t")
            t.stackTrace.take(4).forEach { println("         at $it") }
        }
    }

    fun finish(): Int {
        println("\n$passed passed, ${failures.size} failed")
        if (failures.isNotEmpty()) println("Failed:\n - " + failures.joinToString("\n - "))
        return if (failures.isEmpty()) 0 else 1
    }
}

fun check(condition: Boolean, message: String = "check failed") {
    if (!condition) throw AssertionError(message)
}

fun <T> eq(actual: T, expected: T, message: String = "") {
    if (actual != expected) throw AssertionError("expected <$expected> but was <$actual> $message")
}

fun eqBytes(actual: ByteArray, expected: ByteArray, message: String = "") {
    if (!actual.contentEquals(expected)) throw AssertionError("byte arrays differ (${actual.size} vs ${expected.size} bytes) $message")
}

fun tempDir(tag: String): File = Files.createTempDirectory("clipsync-$tag-").toFile()

/** Deterministic pseudo-random bytes, so that a wrong byte anywhere shows up. */
fun sample(length: Int, seed: Int = 1): ByteArray = ByteArray(length) { i -> ((i * 31 + (i shr 7) + seed * 17) xor (i shr 3)).toByte() }
