package dev.openauth.app.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryStorageTest {
    @Test fun partialWritePreservesOriginalAndSuccessfulReplacementKeepsPrivateCiphertext() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "recovery-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val file = File(directory, "vault.oav")
            val original = ByteArray(200) { 41 }; val replacement = ByteArray(220) { 73 }
            LocalVaultFile(file).writeAtomically(original)
            val failing = LocalVaultFile(file) { stream, bytes -> stream.write(bytes, 0, 12); throw IOException("Synthetic failure") }
            assertThrows(IOException::class.java) { failing.replacePreservingOriginal(original, replacement) }
            assertArrayEquals(original, LocalVaultFile(file).read())
            assertTrue(File(directory, "vault-recovery").listFiles().orEmpty().isEmpty())
            LocalVaultFile(file).replacePreservingOriginal(original, replacement)
            assertArrayEquals(replacement, LocalVaultFile(file).read())
            assertArrayEquals(original, File(directory, "vault-recovery").listFiles()!!.single().readBytes())
            assertThrows(VaultException::class.java) { LocalVaultFile(file).replacePreservingOriginal(original, ByteArray(100)) }
            assertArrayEquals(replacement, LocalVaultFile(file).read())
        } finally { directory.deleteRecursively() }
    }
}
