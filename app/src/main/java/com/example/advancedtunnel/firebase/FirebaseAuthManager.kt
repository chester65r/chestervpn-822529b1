package com.example.advancedtunnel.firebase

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.advancedtunnel.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

private const val TAG = "FirebaseAuthManager"
private const val MICROSOFT_PROVIDER_ID = "microsoft.com"
private val MICROSOFT_SCOPES = listOf("openid", "profile", "email")

class FirebaseAuthManager(private val context: Context) {

    private val auth = FirebaseAuth.getInstance()
    private val credentialManager = CredentialManager.create(context)

    private val _currentUser = MutableStateFlow<FirebaseUser?>(auth.currentUser)
    val currentUser: StateFlow<FirebaseUser?> = _currentUser.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        auth.addAuthStateListener { firebaseAuth ->
            _currentUser.value = firebaseAuth.currentUser
        }
    }

    /**
     * Attempts silent auto-sign-in for returning users using GetGoogleIdOption.
     */
    suspend fun attemptAutoSignIn(activityContext: Context = context): Result<FirebaseUser> {
        if (auth.currentUser != null) {
            return Result.success(auth.currentUser!!)
        }
        return try {
            val clientId = activityContext.getString(R.string.default_web_client_id)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(clientId)
                .setAutoSelectEnabled(true)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(activityContext, request)
            handleSignInResult(result.credential)
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Auto sign-in cancelled by user", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.d(TAG, "No authorized account available for silent sign-in: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Explicit interactive Google Sign-In button flow using GetSignInWithGoogleOption
     * and the project's R.string.default_web_client_id generated from google-services.json.
     */
    suspend fun signInWithGoogle(activityContext: Context = context): Result<FirebaseUser> {
        _isLoading.value = true
        return try {
            val clientId = activityContext.getString(R.string.default_web_client_id)
            val signInWithGoogleOption = GetSignInWithGoogleOption.Builder(clientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInWithGoogleOption)
                .build()

            val result = credentialManager.getCredential(activityContext, request)
            handleSignInResult(result.credential)
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "User cancelled Google Sign-In flow", e)
            Result.failure(Exception("Sign-in was cancelled."))
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    private suspend fun handleSignInResult(credential: androidx.credentials.Credential): Result<FirebaseUser> {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val idToken = googleIdTokenCredential.idToken
            val authCredential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(authCredential).await()
            val user = authResult.user ?: throw Exception("Firebase user is null after sign in.")
            _currentUser.value = user
            return Result.success(user)
        } else {
            throw Exception("Unexpected credential type: ${credential.type}")
        }
    }

    /**
     * Signs the user in with their Microsoft account through Firebase's federated OAuth
     * flow.
     */
    suspend fun signInWithMicrosoft(activity: Activity): Result<FirebaseUser> {
        _isLoading.value = true
        return try {
            val provider = OAuthProvider.newBuilder(MICROSOFT_PROVIDER_ID)
                .setScopes(MICROSOFT_SCOPES)
                .build()

            val authResult = auth.startActivityForSignInWithProvider(activity, provider).await()
            val user = authResult.user
                ?: throw Exception("Firebase user is null after Microsoft sign in.")
            _currentUser.value = user
            Result.success(user)
        } catch (e: Exception) {
            Log.e(TAG, "Microsoft Sign-In failed", e)
            Result.failure(e)
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun signOut() {
        auth.signOut()
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear credential manager state", e)
        }
        _currentUser.value = null
    }
}
