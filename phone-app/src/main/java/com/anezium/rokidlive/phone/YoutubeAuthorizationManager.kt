package com.anezium.rokidlive.phone

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope

class YoutubeAuthorizationManager(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val onAuthorized: (email: String) -> Unit,
    private val onError: (String, Throwable?) -> Unit
) {
    private val authorizationClient = Identity.getAuthorizationClient(activity)
    private var accessToken: String? = null
    private var accountEmail: String = ""
    private var pendingTokenHandler: ((String) -> Unit)? = null

    fun authorize(onToken: (String) -> Unit = {}) {
        pendingTokenHandler = onToken
        onStatus("Opening YouTube authorization...")
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(YOUTUBE_SCOPE)))
            .build()
        authorizationClient.authorize(request)
            .addOnSuccessListener(activity) { result -> handleAuthorizationResult(result) }
            .addOnFailureListener(activity) { throwable -> onError("YouTube authorization failed", throwable) }
    }

    fun handleActivityResult(data: Intent?) {
        if (data == null) {
            onError("YouTube authorization cancelled", null)
            return
        }
        runCatching {
            authorizationClient.getAuthorizationResultFromIntent(data)
        }.onSuccess { result ->
            handleAuthorizationResult(result)
        }.onFailure { throwable ->
            onError("YouTube authorization failed", throwable)
        }
    }

    fun requireToken(onToken: (String) -> Unit) {
        val token = accessToken
        if (token != null) {
            onToken(token)
            return
        }
        authorize(onToken)
    }

    private fun handleAuthorizationResult(result: AuthorizationResult) {
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent
            if (pendingIntent == null) {
                onError("YouTube authorization needs user action but no intent was returned", null)
                return
            }
            runCatching {
                activity.startIntentSenderForResult(
                    pendingIntent.intentSender,
                    REQUEST_CODE,
                    null,
                    0,
                    0,
                    0
                )
            }.onFailure { throwable ->
                val wrapped = throwable as? IntentSender.SendIntentException ?: throwable
                onError("Could not open YouTube authorization", wrapped)
            }
            return
        }

        val token = result.accessToken
        if (token.isNullOrBlank()) {
            onError("YouTube authorization returned no access token", null)
            return
        }
        accessToken = token
        accountEmail = result.toGoogleSignInAccount()?.email.orEmpty()
        onAuthorized(accountEmail)
        onStatus(if (accountEmail.isBlank()) "YouTube connected" else "YouTube connected: $accountEmail")
        pendingTokenHandler?.invoke(token)
        pendingTokenHandler = null
    }

    fun clearCachedToken() {
        accessToken = null
        pendingTokenHandler = null
    }

    fun disconnectLocal() {
        accessToken = null
        accountEmail = ""
        pendingTokenHandler = null
    }

    fun disconnect() {
        if (accountEmail.isBlank()) {
            disconnectLocal()
            onStatus("YouTube disconnected")
            return
        }
        disconnectLocal()
        val request = RevokeAccessRequest.builder()
            .setScopes(listOf(Scope(YOUTUBE_SCOPE)))
            .build()
        authorizationClient.revokeAccess(request)
            .addOnSuccessListener(activity) { onStatus("YouTube disconnected") }
            .addOnFailureListener(activity) { throwable -> onError("YouTube disconnect failed", throwable) }
    }

    companion object {
        const val REQUEST_CODE = 6101
        const val YOUTUBE_SCOPE = "https://www.googleapis.com/auth/youtube"
    }
}
