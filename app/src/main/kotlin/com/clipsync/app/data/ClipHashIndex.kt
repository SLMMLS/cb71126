package com.clipsync.app.data

import org.json.JSONObject
import java.security.MessageDigest

/** Where the index is persisted: SharedPreferences in the app, a plain variable in tests. */
interface StringStore {
    fun read(): String?
    fun write(value: String)
}

/**
 * "This exact content is already in the history as entry X."
 *
 * Why it exists: the same thing reaches the phone through several doors - copied on the
 * phone (captured when the app is opened), pushed by the PC, sent from the phone - and
 * without this index each door would add its own row. The PC re-pushes whatever the user
 * re-copies there, and a user who copies the same word twice is not asking for two entries.
 * With it, a repeat just moves the existing entry to the top.
 *
 * The room database has no content-hash column (adding one needs a schema migration for
 * every user), so the index lives next to it in a small bounded map: hash -> entry id,
 * oldest first, capped at [capacity]. A hash whose entry has since been deleted is simply
 * ignored by the caller and overwritten.
 */
class ClipHashIndex(private val store: StringStore, private val capacity: Int = 150) {
    private val map = LinkedHashMap<String, String>()
    private var loaded = false

    @Synchronized
    fun find(hash: String): String? {
        ensureLoaded()
        return map[hash]
    }

    @Synchronized
    fun put(hash: String, id: String) {
        ensureLoaded()
        map.remove(hash) // re-insert at the end: most recently seen
        map[hash] = id
        while (map.size > capacity) map.remove(map.keys.first())
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        store.write(json.toString())
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        val raw = store.read() ?: return
        try {
            val json = JSONObject(raw)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getString(key)
            }
        } catch (e: Exception) {
            map.clear() // a corrupt index is only a lost optimisation
        }
    }

    companion object {
        /** Hash of "type + content": the same bytes as text and as an image are different things. */
        fun hashOf(entryType: String, content: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(entryType.toByteArray(Charsets.UTF_8))
            digest.update(0.toByte())
            digest.update(content)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
