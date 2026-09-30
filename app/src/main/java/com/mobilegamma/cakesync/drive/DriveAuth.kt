package com.mobilegamma.cakesync.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.mobilegamma.cakesync.data.Settings
import kotlinx.coroutines.tasks.await

/**
 * Gets Google Drive access tokens with the narrow `drive.file` scope: the app can only
 * see files and folders it created itself, never the rest of the user's Drive.
 */
class DriveAuth(private val context: Context) {

    sealed interface Outcome {
        data class Token(val accessToken: String) : Outcome
        /** The user must approve access; launch this with an IntentSender launcher. */
        data class NeedsConsent(val pendingIntent: PendingIntent) : Outcome
    }

    /** Uses the account chosen in Settings, if any. */
    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        .apply { Settings(context).driveAccount?.let { setAccount(Account(it, "com.google")) } }
        .build()

    suspend fun authorize(): Outcome = toOutcome(
        Identity.getAuthorizationClient(context).authorize(request()).await()
    )

    /** Call with the data Intent returned after the consent screen closes. */
    fun resultFromConsent(data: Intent?): Outcome =
        toOutcome(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data))

    private fun toOutcome(result: AuthorizationResult): Outcome {
        val pending = result.pendingIntent
        val token = result.accessToken
        return when {
            result.hasResolution() && pending != null -> Outcome.NeedsConsent(pending)
            token != null -> Outcome.Token(token)
            else -> throw IllegalStateException("Google authorization returned no token")
        }
    }

    companion object {
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
