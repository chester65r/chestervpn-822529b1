package com.example.advancedtunnel.firebase

import android.content.Context
import android.util.Log
import com.example.advancedtunnel.R
import com.example.advancedtunnel.model.RelayServer
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType {
    CREATE, UPDATE, DELETE, LIST, GET, WRITE
}

fun handleFirestoreError(
    exception: Throwable,
    operationType: OperationType,
    path: String?
): String {
    val auth = try {
        FirebaseAuth.getInstance()
    } catch (_: Exception) {
        null
    }
    val currentUser = auth?.currentUser

    val providerInfoArray = JSONArray()
    currentUser?.providerData?.forEach { profile ->
        val providerObj = JSONObject().apply {
            put("providerId", profile.providerId)
            put("displayName", profile.displayName)
            put("email", profile.email)
            put("photoUrl", profile.photoUrl?.toString())
        }
        providerInfoArray.put(providerObj)
    }

    val authInfoObj = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("isAnonymous", currentUser?.isAnonymous)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", providerInfoArray)
    }

    val errInfoObj = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.name.lowercase())
        put("path", path)
        put("authInfo", authInfoObj)
    }

    val jsonString = errInfoObj.toString()
    Log.e("FirestoreError", jsonString, exception)
    return jsonString
}

class FirestoreManager(
    private val firestore: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    fun observeRelayServers(userId: String): Flow<List<RelayServer>> = callbackFlow {
        val path = "users/$userId/relay_servers"
        val collection = firestore.collection("users").document(userId).collection("relay_servers")
        val listener = collection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                handleFirestoreError(error, OperationType.LIST, path)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val servers = snapshot.documents.mapNotNull { doc ->
                    try {
                        val rawCreatedAt = doc.get("createdAt") ?: doc.get("updatedAt")
                        val _createdMs = when (rawCreatedAt) {
                            is Timestamp -> rawCreatedAt.toDate().time
                            is Number -> rawCreatedAt.toLong()
                            else -> System.currentTimeMillis()
                        }
                        RelayServer(
                            id = doc.id,
                            name = doc.getString("name") ?: "",
                            host = doc.getString("host") ?: "",
                            port = doc.getLong("port")?.toInt() ?: 443,
                            transport = doc.getString("transport") ?: "WebSocket",
                            notes = doc.getString("notes") ?: ""
                        )
                    } catch (_: Exception) {
                        null
                    }
                }
                trySend(servers)
            }
        }
        awaitClose { listener.remove() }
    }

    suspend fun saveRelayServer(userId: String, server: RelayServer): Result<Unit> {
        val path = "users/$userId/relay_servers/${server.id}"
        return try {
            val doc = firestore.collection("users").document(userId).collection("relay_servers").document(server.id)
            val data = mapOf(
                "name" to server.name,
                "host" to server.host,
                "port" to server.port,
                "transport" to server.transport,
                "notes" to server.notes,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            doc.set(data).await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteRelayServer(userId: String, serverId: String): Result<Unit> {
        val path = "users/$userId/relay_servers/$serverId"
        return try {
            firestore.collection("users").document(userId).collection("relay_servers").document(serverId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, path)
            Result.failure(e)
        }
    }
}
