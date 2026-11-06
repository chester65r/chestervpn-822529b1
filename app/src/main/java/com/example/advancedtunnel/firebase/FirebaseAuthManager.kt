package com.example.advancedtunnel.firebase

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
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
private const val WEB_CLIENT_ID = "1075657195872-n1255nbc69gf1s9qkf0mkh2f6ntk26jm.apps.googleusercontent.com"
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

    suspend fun signInWithGoogle(): Result<FirebaseUser> {
        _isLoading.value = true
        return try {
            val signInOption = GetSignInWithGoogleOption.Builder(WEB_CLIENT_ID)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val result = credentialManager.getCredential(context, request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken
                val authCredential = GoogleAuthProvider.getCredential(idToken, null)
                val authResult = auth.signInWithCredential(authCredential).await()
                val user = authResult.user ?: throw Exception("Firebase user is null after sign in.")
                _currentUser.value = user
                Result.success(user)
            } else {
                throw Exception("Unexpected credential type: ${credential.type}")
            }
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

    /**
     * Signs the user in with their Microsoft account through Firebase's federated OAuth
     * flow. The provider opens the Microsoft consent screen and returns the signed-in
     * user directly, so no activity-result plumbing is required. Microsoft must be
     * enabled in the Firebase console and the Firebase redirect URI registered with the
     * Microsoft app registration before this can succeed.
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

    fun signOut() {
        auth.signOut()
        _currentUser.value = null
    }
}
