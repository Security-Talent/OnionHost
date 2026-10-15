package com.onionhost.app.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.dataStore by preferencesDataStore(name = "onionhost_settings")

class SettingsRepository @Inject constructor(
    private val context: Context
) {
    companion object {
        val KEY_AUTO_START_BOOT = booleanPreferencesKey("auto_start_boot")
        val KEY_DEFAULT_PORT = intPreferencesKey("default_port")
        val KEY_DARK_THEME = booleanPreferencesKey("dark_theme")
        val KEY_RATE_LIMIT = intPreferencesKey("rate_limit")
        val KEY_USERNAME_DISCOVERY_NODES = stringPreferencesKey("username_discovery_nodes")
    }

    val autoStartOnBoot: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_START_BOOT] ?: false
    }

    val defaultPort: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEFAULT_PORT] ?: 8080
    }

    val rateLimitPerMin: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_RATE_LIMIT] ?: 120
    }

    val usernameDiscoveryNodes: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USERNAME_DISCOVERY_NODES] ?: ""
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_START_BOOT] = enabled
        }
    }

    suspend fun setDefaultPort(port: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEFAULT_PORT] = port
        }
    }

    suspend fun setRateLimit(limit: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_RATE_LIMIT] = limit
        }
    }

    suspend fun setUsernameDiscoveryNodes(nodes: String) {
        context.dataStore.edit { prefs -> prefs[KEY_USERNAME_DISCOVERY_NODES] = nodes }
    }

    suspend fun pinnedUsernameFingerprint(username: String): String? = context.dataStore.data
        .map { prefs -> prefs[stringPreferencesKey("username_pin_${username.lowercase()}")] }
        .first()

    suspend fun pinUsernameFingerprint(username: String, fingerprint: String) {
        require(username.matches(Regex("[a-z][a-z0-9_]{2,31}")))
        require(fingerprint.matches(Regex("[0-9a-fA-F]{64}")))
        context.dataStore.edit { prefs ->
            prefs[stringPreferencesKey("username_pin_${username}")] = fingerprint.lowercase()
        }
    }
}
