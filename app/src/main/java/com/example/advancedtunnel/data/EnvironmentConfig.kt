package com.example.advancedtunnel.data

import android.util.Log
import com.example.advancedtunnel.BuildConfig

/**
 * Provides type-safe access to environment variables injected via the Secrets Gradle Plugin
 * from `.env` / `.env.example` (configured via the Secrets panel in AI Studio).
 */
object EnvironmentConfig {
    private const val TAG = "EnvironmentConfig"

    val supabaseProjectId: String
        get() = BuildConfig.SUPABASE_PROJECT_ID.ifBlank { BuildConfig.VITE_SUPABASE_PROJECT_ID }

    val supabasePublishableKey: String
        get() = BuildConfig.SUPABASE_PUBLISHABLE_KEY.ifBlank { BuildConfig.VITE_SUPABASE_PUBLISHABLE_KEY }

    val supabaseUrl: String
        get() = BuildConfig.SUPABASE_URL.ifBlank { BuildConfig.VITE_SUPABASE_URL }

    fun isSupabaseConfigured(): Boolean {
        val configured = supabaseProjectId.isNotBlank() &&
            !supabaseProjectId.startsWith("YOUR_") &&
            supabasePublishableKey.isNotBlank() &&
            !supabasePublishableKey.startsWith("YOUR_") &&
            supabaseUrl.startsWith("https://")
        if (!configured) {
            Log.w(TAG, "Supabase environment variables are using placeholders. Configure them in the AI Studio Secrets panel.")
        }
        return configured
    }
}
