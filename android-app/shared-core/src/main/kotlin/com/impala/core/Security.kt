package com.impala.core

import java.security.MessageDigest

object SecureTokenStore {
    fun maskToken(token: String): String {
        if (token.length < 10) return "***"
        return token.take(4) + "..." + token.takeLast(4)
    }

    fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
