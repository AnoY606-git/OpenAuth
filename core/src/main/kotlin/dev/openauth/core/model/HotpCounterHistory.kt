package dev.openauth.core.model

import java.security.MessageDigest

/** Only an encrypted, domain-separated fingerprint is retained after an account is deleted. */
data class HotpCounterFloor(val fingerprint: String, val nextCounter: Long) {
    init {
        require(fingerprint.length == 64 && fingerprint.all { it in '0'..'9' || it in 'a'..'f' })
        require(nextCounter > 0)
    }

    companion object {
        private val DOMAIN = "OpenAuth/HOTP/counter-floor/v1\u0000".toByteArray(Charsets.US_ASCII)
        private const val HEX = "0123456789abcdef"

        /** Digits, period, labels and the current card type must not reset the HOTP sequence. */
        fun fingerprint(secret: OtpSecret): String {
            val bytes = secret.copyBytes()
            return try {
                MessageDigest.getInstance("SHA-256").run {
                    update(DOMAIN)
                    val hash = digest(bytes)
                    CharArray(hash.size * 2) { index ->
                        val value = hash[index / 2].toInt() and 255
                        HEX[if (index % 2 == 0) value ushr 4 else value and 15]
                    }.concatToString()
                }
            } finally { bytes.fill(0) }
        }
    }
}

/** Preserves counter floors even when no corresponding HOTP card survives a mutation/restore. */
object HotpCounterHistory {
    fun maxima(vararg snapshots: VaultSnapshot): Map<String, Long> {
        val result = HashMap<String, Long>()
        fun retain(fingerprint: String, counter: Long) {
            if (counter == 0L) return
            result[fingerprint] = maxOf(result[fingerprint] ?: 0L, counter)
            require(result.size <= VaultSnapshot.MAX_HOTP_COUNTER_FLOORS) { "История HOTP заполнена" }
        }
        for (snapshot in snapshots) {
            snapshot.hotpCounterFloors.forEach { retain(it.fingerprint, it.nextCounter) }
            snapshot.entries.filter { it.type == OtpType.HOTP }.forEach {
                retain(HotpCounterFloor.fingerprint(it.secret), it.nextCounter)
            }
        }
        return result
    }

    fun preserve(snapshot: VaultSnapshot, vararg previous: VaultSnapshot): VaultSnapshot {
        val maxima = maxima(snapshot, *previous)
        val floors = maxima.entries.sortedBy { it.key }.map { HotpCounterFloor(it.key, it.value) }
        val entries = snapshot.entries.map { entry ->
            if (entry.type != OtpType.HOTP) entry else {
                val counter = maxOf(entry.nextCounter, maxima[HotpCounterFloor.fingerprint(entry.secret)] ?: 0L)
                if (counter == entry.nextCounter) entry else entry.copy(nextCounter = counter)
            }
        }
        val schema = if (floors.isEmpty()) snapshot.schemaVersion else 2
        if (schema == snapshot.schemaVersion && entries == snapshot.entries && floors == snapshot.hotpCounterFloors) return snapshot
        return VaultSnapshot(schema, snapshot.revision, entries, snapshot.groups, floors)
    }
}
