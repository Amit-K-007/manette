package com.manette.app.layout.storage

import com.manette.app.layout.model.Preset
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

interface PresetRepository {
    /** Saved presets for one controller type, sorted by name. The built-in default is not stored, so it is not listed. */
    fun list(controllerId: String): List<Preset>
    fun get(id: String): Preset?
    fun save(preset: Preset)
    fun delete(id: String)

    /** The preset to use for [controllerId], or null for the built-in default (also null if that preset vanished). */
    fun activeId(controllerId: String): String?
    fun setActive(controllerId: String, presetId: String?)
}

object PresetIds {
    private val SAFE = Regex("[A-Za-z0-9_-]{1,64}")
    fun isSafe(id: String) = SAFE.matches(id)
    fun newId(): String = UUID.randomUUID().toString()
}

/**
 * One JSON file per preset in [dir]. Ids are restricted to a safe character set because they become file names
 * (no path traversal). Writes are atomic (temp file then rename), and one corrupt file never hides the others.
 */
class FilePresetRepository(private val dir: File) : PresetRepository {
    override fun list(controllerId: String): List<Preset> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .mapNotNull { readFile(it) }
            .filter { it.controllerId == controllerId }
            .sortedBy { it.name.lowercase() }

    override fun get(id: String): Preset? =
        if (PresetIds.isSafe(id)) fileFor(id).takeIf { it.isFile }?.let { readFile(it) } else null

    override fun save(preset: Preset) {
        require(PresetIds.isSafe(preset.id)) { "unsafe preset id" }
        writeAtomic(fileFor(preset.id), PresetJson.toJson(preset))
    }

    override fun delete(id: String) {
        if (PresetIds.isSafe(id)) fileFor(id).delete()
    }

    override fun activeId(controllerId: String): String? {
        if (!PresetIds.isSafe(controllerId)) return null
        val f = activeFile(controllerId)
        val id = (if (f.isFile) f.readText(StandardCharsets.UTF_8).trim() else return null)
        return id.takeIf { get(it)?.controllerId == controllerId }
    }

    override fun setActive(controllerId: String, presetId: String?) {
        require(PresetIds.isSafe(controllerId)) { "unsafe controller id" }
        if (presetId == null) { activeFile(controllerId).delete(); return }
        require(PresetIds.isSafe(presetId)) { "unsafe preset id" }
        writeAtomic(activeFile(controllerId), presetId)
    }

    private fun fileFor(id: String) = File(dir, "$id.json")
    private fun activeFile(controllerId: String) = File(dir, "active-$controllerId.txt")

    private fun readFile(f: File): Preset? = try {
        PresetJson.fromJson(f.readText(StandardCharsets.UTF_8))?.takeIf { it.id + ".json" == f.name }
    } catch (_: Exception) {
        null
    }

    private fun writeAtomic(target: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text, StandardCharsets.UTF_8)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
