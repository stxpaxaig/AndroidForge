package com.ghosttap.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Local persistence for learned device controls. Learned actions are only
 * ever created from traffic Ghost Tap actually observed — never fabricated.
 * Stored as JSON in the app's private files dir, keyed by device MAC address.
 */
class LearnedStore(context: Context) {
    private val file: File = File(context.filesDir, "learned_controls.json")
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Volatile
    private var cache: MutableMap<String, DeviceLearnedMap>? = null

    private fun load(): MutableMap<String, DeviceLearnedMap> {
        cache?.let { return it }
        val map: MutableMap<String, DeviceLearnedMap> = mutableMapOf()
        runCatching {
            if (file.exists()) {
                val list = json.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(DeviceLearnedMap.serializer()),
                    file.readText()
                )
                list.forEach { map[it.address] = it }
            }
        }
        cache = map
        return map
    }

    /**
     * Last save failure, in plain words — null when the last save worked
     * (Package 6 / E-45). A failed save is now visible instead of silent:
     * the old file is kept, so nothing learned is lost to a half-written
     * file.
     */
    @Volatile
    var lastSaveError: String? = null
        private set

    private fun save(map: Map<String, DeviceLearnedMap>) {
        // Atomic save (Package 6 / E-45): write a temp file, then swap it
        // into place. A crash mid-write leaves the old file intact — the
        // old direct writeText could leave a half-written file that the
        // next load would quietly drop.
        val tmp = File(file.parentFile, file.name + ".tmp")
        val ok = runCatching {
            val list = map.values.toList()
            tmp.writeText(
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(DeviceLearnedMap.serializer()),
                    list
                )
            )
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) throw IllegalStateException("could not replace ${file.name}")
            }
        }
        lastSaveError = ok.exceptionOrNull()?.let {
            "Couldn't save the learned controls: ${it.message ?: "unknown problem"}"
        }
    }

    @Synchronized
    fun actionsFor(address: String): List<LearnedAction> =
        load()[address]?.actions ?: emptyList()

    @Synchronized
    fun addAction(address: String, action: LearnedAction) {
        val map = load()
        val existing = map[address] ?: DeviceLearnedMap(address)
        map[address] = existing.copy(actions = existing.actions + action)
        save(map)
    }

    @Synchronized
    fun removeAction(address: String, actionId: String) {
        val map = load()
        val existing = map[address] ?: return
        map[address] = existing.copy(actions = existing.actions.filterNot { it.id == actionId })
        save(map)
    }

    fun newId(): String = UUID.randomUUID().toString()
}
