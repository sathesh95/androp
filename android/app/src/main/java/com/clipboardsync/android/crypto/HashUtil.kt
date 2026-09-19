package com.clipboardsync.android.crypto

import java.security.MessageDigest
import java.util.Collections
import java.util.HashSet

object HashUtil {
    private val remoteInjectedHashes = Collections.synchronizedSet(HashSet<String>())

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun markRemoteInjected(hash: String) {
        remoteInjectedHashes.add(hash)
    }

    fun consumeRemoteInjectedIfPresent(hash: String): Boolean {
        return remoteInjectedHashes.remove(hash)
    }
}
