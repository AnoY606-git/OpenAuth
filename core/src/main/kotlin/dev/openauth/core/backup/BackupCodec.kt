package dev.openauth.core.backup

import dev.openauth.core.model.VaultSnapshot
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

enum class BackupFailure { INVALID_CREDENTIAL, INVALID_FORMAT, LIMIT_EXCEEDED, AUTHENTICATION_FAILED, CLOSED, CRYPTO_FAILURE }

/** Без цепочки причин: сообщения провайдера и парсера могут содержать чувствительный ввод. */
class BackupException(val reason: BackupFailure) : Exception(reason.name)

class BackupCodec(private val random: SecureRandom = SecureRandom()) {
    fun createGeneration(credential: BackupCredential): BackupGeneration {
        val password = credential.copyBytes()
        val key = ByteArray(32)
        var kek: ByteArray? = null
        try {
            val header = ByteBuffer.allocate(72).put(MAGIC).putShort(1).put(credential.kind.toByte()).put(1)
                .putInt(19).putInt(65536).putInt(3).putInt(4)
                .put(randomBytes(16)).put(randomBytes(16)).put(randomBytes(12)).array()
            random.nextBytes(key)
            kek = derive(password, header.copyOfRange(28, 44))
            val wrapper = crypt(Cipher.ENCRYPT_MODE, kek, header.copyOfRange(60, 72), WRAPPER_DOMAIN + header, key)
            return BackupGeneration(header, wrapper, key)
        } finally {
            password.fill(0)
            key.fill(0)
            kek?.fill(0)
        }
    }

    fun seal(snapshot: VaultSnapshot, generation: BackupGeneration, snapshotId: UUID, createdAtEpochSeconds: Long): ByteArray {
        if (createdAtEpochSeconds < 0) throw BackupException(BackupFailure.INVALID_FORMAT)
        return generation.withMaterial { header, wrapper, key ->
            val plaintext = SnapshotJsonCodec.encode(snapshot)
            try {
                val snapshotHeader = ByteBuffer.allocate(40).putLong(snapshotId.mostSignificantBits)
                    .putLong(snapshotId.leastSignificantBits).putLong(createdAtEpochSeconds)
                    .put(randomBytes(12)).putInt(plaintext.size).array()
                val prefix = header + wrapper + snapshotHeader
                prefix + crypt(Cipher.ENCRYPT_MODE, key, snapshotHeader.copyOfRange(24, 36), PAYLOAD_DOMAIN + prefix, plaintext)
            } finally { plaintext.fill(0) }
        }
    }

    fun open(container: ByteArray, credential: BackupCredential): VaultSnapshot {
        // Проверки ограничений обязательно предшествуют чтению пароля и дорогостоящему KDF.
        if (container.size > 16 * 1024 * 1024) throw BackupException(BackupFailure.LIMIT_EXCEEDED)
        if (container.size < 176) invalid()
        val header = container.copyOfRange(0, 72)
        validateHeader(header)
        val view = ByteBuffer.wrap(container)
        val length = view.getInt(156)
        if (length > SnapshotJsonCodec.MAX_BYTES) throw BackupException(BackupFailure.LIMIT_EXCEEDED)
        if (length < 0 || container.size.toLong() != 176L + length || view.getLong(136) < 0) invalid()
        if (header[10].toInt() != credential.kind) throw BackupException(BackupFailure.AUTHENTICATION_FAILED)
        val password = credential.copyBytes()
        var kek: ByteArray? = null
        var key: ByteArray? = null
        var plaintext: ByteArray? = null
        try {
            kek = derive(password, header.copyOfRange(28, 44))
            key = crypt(Cipher.DECRYPT_MODE, kek, header.copyOfRange(60, 72), WRAPPER_DOMAIN + header, container.copyOfRange(72, 120))
            plaintext = crypt(Cipher.DECRYPT_MODE, key, container.copyOfRange(144, 156),
                PAYLOAD_DOMAIN + container.copyOfRange(0, 160), container.copyOfRange(160, container.size))
            return SnapshotJsonCodec.decode(plaintext)
        } finally {
            password.fill(0)
            kek?.fill(0)
            key?.fill(0)
            plaintext?.fill(0)
        }
    }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun derive(password: ByteArray, salt: ByteArray): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withMemoryAsKB(65536).withIterations(3).withParallelism(4).withSalt(salt).build()
        val result = ByteArray(32)
        try {
            val generator = Argon2BytesGenerator()
            generator.init(parameters)
            generator.generateBytes(password, result)
            return result
        } catch (_: RuntimeException) {
            result.fill(0)
            throw BackupException(BackupFailure.CRYPTO_FAILURE)
        } finally { parameters.clear() }
    }

    private fun crypt(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray): ByteArray {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            return cipher.doFinal(input)
        } catch (_: GeneralSecurityException) {
            throw BackupException(if (mode == Cipher.DECRYPT_MODE) BackupFailure.AUTHENTICATION_FAILED else BackupFailure.CRYPTO_FAILURE)
        }
    }

    companion object {
        private val MAGIC = "OABACKUP".toByteArray(Charsets.US_ASCII)
        private val WRAPPER_DOMAIN = "OpenAuth/backup/wrapper/v1\u0000".toByteArray(Charsets.US_ASCII)
        private val PAYLOAD_DOMAIN = "OpenAuth/backup/payload/v1\u0000".toByteArray(Charsets.US_ASCII)

        internal fun validateHeader(header: ByteArray) {
            if (header.size != 72 || !header.copyOfRange(0, 8).contentEquals(MAGIC)) invalid()
            val view = ByteBuffer.wrap(header)
            if (view.getShort(8).toInt() != 1 || header[10].toInt() !in 1..2 || header[11].toInt() != 1 ||
                view.getInt(12) != 19 || view.getInt(16) != 65536 || view.getInt(20) != 3 || view.getInt(24) != 4) invalid()
        }

        private fun invalid(): Nothing = throw BackupException(BackupFailure.INVALID_FORMAT)
    }
}
