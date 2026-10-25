package com.manette.app.session

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.manette.app.layout.model.Preset
import com.manette.app.layout.storage.PresetRepository

/** Compose-observable view of the saved presets for one controller type. (Tiny files, so IO on the main thread is fine.) */
class PresetsState(private val repo: PresetRepository, private val controllerId: String) {
    var presets by mutableStateOf(repo.list(controllerId)); private set
    var activeId by mutableStateOf(repo.activeId(controllerId)); private set

    /** The preset in use, or null for the built-in default layout. */
    val active: Preset? get() = presets.firstOrNull { it.id == activeId }

    fun select(id: String?) { repo.setActive(controllerId, id); activeId = id }

    fun save(p: Preset) {
        repo.save(p)
        repo.setActive(controllerId, p.id)
        refresh()
    }

    fun delete(id: String) {
        repo.delete(id)
        refresh()
    }

    private fun refresh() {
        presets = repo.list(controllerId)
        activeId = repo.activeId(controllerId)
    }
}
