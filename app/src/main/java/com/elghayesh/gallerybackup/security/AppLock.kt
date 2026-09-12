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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.elghayesh.gallerybackup.data.settings.LockMethod
import com.elghayesh.gallerybackup.ui.gallery.GalleryViewModel
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/** Biometric (fingerprint/face) OR the device's own PIN/pattern/password -- whichever the device
 * already has set up. Only relevant for [LockMethod.BIOMETRIC]; MediaHub never sees or stores
 * this credential itself, deferring entirely to the OS's own trusted lock-screen check. */
private const val ALLOWED_AUTHENTICATORS =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** True if the device has some way to satisfy [ALLOWED_AUTHENTICATORS] -- biometrics enrolled, or
 * a screen lock (PIN/pattern/password) set. If neither, [LockMethod.BIOMETRIC] would have no way
 * to ever unlock again, so enabling app lock/folder lock/hidden-item lock under it should refuse. */
fun canUseAppLock(context: Context): Boolean =
    BiometricManager.from(context).canAuthenticate(ALLOWED_AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

/**
 * Prompts for biometric or device-credential authentication, suspending until the user succeeds,
 * cancels, or the system reports an error (all three simply resolve as a plain true/false -- a
 * caller that needs to distinguish "wrong finger, try again" doesn't need to, since the system
 * prompt itself already handles retries internally before giving up).
 */
suspend fun requestBiometricAuthentication(
    activity: FragmentActivity,
    title: String,
): Boolean = suspendCancellableCoroutine { continuation ->
    val promptInfo = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
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

/**
 * Mounted once near the app's root (alongside the nav host, so it renders no matter which screen
 * is on top). Watches [GalleryViewModel.pendingAuth] -- whenever any part of the app calls
 * [GalleryViewModel.requestAuth], this shows whichever credential prompt [LockMethod] the user has
 * chosen (a biometric/device-credential system prompt, or MediaHub's own password dialog) and
 * resolves that call via [GalleryViewModel.resolveAuth]. Centralizing the prompt here, instead of
 * each call site invoking biometrics directly, is what lets every lock (app-wide, per-folder,
 * hidden items) honor the user's actual choice of credential.
 */
@Composable
fun AuthPromptHost(viewModel: GalleryViewModel) {
    val pending by viewModel.pendingAuth.collectAsState()
    val request = pending ?: return
    val lockMethod by viewModel.lockMethod.collectAsState()

    when (lockMethod) {
        LockMethod.BIOMETRIC -> {
            val context = LocalContext.current
            val activity = context as? FragmentActivity
            LaunchedEffect(request) {
                val ok = activity != null && requestBiometricAuthentication(activity, request.title)
                viewModel.resolveAuth(ok)
            }
        }
        LockMethod.PASSWORD -> {
            PasswordPromptDialog(
                title = request.title,
                onVerify = { password -> viewModel.verifyLockPassword(password) },
                onResult = { success -> viewModel.resolveAuth(success) },
            )
        }
    }
}

/** A password-entry dialog for [LockMethod.PASSWORD] -- unlike the biometric system prompt, a
 * wrong entry here doesn't automatically get another try from the OS, so this manages its own
 * retry loop: a failed [onVerify] shows an inline error and clears the field rather than closing,
 * only calling [onResult] on an explicit success or Cancel. */
@Composable
private fun PasswordPromptDialog(title: String, onVerify: suspend (String) -> Boolean, onResult: (Boolean) -> Unit) {
    var password by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }
    var isChecking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (isChecking) return
        isChecking = true
        scope.launch {
            val ok = onVerify(password)
            isChecking = false
            if (ok) {
                onResult(true)
            } else {
                showError = true
                password = ""
            }
        }
    }

    AlertDialog(
        onDismissRequest = { onResult(false) },
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; showError = false },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = showError,
                )
                if (showError) {
                    Text(
                        "Incorrect password",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty() && !isChecking, onClick = { submit() }) { Text("Unlock") }
        },
        dismissButton = {
            TextButton(onClick = { onResult(false) }) { Text("Cancel") }
        },
    )
}

/** Full-screen gate shown in place of the app's real content while app lock is on and this
 * session hasn't been authenticated yet -- auto-prompts once as soon as it appears (via
 * [GalleryViewModel.requestAuth], so it honors whichever [LockMethod] is chosen), with a button to
 * retry if that prompt is dismissed/cancelled instead of completed. */
@Composable
fun AppLockGateScreen(viewModel: GalleryViewModel, onUnlocked: () -> Unit) {
    val scope = rememberCoroutineScope()

    fun tryUnlock() {
        scope.launch { if (viewModel.requestAuth("Unlock MediaHub")) onUnlocked() }
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
            Button(onClick = { tryUnlock() }) { Text("Unlock") }
        }
    }
}
