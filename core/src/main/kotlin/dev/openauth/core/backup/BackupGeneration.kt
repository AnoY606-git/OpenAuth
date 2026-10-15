package dev.openauth.core.backup

/** Состояние активного поколения; пароль и KEK в нём отсутствуют. */
class BackupGeneration internal constructor(header: ByteArray, wrapper: ByteArray, key: ByteArray) : AutoCloseable {
    private val header = header.copyOf()
    private val wrapper = wrapper.copyOf()
    private val key = key.copyOf()
    private var closed = false

    /** Возвращает открытый DEK: сохранять только внутри аутентифицированного шифротекста vault. */
    @Synchronized fun exportLocalState(): ByteArray {
        checkOpen()
        return LOCAL_MAGIC + header + wrapper + key
    }

    @Synchronized internal fun <T> withMaterial(action: (ByteArray, ByteArray, ByteArray) -> T): T {
        checkOpen()
        val keyCopy = key.copyOf()
        return try { action(header.copyOf(), wrapper.copyOf(), keyCopy) } finally { keyCopy.fill(0) }
    }

    private fun checkOpen() {
        if (closed) throw BackupException(BackupFailure.CLOSED)
    }

    @Synchronized override fun close() {
        key.fill(0)
        closed = true
    }

    override fun toString(): String = "BackupGeneration([REDACTED])"

    companion object {
        private val LOCAL_MAGIC = "OALOCAL1".toByteArray(Charsets.US_ASCII)

        /** bytes должны быть получены из уже аутентифицированного локального vault. */
        fun importLocalState(bytes: ByteArray): BackupGeneration {
            if (bytes.size != 160 || !bytes.copyOfRange(0, 8).contentEquals(LOCAL_MAGIC)) {
                throw BackupException(BackupFailure.INVALID_FORMAT)
            }
            val header = bytes.copyOfRange(8, 80)
            BackupCodec.validateHeader(header)
            val key = bytes.copyOfRange(128, 160)
            return try { BackupGeneration(header, bytes.copyOfRange(80, 128), key) } finally { key.fill(0) }
        }
    }
}
