package dev.openauth.core.model

/** Параметры, определяющие одну последовательность OTP независимо от метаданных записи. */
@ConsistentCopyVisibility
data class OtpGeneratorIdentity private constructor(
    val type: OtpType,
    val secret: OtpSecret,
    val algorithm: HashAlgorithm,
    val digits: Int,
    val periodSeconds: Int,
) {
    companion object {
        fun of(entry: OtpEntry): OtpGeneratorIdentity = OtpGeneratorIdentity(
            entry.type, entry.secret, entry.algorithm, entry.digits,
            // HOTP зависит от счётчика; изменение неиспользуемого периода не создаёт новый генератор.
            if (entry.type == OtpType.HOTP) 0 else entry.periodSeconds,
        )
    }
}
