package dev.openauth.core.backup

import org.junit.Assert.*
import org.junit.Test

class BackupCredentialTest {
    @Test fun passwordAndPinBoundsAreEnforced() {
        for (value in listOf("short", "😀".repeat(11), "é".repeat(513), "abcdefghijkl\uD800")) {
            assertEquals(BackupFailure.INVALID_CREDENTIAL, assertThrows(BackupException::class.java) {
                BackupCredential.password(value.toCharArray())
            }.reason)
        }
        BackupCredential.password("😀".repeat(12).toCharArray()).close()
        BackupCredential.password("é".repeat(512).toCharArray()).close()
        for (value in listOf("1234567", "1".repeat(129), "１２３４５６７８", "1234567 ")) {
            assertThrows(BackupException::class.java) { BackupCredential.pin(value.toCharArray()) }
        }
        BackupCredential.pin("00001234".toCharArray()).close()
        BackupCredential.pin("1".repeat(128).toCharArray()).close()
    }

    @Test fun closeIsIdempotentAndPreventsFurtherUse() {
        val credential = BackupCredential.password("long password!".toCharArray())
        credential.close()
        credential.close()
        assertEquals(BackupFailure.CLOSED, assertThrows(BackupException::class.java) {
            BackupCodec().createGeneration(credential)
        }.reason)
        assertFalse(credential.toString().contains("long password"))
    }
}
