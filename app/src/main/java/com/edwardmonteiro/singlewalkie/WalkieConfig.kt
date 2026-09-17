package com.edwardmonteiro.singlewalkie

import android.content.Context

data class WalkieConfig(
    val displayName: String,
    val peerHost: String,
    val port: Int,
) {
    companion object {
        private const val PREFS = "walkie_config"
        private const val KEY_NAME = "display_name"
        private const val KEY_HOST = "peer_host"
        private const val KEY_PORT = "port"
        const val DEFAULT_PORT = 45454

        fun load(context: Context): WalkieConfig {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return WalkieConfig(
                displayName = prefs.getString(KEY_NAME, "Edward") ?: "Edward",
                peerHost = prefs.getString(KEY_HOST, "") ?: "",
                port = prefs.getInt(KEY_PORT, DEFAULT_PORT),
            )
        }

        fun save(context: Context, config: WalkieConfig) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_NAME, config.displayName.trim().ifBlank { "Walkie" })
                .putString(KEY_HOST, config.peerHost.trim())
                .putInt(KEY_PORT, config.port.coerceIn(1024, 65535))
                .apply()
        }
    }
}
