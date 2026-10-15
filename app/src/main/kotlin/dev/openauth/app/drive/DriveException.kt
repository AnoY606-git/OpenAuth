package dev.openauth.app.drive

/** В исключениях нет HTTP body, токена, URL или исходного сетевого исключения. */
class DriveException(val failure: DriveFailure) : Exception(failure.name.lowercase())

enum class DriveFailure {
    AUTH_REQUIRED, QUOTA, RATE_LIMITED, RETRYABLE, INVALID_RESPONSE, LIMIT_EXCEEDED,
    NETWORK, PERMISSION_DENIED, NOT_FOUND, CONFLICT, AMBIGUOUS_UPLOAD, INVALID_REQUEST,
}
