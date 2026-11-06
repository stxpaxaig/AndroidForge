package com.ghosttap.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The last firmware file the user picked for flashing (Package 5, A-30).
 * Only ever written from a file the user actually picked — never
 * invented. The stored URI is the one the picker returned; if Android
 * won't open it any more, the flash flow says so honestly instead of
 * pretending the file is there.
 */
@Serializable
data class RecentFirmware(
    val name: String,
    val uri: String,
    val size: Long,
    val pickedMs: Long
)

/**
 * Local persistence for the last-used firmware file (Package 5, A-30).
 * Same file pattern as [LearnedStore]: one JSON file in the app's
 * private files dir, failures swallowed.
 */
class RecentFirmwareStore(context: Context) {
    private val file: File = File(context.filesDir, "recent_firmware.json")
    private val json = Json { ignoreUnknownKeys = true }

    /** The last firmware picked, or null when no firmware was ever picked. */
    fun load(): RecentFirmware? {
        return runCatching {
            if (!file.exists()) return null
            json.decodeFromString(RecentFirmware.serializer(), file.readText())
        }.getOrNull()
    }

    fun save(recent: RecentFirmware) {
        runCatching {
            file.writeText(json.encodeToString(RecentFirmware.serializer(), recent))
        }
    }
}
