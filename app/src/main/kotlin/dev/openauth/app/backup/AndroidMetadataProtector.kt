package dev.openauth.app.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface MetadataProtector {
    fun seal(bytes: ByteArray): ByteArray
    fun open(bytes: ByteArray): ByteArray
}
interface AtomicBytes {
    fun read(): ByteArray?
    fun writeAtomically(bytes: ByteArray)
}

/** Отдельный non-auth ключ защищает лишь метаданные очереди. Он не открывает vault или OTP. */
class AndroidMetadataProtector(private val alias: String = "OpenAuth.background.metadata.v1") : MetadataProtector {
    private fun key(create: Boolean): SecretKey = synchronized(keyGuard) {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(alias, null)
        if (existing is SecretKey) return@synchronized existing
        if (!create) throw BackupFlowException(BackupFlowFailure.STORAGE)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun seal(bytes: ByteArray): ByteArray {
        require(bytes.size <= MAX_METADATA_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true)); cipher.updateAAD(DOMAIN)
        check(cipher.iv.size == 12)
        return cipher.iv + cipher.doFinal(bytes)
    }
    override fun open(bytes: ByteArray): ByteArray {
        if (bytes.size !in 28..MAX_METADATA_BYTES + 28) throw BackupFlowException(BackupFlowFailure.STORAGE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(DOMAIN)
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
    companion object {
        private val keyGuard = Any()
        private val DOMAIN = "OpenAuth/background-metadata/v1".toByteArray(Charsets.US_ASCII)
        const val MAX_METADATA_BYTES = 64 * 1024
    }
}

class AndroidAtomicBytes(file: File) : AtomicBytes {
    private val atomic = AtomicFile(file)
    @Synchronized override fun read(): ByteArray? {
        val input = try { atomic.openRead() } catch (failure: FileNotFoundException) {
            if (!atomic.baseFile.exists() && !File(atomic.baseFile.path + ".bak").exists()) return null
            throw failure
        }
        return input.use {
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val count = it.read(chunk)
                if (count < 0) break
                if (buffer.size() + count > AndroidMetadataProtector.MAX_METADATA_BYTES + 28) throw IOException("Metadata size")
                buffer.write(chunk, 0, count)
            }
            buffer.toByteArray()
        }
    }
    @Synchronized override fun writeAtomically(bytes: ByteArray) {
        require(bytes.size <= AndroidMetadataProtector.MAX_METADATA_BYTES + 28)
        atomic.baseFile.parentFile?.mkdirs()
        val output = atomic.startWrite()
        try {
            output.write(bytes); output.fd.sync(); atomic.finishWrite(output)
            if (read()?.contentEquals(bytes) != true) throw IOException("Metadata commit")
        } catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
}
