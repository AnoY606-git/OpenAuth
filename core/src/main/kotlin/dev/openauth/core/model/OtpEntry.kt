package dev.openauth.core.model

import java.util.UUID

enum class OtpType { TOTP, HOTP }

enum class HashAlgorithm { SHA1, SHA256, SHA512 }

data class OtpEntry(
    val id: UUID,
    val issuer: String,
    val accountName: String,
    val secret: OtpSecret,
    val type: OtpType,
    val algorithm: HashAlgorithm,
    val digits: Int,
    val periodSeconds: Int,
    val nextCounter: Long,
    val groupId: UUID?,
    val favorite: Boolean,
    val sortIndex: Long,
) {
    init {
        require(issuer.length <= 256 && issuer.none { it.isISOControl() }) {
            "Название сервиса должно содержать не больше 256 символов без управляющих символов"
        }
        require(accountName.isNotBlank() && accountName.length <= 256 && accountName.none { it.isISOControl() }) {
            "Название аккаунта должно содержать от 1 до 256 символов без управляющих символов"
        }
        require(digits == 6 || digits == 8) { "OTP должен содержать 6 или 8 цифр" }
        require(periodSeconds in 15..120) { "Период OTP должен быть от 15 до 120 секунд" }
        require(type != OtpType.HOTP || algorithm == HashAlgorithm.SHA1) { "HOTP поддерживает только SHA-1" }
        require(nextCounter >= 0) { "Счётчик OTP не может быть отрицательным" }
    }
}
