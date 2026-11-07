package com.ghosttap.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BaselineStore(context: Context) {
    private val prefs = context.getSharedPreferences("ghost_tap_baseline", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(snapshot: BaselineSnapshot) {
        prefs.edit().putString("snapshot", json.encodeToString(snapshot)).apply()
    }

    fun load(): BaselineSnapshot? {
        val raw = prefs.getString("snapshot", null) ?: return null
        return runCatching { json.decodeFromString<BaselineSnapshot>(raw) }.getOrNull()
    }
}
