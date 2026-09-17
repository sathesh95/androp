package com.clipboardsync.android.crypto

import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashSet

object HashUtil {
    private val maxHistorySize = 100
    private val knownHashes = Collections.synchronizedSet(LinkedHashSet<String>())

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun markHashAsHandled(hash: String) {
        synchronized(knownHashes) {
            knownHashes.add(hash)
            if (knownHashes.size > maxHistorySize) {
                val iterator = knownHashes.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    fun isHashKnown(hash: String): Boolean {
        return knownHashes.contains(hash)
    }
}
