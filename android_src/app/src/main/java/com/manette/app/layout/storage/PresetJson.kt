package com.manette.app.layout.storage

import com.manette.app.json.Json
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Preset

/**
 * The on-disk preset format, versioned so a future app can change it safely:
 * `{"version":1,"id":"...","name":"...","controllerId":"xbox","placements":{"a":{"cx":0.86,"cy":0.58,"scale":1.0,"opacity":1.0}}}`
 * Reading is strict about structure and ranges: anything malformed yields null rather than a half-valid preset.
 */
object PresetJson {
    const val VERSION = 1
    const val MAX_NAME = 60

    fun toJson(p: Preset): String = Json.write(
        linkedMapOf(
            "version" to VERSION,
            "id" to p.id,
            "name" to p.name,
            "controllerId" to p.controllerId,
            "placements" to p.placements.mapValuesTo(LinkedHashMap<String, Any?>()) { (_, pl) ->
                linkedMapOf("cx" to pl.cx, "cy" to pl.cy, "scale" to pl.scale, "opacity" to pl.opacity)
            },
        ),
    )

    fun fromJson(text: String): Preset? = try {
        parse(text)
    } catch (_: Exception) {
        null   // bad JSON, wrong types, out-of-range values (Placement validates itself): treat all as "not a preset"
    }

    @Suppress("UNCHECKED_CAST")
    private fun parse(text: String): Preset? {
        val root = Json.parse(text) as? Map<String, Any?> ?: return null
        val version = (root["version"] as? Long) ?: return null
        if (version != VERSION.toLong()) return null            // from a newer (or unknown) format: do not guess
        val id = root["id"] as? String ?: return null
        val name = root["name"] as? String ?: return null
        val controllerId = root["controllerId"] as? String ?: return null
        if (id.isEmpty() || controllerId.isEmpty() || name.isBlank() || name.length > MAX_NAME) return null

        val raw = root["placements"] as? Map<String, Any?> ?: return null
        val placements = LinkedHashMap<String, Placement>()
        for ((controlId, v) in raw) {
            val m = v as? Map<String, Any?> ?: return null
            placements[controlId] = Placement(
                cx = num(m["cx"]) ?: return null,
                cy = num(m["cy"]) ?: return null,
                scale = if (m.containsKey("scale")) num(m["scale"]) ?: return null else 1f,
                opacity = if (m.containsKey("opacity")) num(m["opacity"]) ?: return null else 1f,
            )
        }
        return Preset(id, name, controllerId, placements)
    }

    private fun num(v: Any?): Float? = when (v) {
        is Long -> v.toFloat()
        is Double -> v.toFloat()
        else -> null
    }
}
