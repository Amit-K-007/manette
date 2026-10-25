package com.manette.app.layout

import com.manette.app.layout.definitions.XboxDefinition
import com.manette.app.layout.definitions.XboxDefinition.Ids
import com.manette.app.layout.model.Placement
import com.manette.app.layout.model.Preset
import com.manette.app.layout.storage.FilePresetRepository
import com.manette.app.layout.storage.PresetIds
import com.manette.app.layout.storage.PresetJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import java.io.File
import java.nio.file.Files
import org.junit.Test

class PresetStorageTest {
    private fun preset(id: String, name: String, controller: String = "xbox") =
        Preset(id, name, controller, mapOf(Ids.A to Placement(0.5f, 0.4f, 1.5f, 0.7f)))

    private fun repo(): Pair<FilePresetRepository, File> {
        val dir = Files.createTempDirectory("presets").toFile()
        return FilePresetRepository(File(dir, "presets")) to dir
    }

    // ------------------------------------------------------------------- JSON

    @Test
    fun presetRoundTripsThroughJsonIncludingAnAwkwardName() {
        val p = Preset("id-1", "Mine \"fast\" \u65e5\u672c\n2", "xbox", mapOf(Ids.A to Placement(0.5f, 0.4f, 1.5f, 0.7f), Ids.B to Placement(0.9f, 0.1f)))
        assertEquals(p, PresetJson.fromJson(PresetJson.toJson(p)))
    }

    @Test
    fun missingScaleAndOpacityDefaultAndUnknownFieldsAreIgnored() {
        val p = PresetJson.fromJson("""{"version":1,"id":"x","name":"n","controllerId":"xbox","future":42,"placements":{"a":{"cx":0.5,"cy":0.5}}}""")
        assertEquals(Placement(0.5f, 0.5f, 1f, 1f), p!!.placements.getValue("a"))
    }

    @Test
    fun malformedOrInvalidPresetsAreRejectedNotHalfLoaded() {
        val ok = """{"version":1,"id":"x","name":"n","controllerId":"xbox","placements":{"a":{"cx":0.5,"cy":0.5}}}"""
        assertNotNull(PresetJson.fromJson(ok))
        val bad = listOf(
            "nope", "[]", "", ok.replace("\"version\":1", "\"version\":2"), ok.replace("\"version\":1,", ""),
            ok.replace("\"id\":\"x\",", ""), ok.replace("\"name\":\"n\"", "\"name\":\"  \""),
            ok.replace("\"name\":\"n\"", "\"name\":\"${"x".repeat(61)}\""),
            ok.replace("\"cx\":0.5", "\"cx\":1.5"), ok.replace("\"cx\":0.5", "\"cx\":\"0.5\""),
            ok.replace("{\"cx\":0.5,\"cy\":0.5}", "7"), ok.replace("\"placements\":{\"a\":{\"cx\":0.5,\"cy\":0.5}}", "\"placements\":5"),
            ok.replace("\"cx\":0.5,\"cy\":0.5", "\"cx\":0.5,\"cy\":0.5,\"scale\":9"),
        )
        for (b in bad) assertNull("should reject: $b", PresetJson.fromJson(b))
    }

    // ------------------------------------------------------------- repository

    @Test
    fun saveListGetAndDelete() {
        val (r, _) = repo()
        r.save(preset("b-id", "beta")); r.save(preset("a-id", "Alpha")); r.save(preset("n-id", "NES one", controller = "nes"))
        assertEquals(listOf("Alpha", "beta"), r.list("xbox").map { it.name })
        assertEquals(listOf("NES one"), r.list("nes").map { it.name })
        assertEquals(preset("a-id", "Alpha"), r.get("a-id"))
        r.delete("a-id")
        assertNull(r.get("a-id"))
        assertEquals(listOf("beta"), r.list("xbox").map { it.name })
        r.delete("does-not-exist")                                    // harmless
    }

    @Test
    fun savingAgainReplacesAndLeavesNoTempFiles() {
        val (r, root) = repo()
        r.save(preset("p", "one")); r.save(preset("p", "two"))
        assertEquals("two", r.get("p")!!.name)
        assertEquals(1, r.list("xbox").size)
        assertTrue(File(root, "presets").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun aCorruptFileDoesNotHideTheOthersOrCrash() {
        val (r, root) = repo()
        r.save(preset("good", "Good"))
        File(root, "presets/broken.json").writeText("{ not json")
        File(root, "presets/liar.json").writeText(PresetJson.toJson(preset("other-id", "Liar")))   // id does not match its file name
        assertEquals(listOf("Good"), r.list("xbox").map { it.name })
        assertNull(r.get("broken"))
    }

    @Test
    fun unsafeIdsNeverTouchTheFileSystem() {
        val (r, root) = repo()
        for (bad in listOf("../evil", "a/b", "a\\b", "", "x".repeat(65), "dot.dot")) {
            assertFalse(PresetIds.isSafe(bad))
            assertNull(r.get(bad))
            try { r.save(preset(bad, "x")); fail("should refuse '$bad'") } catch (_: IllegalArgumentException) { }
        }
        assertFalse(File(root, "evil.json").exists())
        assertTrue(PresetIds.isSafe(PresetIds.newId()))
    }

    @Test
    fun theActivePresetIsRememberedAndFallsBackToDefaultWhenGone() {
        val (r, _) = repo()
        assertNull(r.activeId("xbox"))
        r.save(preset("p1", "One")); r.setActive("xbox", "p1")
        assertEquals("p1", r.activeId("xbox"))
        r.delete("p1")
        assertNull("a deleted preset is no longer active", r.activeId("xbox"))
        r.save(preset("p2", "Two")); r.setActive("xbox", "p2"); r.setActive("xbox", null)
        assertNull(r.activeId("xbox"))
        r.setActive("xbox", "p2")
        assertNull("active preset must belong to that controller", r.activeId("nes"))
    }

    @Test
    fun aSavedPresetResolvesToTheSamePixelsAsTheOriginal() {
        val (r, _) = repo()
        val p = preset("p", "P")
        r.save(p)
        val a = LayoutResolver.resolve(XboxDefinition.definition, p, 2000f, 1000f)
        val b = LayoutResolver.resolve(XboxDefinition.definition, r.get("p"), 2000f, 1000f)
        for (e in a.elements) {
            val o = b.find(e.id)!!
            assertEquals(e.cx, o.cx, 0.001f); assertEquals(e.halfW, o.halfW, 0.001f); assertEquals(e.opacity, o.opacity, 0.001f)
        }
    }
}
