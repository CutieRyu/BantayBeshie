package com.example.bantaybeshie

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {
    private const val FILE = "bantaybeshie_secrets"
    private const val KEY_EMAIL = "smtp_user"
    private const val KEY_PASS = "smtp_pass"

    fun storeCredentials(context: Context, email: String, appPassword: String) {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        prefs.edit().putString(KEY_EMAIL, email).putString(KEY_PASS, appPassword).apply()
    }

    fun getCredentials(context: Context): Pair<String?, String?> {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs = EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        return Pair(prefs.getString(KEY_EMAIL, null), prefs.getString(KEY_PASS, null))
    }
}
