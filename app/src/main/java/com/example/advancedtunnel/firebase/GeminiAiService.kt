package com.example.advancedtunnel.firebase

import android.util.Log
import com.example.advancedtunnel.model.TunnelProfile
import com.example.advancedtunnel.model.TunnelStatus
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

private const val TAG = "GeminiAiService"

class GeminiAiService {

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.4f
                maxOutputTokens = 800
            }
        )
    }

    suspend fun analyzeConnection(
        profile: TunnelProfile,
        status: TunnelStatus,
        sessionSeconds: Long,
        downSpeedBps: Double,
        upSpeedBps: Double
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val prompt = """
                You are a senior network protocol and tunnel optimization expert for Tunnel Console.
                Analyze the following tunnel configuration and live network metrics:
                - Connection Status: ${status.name}
                - Target Server: ${profile.ip}:${profile.port}
                - SNI Virtualization: ${profile.sni}
                - Handshake Payload: "${profile.payload.take(200)}"
                - Active Duration: ${sessionSeconds}s
                - Downlink Speed: ${(downSpeedBps / 1024.0).toInt()} KB/s
                - Uplink Speed: ${(upSpeedBps / 1024.0).toInt()} KB/s

                Provide a concise, professional diagnostic assessment in 3-4 bullet points:
                1. Protocol & handshake health assessment (SNI correctness, HTTP Upgrade validity).
                2. Routing efficiency & latency potential.
                3. Actionable recommendation for best performance or security hardening.
                Keep it concise and clear for a developer or network admin.
            """.trimIndent()

            val response = withTimeout(45_000L) {
                model.generateContent(prompt)
            }
            val text = response.text
            if (!text.isNullOrBlank()) {
                Result.success(text.trim())
            } else {
                Result.failure(Exception("Model returned an empty analysis."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Gemini AI analysis error", e)
            Result.failure(e)
        }
    }
}
