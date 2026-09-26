package com.ds.localapi.store

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom

/**
 * 生成本地 API Key（`sk-...`），用于校验接入工具的 `Authorization: Bearer`。
 * 首次启动时生成并持久化。
 */
object ApiKey {

    private const val KEY = "api_key"
    private const val CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (!::prefs.isInitialized) {
            prefs = context.getSharedPreferences("ds_local_api", Context.MODE_PRIVATE)
        }
    }

    fun get(): String {
        val existing = prefs.getString(KEY, null)
        if (!existing.isNullOrEmpty()) return existing
        val generated = generate()
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }

    fun regenerate(): String {
        val generated = generate()
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }

    fun verify(authorizationHeader: String?): Boolean {
        if (authorizationHeader.isNullOrBlank()) return false
        val token = authorizationHeader.removePrefix("Bearer").trim()
        return token == get()
    }

    private fun generate(): String {
        val sb = StringBuilder("sk-")
        val rnd = SecureRandom()
        repeat(32) {
            sb.append(CHARS[rnd.nextInt(CHARS.length)])
        }
        return sb.toString()
    }
}