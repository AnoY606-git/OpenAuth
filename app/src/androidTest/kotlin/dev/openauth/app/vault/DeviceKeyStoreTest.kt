package dev.openauth.app.vault

import android.app.KeyguardManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceKeyStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun authBoundKeyCannotWrapWithoutRecentSystemAuthentication() {
        assumeTrue(context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val alias = "openauth.test." + UUID.randomUUID()
        try {
            val keys = DeviceKeyStore(context, alias)
            keys.prepare(true)
            Thread.sleep(16_000)
            val error = assertThrows(VaultException::class.java) { keys.wrap(ByteArray(32)) }
            assertEquals(VaultFailure.AUTHENTICATION_REQUIRED, error.reason)
        } finally { KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) } }
    }

    @Test fun missingKeyIsExplicitAndDoesNotCreateReplacement() {
        val keys = DeviceKeyStore(context, "openauth.missing." + UUID.randomUUID())
        val error = assertThrows(VaultException::class.java) { keys.prepare(false) }
        assertEquals(VaultFailure.KEY_UNAVAILABLE, error.reason)
    }

    @Test fun insecureDeviceCannotPrepareVaultKey() {
        assumeTrue(!context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        val error = assertThrows(VaultException::class.java) {
            DeviceKeyStore(context, "openauth.insecure." + UUID.randomUUID()).prepare(true)
        }
        assertEquals(VaultFailure.DEVICE_NOT_SECURE, error.reason)
    }

    @Test fun interruptedAtomicWriteRecoversPreviousEncryptedBytes() {
        val file = File(context.noBackupFilesDir, "atomic-test-" + UUID.randomUUID())
        try {
            LocalVaultFile(file).writeAtomically(byteArrayOf(1, 2, 3))
            val failing = LocalVaultFile(file) { stream, bytes ->
                stream.write(bytes, 0, 1)
                throw IOException("synthetic disk failure")
            }
            assertThrows(IOException::class.java) { failing.writeAtomically(byteArrayOf(4, 5, 6)) }
            assertArrayEquals(byteArrayOf(1, 2, 3), LocalVaultFile(file).read())
        } finally { file.delete(); File(file.path + ".bak").delete(); File(file.path + ".new").delete() }
    }
}
