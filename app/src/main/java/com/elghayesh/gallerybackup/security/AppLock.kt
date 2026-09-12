package com.elghayesh.gallerybackup.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Biometric (fingerprint/face) OR the device's own PIN/pattern/password -- whichever the device
 * already has set up. MediaHub never sees or stores a credential itself; it defers entirely to
 * the OS's own trusted lock-screen check, the same one guarding the rest of the device. */
private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** True if the device has some way to satisfy [ALLOWED_AUTHENTICATORS] -- biometrics enrolled, or
 * a screen lock (PIN/pattern/password) set. If neither, there'd be no way to ever unlock again, so
 * app lock/folder lock/hidden-item lock should all refuse to turn on. */
fun canUseAppLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Prompts for biometric or device-credential authentication, suspending until the user succeeds,
 * cancels, or the system reports an error (all three simply resolve as a plain true/false -- a
 * caller that needs to distinguish "wrong finger, try again" doesn't need to, since the system
 * prompt itself already handles retries internally before giving up).
 */
suspend fun requestAppLockAuthentication(
    activity: FragmentActivity,
    title: String,
    subtitle: String? = null,
): Boolean = suspendCancellableCoroutine { continuation ->
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .apply { if (subtitle != null) setSubtitle(subtitle) }
        .setAllowedAuthenticators(ALLOWED_AUTHENTICATORS)
        .build()
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (continuation.isActive) continuation.resume(false)
            }

            // A single failed attempt (e.g. one wrong fingerprint) -- the system prompt stays open
            // for another try on its own, so this deliberately doesn't resolve the coroutine.
            override fun onAuthenticationFailed() = Unit
        },
    )
    continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    prompt.authenticate(promptInfo)
}

/** Full-screen gate shown in place of the app's real content while app lock is on and this
 * session hasn't been authenticated yet -- auto-prompts once as soon as it appears, with a button
 * to retry if that prompt is dismissed/cancelled instead of completed. */
@Composable
fun AppLockGateScreen(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val scope = rememberCoroutineScope()

    suspend fun tryUnlock() {
        if (activity != null && requestAppLockAuthentication(activity, "Unlock MediaHub")) {
            onUnlocked()
        }
    }

    LaunchedEffect(Unit) { tryUnlock() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("MediaHub is locked", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { scope.launch { tryUnlock() } }) { Text("Unlock") }
        }
    }
}
