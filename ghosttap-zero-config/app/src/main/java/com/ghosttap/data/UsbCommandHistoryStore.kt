package com.ghosttap.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local persistence for USB terminal command history (Package 5, D-10).
 *
 * Same file pattern as [LearnedStore]: one JSON file in the app's
 * private files dir, failures swallowed — a corrupt file just means an
 * empty history, never a crash. Most-recent-first, capped at 20 entries.
 */
class UsbCommandHistoryStore(context: Context) {
    private val file: File = File(context.filesDir, "usb_command_history.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<String> {
        return runCatching {
            if (!file.exists()) return emptyList()
            json.decodeFromString(ListSerializer(String.serializer()), file.readText())
        }.getOrDefault(emptyList())
    }

    fun save(history: List<String>) {
        runCatching {
            file.writeText(json.encodeToString(ListSerializer(String.serializer()), history))
        }
    }

    fun clear() {
        runCatching {
            if (file.exists()) file.delete()
        }
    }
}
