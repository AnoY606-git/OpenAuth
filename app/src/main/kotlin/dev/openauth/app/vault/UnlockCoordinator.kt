package dev.openauth.app.vault

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Успех диалога только разрешает попытку unwrap: доступ всё равно проверяет Android Keystore. */
class UnlockCoordinator(
    private val activity: FragmentActivity,
    private val repository: VaultRepository,
    private val keys: VaultKeyProtector,
) {
    private var activePrompt: BiometricPrompt? = null

    suspend fun unlock(initialize: Boolean = false) {
        withContext(Dispatchers.IO) { if (initialize) keys.prepare(true) else keys.prepareForUnlock() }
        withContext(Dispatchers.Main.immediate) {
            if (activePrompt != null) throw VaultException(VaultFailure.AUTHENTICATION_REQUIRED)
            authenticate()
            if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw VaultException(VaultFailure.LOCKED)
            if (initialize) repository.initialize() else repository.unlock()
        }
    }

    suspend fun authenticateRecovery() = withContext(Dispatchers.Main.immediate) {
        if (activePrompt != null) throw VaultException(VaultFailure.AUTHENTICATION_REQUIRED)
        authenticate()
        if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) throw VaultException(VaultFailure.LOCKED)
    }

    private suspend fun authenticate(): Unit = suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                activePrompt = null
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                activePrompt = null
                if (continuation.isActive) continuation.resumeWithException(VaultException(VaultFailure.AUTHENTICATION_CANCELLED))
            }
        })
        activePrompt = prompt
        continuation.invokeOnCancellation {
            activity.runOnUiThread { if (activePrompt === prompt) { activePrompt = null; prompt.cancelAuthentication() } }
        }
        val russian = activity.resources.configuration.locales[0].language == "ru"
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(if (russian) "Открыть Open Auth" else "Unlock Open Auth")
            .setSubtitle(if (russian) "Подтвердите личность для доступа к кодам" else "Authenticate to access your codes")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build())
    }
}
