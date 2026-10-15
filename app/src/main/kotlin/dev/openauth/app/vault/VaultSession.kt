package dev.openauth.app.vault

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Сессия владеет DEK; после закрытия новые операции получить его не могут. */
internal class VaultSession(key: ByteArray, val wrappedKey: ByteArray) : AutoCloseable {
    private var key: ByteArray? = key.copyOf()

    @Synchronized fun copyKey(): ByteArray = key?.copyOf() ?: throw VaultException(VaultFailure.LOCKED)

    @Synchronized override fun close() {
        key?.fill(0)
        key = null
    }
}

internal object VaultCipher {
    private val random = SecureRandom()
    fun newKey() = ByteArray(32).also(random::nextBytes)

    fun encrypt(key: ByteArray, plaintext: ByteArray, aad: ByteArray): ByteArray {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, ciphertext: ByteArray, aad: ByteArray): ByteArray {
        if (ciphertext.size < 28) throw VaultException(VaultFailure.CORRUPT_STORAGE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, ciphertext, 0, 12))
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, 12, ciphertext.size - 12)
    }
}
