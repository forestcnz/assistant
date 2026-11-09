package com.forest.android.assistant.core.flow

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlowTemplateContentTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun image(id: String, asset: String): JSONObject = JSONObject().put("id", id).put("type", "image")
        .put("props", JSONObject().put("asset", asset))

    private fun document(vararg nodes: JSONObject): JSONObject = JSONObject().put("schemaVersion", 1).put("nodes", JSONArray(nodes.toList()))

    @Test fun emptyNamedTemplateCanBeCreatedBeforeEditing() {
        val parsed = FlowTemplateContent.parse(document().toString())
        assertEquals(0, parsed.getJSONArray("nodes").length())
        assertTrue(FlowTemplateContent.preview(parsed).contains("function __flowMain()"))
    }

    @Test fun acceptsNestedStepsAndLoopControlFragments() {
        val node = JSONObject().put("id", "break1").put("type", "break")
        val parsed = FlowTemplateContent.parse(document(node).toString())
        assertEquals(1, parsed.getJSONArray("nodes").length())
        assertTrue(FlowTemplateContent.preview(parsed).contains("break;"))
    }

    @Test fun rejectsInvalidDuplicateIdsIncludingDisabledContainers() {
        val hidden = JSONObject().put("id", "container").put("type", "sequence").put("enabled", false)
            .put("children", JSONArray().put(image("same", "a.png")))
        assertThrows(IllegalArgumentException::class.java) {
            FlowTemplateContent.parse(document(hidden, image("same", "b.png")).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowTemplateContent.parse(document(image("../bad", "a.png")).toString())
        }
    }

    @Test fun remapsImageFieldsWithoutChangingTextConditionsOrCascadingNames() {
        val condition = JSONObject().put("id", "condition").put("type", "condition")
            .put("props", JSONObject().put("conditionKind", "text").put("conditionValue", "a.png")
                .put("secondKind", "image").put("secondValue", "images/b.png"))
        val source = document(image("a", "images/a.png"), image("b", "b.png"), condition)
        assertEquals(setOf("a.png", "b.png"), FlowTemplateContent.imageNames(source))
        val updated = FlowTemplateContent.remapImages(source, mapOf("a.png" to "b.png", "b.png" to "c.png"))
        val nodes = updated.getJSONArray("nodes")
        assertEquals("images/b.png", nodes.getJSONObject(0).getJSONObject("props").getString("asset"))
        assertEquals("c.png", nodes.getJSONObject(1).getJSONObject("props").getString("asset"))
        val fields = nodes.getJSONObject(2).getJSONObject("props")
        assertEquals("a.png", fields.getString("conditionValue"))
        assertEquals("images/c.png", fields.getString("secondValue"))
        assertEquals("images/a.png", source.getJSONArray("nodes").getJSONObject(0).getJSONObject("props").getString("asset"))
    }

    @Test fun reusesIdenticalImagesAndPreservesConflictingTargetImages() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        File(source, "same.png").writeText("identical")
        File(target, "same.png").writeText("identical")
        File(source, "other.png").writeText("template image")
        File(target, "other.png").writeText("task image")
        val mapping = FlowTemplateContent.copyImages(document(image("a", "same.png"), image("b", "other.png")), source, target)
        assertEquals("same.png", mapping["same.png"])
        assertNotEquals("other.png", mapping["other.png"])
        assertEquals("task image", File(target, "other.png").readText())
        assertEquals("template image", File(target, mapping.getValue("other.png")).readText())
        assertEquals(3, target.listFiles()!!.size)
    }

    @Test fun failedCopyRemovesNewFilesAndRejectsPathTraversal() {
        val source = temporary.newFolder("source")
        val target = temporary.newFolder("target")
        File(source, "first.png").writeText("image")
        File(target, "existing.png").writeText("keep")
        assertThrows(IllegalArgumentException::class.java) {
            FlowTemplateContent.copyImages(document(image("a", "first.png"), image("b", "missing.png")), source, target)
        }
        assertEquals(listOf("existing.png"), target.listFiles()!!.map { it.name })
        assertThrows(IllegalArgumentException::class.java) {
            FlowTemplateContent.imageNames(document(image("a", "images/../secret.png")))
        }
    }

}
