package com.example.advancedtunnel.firebase

import android.content.Context
import android.util.Log
import com.example.advancedtunnel.R
import com.example.advancedtunnel.model.RelayServer
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

private const val TAG = "FirestoreManager"

class FirestoreManager(context: Context) {

    private val firestore: FirebaseFirestore by lazy {
        val dbId = try {
            context.getString(R.string.firestore_database_id)
        } catch (_: Exception) {
            null
        }
        if (!dbId.isNullOrBlank()) {
            FirebaseFirestore.getInstance(FirebaseApp.getInstance(), dbId)
        } else {
            FirebaseFirestore.getInstance()
        }
    }

    fun observeRelayServers(userId: String): Flow<List<RelayServer>> = callbackFlow {
        val collection = firestore.collection("users").document(userId).collection("relay_servers")
        val listener = collection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Error listening for relay servers", error)
                return@addSnapshotListener
            }
            if (snapshot != null) {
                val servers = snapshot.documents.mapNotNull { doc ->
                    try {
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
        return try {
            val doc = firestore.collection("users").document(userId).collection("relay_servers").document(server.id)
            val data = mapOf(
                "name" to server.name,
                "host" to server.host,
                "port" to server.port,
                "transport" to server.transport,
                "notes" to server.notes,
                "updatedAt" to System.currentTimeMillis()
            )
            doc.set(data).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save relay server", e)
            Result.failure(e)
        }
    }

    suspend fun deleteRelayServer(userId: String, serverId: String): Result<Unit> {
        return try {
            firestore.collection("users").document(userId).collection("relay_servers").document(serverId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete relay server", e)
            Result.failure(e)
        }
    }
}
