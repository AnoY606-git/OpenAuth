package dev.openauth.core.model

/** Хранит собственную копию секрета и исключает его содержимое из диагностики. */
class OtpSecret private constructor(private val bytes: ByteArray) {
    fun copyBytes(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean =
        this === other || other is OtpSecret && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "OtpSecret([REDACTED])"

    companion object {
        fun fromBytes(bytes: ByteArray): OtpSecret {
            require(bytes.size in 10..128) { "Размер секрета должен быть от 10 до 128 байт" }
            return OtpSecret(bytes.copyOf())
        }
    }
}
