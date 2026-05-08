package com.ran.crm.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = createEncryptedPrefs(context)

    companion object {
        private const val TAG = "PreferenceManager"
        private const val PREFS_NAME = "crm_prefs_encrypted"
        private const val LEGACY_PREFS_NAME = "crm_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_LAST_SYNC_CONTACTS = "last_sync_contacts"
        private const val KEY_LAST_SYNC_CALLS = "last_sync_calls"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_APP_THEME = "app_theme"
        private const val KEY_FONT_SCALE = "font_scale"
        private const val KEY_SYNC_INTERVAL_MINUTES = "sync_interval_minutes"
        private const val KEY_IS_ADMIN = "is_admin"
        private const val KEY_DEBUG_MODE = "debug_mode"

        private fun createEncryptedPrefs(context: Context): SharedPreferences {
            return try {
                        val masterKey =
                                MasterKey.Builder(context)
                                        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                        .build()
                        EncryptedSharedPreferences.create(
                                context,
                                PREFS_NAME,
                                masterKey,
                                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create encrypted prefs, clearing and retrying", e)
                        // If encrypted prefs are corrupted, delete and recreate
                        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .clear()
                                .apply()
                        try {
                            val masterKey =
                                    MasterKey.Builder(context)
                                            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                                            .build()
                            EncryptedSharedPreferences.create(
                                    context,
                                    PREFS_NAME,
                                    masterKey,
                                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                            )
                        } catch (e2: Exception) {
                            Log.e(
                                    TAG,
                                    "Encrypted prefs totally failed, falling back to standard",
                                    e2
                            )
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        }
                    }.also {
                // Migrate legacy unencrypted prefs if they exist
                migrateLegacyPrefs(context, it)
            }
        }

        private fun migrateLegacyPrefs(context: Context, encryptedPrefs: SharedPreferences) {
            val legacyPrefs = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (legacyPrefs.all.isEmpty()) return

            Log.d(TAG, "Migrating legacy preferences to encrypted storage")
            val editor = encryptedPrefs.edit()
            for ((key, value) in legacyPrefs.all) {
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                }
            }
            editor.apply()
            // Clear legacy prefs after migration so we don't migrate again
            legacyPrefs.edit().clear().apply()
        }
    }

    var isAdmin: Boolean
        get() = prefs.getBoolean(KEY_IS_ADMIN, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_ADMIN, value).apply()

    var isDebugMode: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_MODE, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var authToken: String?
        get() = prefs.getString(KEY_AUTH_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_AUTH_TOKEN, value).apply()

    var lastSyncContacts: Long
        get() = prefs.getLong(KEY_LAST_SYNC_CONTACTS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_CONTACTS, value).apply()

    var lastSyncCalls: Long
        get() = prefs.getLong(KEY_LAST_SYNC_CALLS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_CALLS, value).apply()

    var appTheme: String
        get() = prefs.getString(KEY_APP_THEME, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_APP_THEME, value).apply()

    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, value).apply()

    var syncIntervalMinutes: Int
        get() = prefs.getInt(KEY_SYNC_INTERVAL_MINUTES, 15)
        set(value) = prefs.edit().putInt(KEY_SYNC_INTERVAL_MINUTES, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun clearSession() {
        authToken = null
        userId = null
        isAdmin = false
    }
}
