package dev.openauth.app.vault

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ключ не экспортируется: системная аутентификация проверяется самим Keystore при wrap/unwrap. */
class DeviceKeyStore(
    context: Context,
    private val alias: String = "dev.openauth.vault.wrap.v1",
) : VaultKeyProtector {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    @Synchronized override fun prepare(create: Boolean) {
        if (!keyguard.isDeviceSecure) throw VaultException(VaultFailure.DEVICE_NOT_SECURE)
        if (store.containsAlias(alias)) return
        if (!create) throw VaultException(VaultFailure.KEY_UNAVAILABLE)
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(15, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .build())
            generateKey()
        }
    }

    @Synchronized override fun wrap(key: ByteArray): ByteArray = authenticated {
        require(key.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, existingKey())
        cipher.updateAAD(AAD)
        cipher.iv + cipher.doFinal(key)
    }

    @Synchronized override fun unwrap(wrapped: ByteArray): ByteArray = authenticated {
        if (wrapped.size != 60) throw VaultException(VaultFailure.CORRUPT_STORAGE)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, existingKey(), GCMParameterSpec(128, wrapped, 0, 12))
        cipher.updateAAD(AAD)
        cipher.doFinal(wrapped, 12, wrapped.size - 12)
    }

    private fun existingKey(): SecretKey = try {
        store.getKey(alias, null) as? SecretKey ?: throw VaultException(VaultFailure.KEY_UNAVAILABLE)
    } catch (_: UnrecoverableKeyException) { throw VaultException(VaultFailure.KEY_INVALIDATED) }

    private inline fun <T> authenticated(block: () -> T): T = try { block() }
    catch (_: UserNotAuthenticatedException) { throw VaultException(VaultFailure.AUTHENTICATION_REQUIRED) }
    catch (_: KeyPermanentlyInvalidatedException) { throw VaultException(VaultFailure.KEY_INVALIDATED) }

    companion object { private val AAD = "OpenAuth vault DEK v1".toByteArray(Charsets.US_ASCII) }
}
