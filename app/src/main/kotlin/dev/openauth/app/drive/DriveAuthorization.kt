package dev.openauth.app.drive

import android.accounts.Account
import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface DriveAccess {
    class Granted(val accountName: String, val token: String) : DriveAccess
    class NeedsConsent(val resolution: PendingIntent? = null) : DriveAccess
    data object Unavailable : DriveAccess
    data object Denied : DriveAccess
    class Failure(val reason: DriveOAuthFailure) : DriveAccess
}
enum class DriveOAuthFailure { RETRYABLE, CANCELLED, DENIED, CONFIGURATION }

/** Внешние сообщения SDK не пересекают OAuth-границу: сохраняется только безопасная категория. */
internal fun classifyDriveOAuthFailure(statusCode: Int?): DriveOAuthFailure = when (statusCode) {
    CommonStatusCodes.NETWORK_ERROR, CommonStatusCodes.TIMEOUT, CommonStatusCodes.INTERNAL_ERROR -> DriveOAuthFailure.RETRYABLE
    CommonStatusCodes.CANCELED -> DriveOAuthFailure.CANCELLED
    CommonStatusCodes.DEVELOPER_ERROR -> DriveOAuthFailure.CONFIGURATION
    else -> DriveOAuthFailure.DENIED
}
private class DriveOAuthException(val reason: DriveOAuthFailure) : Exception(reason.name)
private fun safeOAuthFailure(failure: Exception) = DriveOAuthException(
    if (failure is java.io.IOException) DriveOAuthFailure.RETRYABLE
    else classifyDriveOAuthFailure((failure as? ApiException)?.statusCode),
)

fun interface DriveTokenProvider {
    suspend fun authorize(accountName: String): DriveAccess
    /** Только отклонённый token текущей попытки; без отзыва scopes или выбора другого аккаунта. */
    suspend fun invalidate(accountName: String, rejectedToken: String) { }
}

/** Google выдаёт токен на конкретный Account; приложение не сохраняет токены или serverAuthCode. */
class DriveAuthorization(context: Context) : DriveTokenProvider {
    private val context = context.applicationContext
    fun available(): Boolean = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    fun accountPicker(): Intent = AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)
    fun selectedAccount(data: Intent?): String? {
        if (data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE) != "com.google") return null
        return data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() && it.length <= 320 && it.none(Char::isISOControl) }
    }
    @Suppress("DEPRECATION")
    override suspend fun authorize(accountName: String): DriveAccess {
        if (!available()) return DriveAccess.Unavailable
        if (accountName.isBlank() || accountName.length > 320 || accountName.any(Char::isISOControl)) return DriveAccess.Denied
        return try {
            val request = AuthorizationRequest.builder().setAccount(Account(accountName, "com.google"))
                .setRequestedScopes(listOf(Scope(SCOPE))).setOptOutIncludingGrantedScopes(true).build()
            val result = suspendCancellableCoroutine<AuthorizationResult> { continuation ->
                Identity.getAuthorizationClient(context).authorize(request)
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(safeOAuthFailure(it)) }
                    .addOnCanceledListener { continuation.cancel() }
            }
            if (result.hasResolution()) return DriveAccess.NeedsConsent(result.pendingIntent)
            val token = result.accessToken
            val returnedAccount = result.toGoogleSignInAccount()?.account?.name
            // В API нет обязательного account в response. Привязку обеспечивает setAccount без SELECT_ACCOUNT.
            if (returnedAccount != null && returnedAccount != accountName) return DriveAccess.Denied
            if (SCOPE !in result.grantedScopes || token.isNullOrBlank()) DriveAccess.Denied
            else DriveAccess.Granted(accountName, token)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DriveOAuthException) { DriveAccess.Failure(failure.reason) }
        catch (failure: Exception) { DriveAccess.Failure(safeOAuthFailure(failure).reason) }
    }
    override suspend fun invalidate(accountName: String, rejectedToken: String) {
        if (accountName.isBlank() || rejectedToken.isBlank()) throw DriveException(DriveFailure.AUTH_REQUIRED)
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                Identity.getAuthorizationClient(context).clearToken(ClearTokenRequest.builder().setToken(rejectedToken).build())
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(safeOAuthFailure(it)) }
                    .addOnCanceledListener { continuation.cancel() }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val reason = (failure as? DriveOAuthException)?.reason ?: safeOAuthFailure(failure).reason
            when (reason) {
                DriveOAuthFailure.RETRYABLE -> throw DriveException(DriveFailure.RETRYABLE)
                DriveOAuthFailure.CANCELLED -> throw CancellationException("OAuth token invalidation cancelled")
                DriveOAuthFailure.CONFIGURATION -> throw DriveConnectionException(DriveConnectionFailure.AUTHORIZATION)
                DriveOAuthFailure.DENIED -> throw DriveException(DriveFailure.AUTH_REQUIRED)
            }
        }
    }
    companion object { const val SCOPE = "https://www.googleapis.com/auth/drive.appdata" }
}
