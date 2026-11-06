package to.wim.timetracker.data.api

import to.wim.timetracker.data.models.ExtraProp
import to.wim.timetracker.data.models.Preset
import to.wim.timetracker.data.models.PropertyMap
import to.wim.timetracker.data.models.SyncPayload
import to.wim.timetracker.data.models.Workspace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPayloadJsonTest {
    private val payload =
        SyncPayload(
            updatedAt = "2026-10-13T08:00:00.000Z",
            workspaces =
                listOf(
                    Workspace(
                        id = "ws1",
                        name = "Client",
                        timeEntriesDbId = "db1",
                        roundingMinutes = 5,
                        propertyMap = PropertyMap(timeEntryTitleProp = "Name", timeEntryDateProp = "Date"),
                        extraProps = listOf(ExtraProp(propName = "Tags", type = "multi_select", required = true)),
                        presets = listOf(Preset(id = "p1", name = "Standup", extraValues = mapOf("Tags" to "a,b"))),
                        color = "#FF0000",
                    ),
                ),
            locale = "nl",
            showNotionLink = true,
        )

    @Test
    fun roundTripKeepsEverything() {
        assertEquals(payload, decodeSyncPayload(encodeSyncPayload(payload)))
    }

    @Test
    fun unsetOptionalFieldsAreLeftOut() {
        val root = Json.parseToJsonElement(encodeSyncPayload(payload)).jsonObject
        assertFalse("theme" in root)
        assertFalse("showArchiveAction" in root)
        assertEquals("nl", root.stringOrNull("locale"))
        val extraProp = root.arrayOrNull("workspaces")!![0].jsonObject.arrayOrNull("extraProps")!![0].jsonObject
        assertFalse("defaultValue" in extraProp)
        assertEquals("multi_select", extraProp.stringOrNull("type"))
    }

    @Test
    fun readsTheDesktopAppsShapes() {
        val decoded =
            decodeSyncPayload(
                """
                {
                  "updatedAt": "2026-10-13T08:00:00.000Z",
                  "theme": "dark",
                  "alwaysOnTop": true,
                  "workspaces": [{
                    "name": "Desk",
                    "color": null,
                    "roundingEnabled": false,
                    "roundingMinutes": 10,
                    "propertyMap": {"timeEntryTitleProp": "Name", "timeEntryRelationProp": null},
                    "extraProps": ["Project", {"propName": "Tags", "required": true}, {"required": false}],
                    "presets": [{
                      "id": "p1",
                      "name": "Focus",
                      "extraValues": {"Tags": ["a", "b"], "Billable": true, "Hours": 2, "Empty": null}
                    }]
                  }]
                }
                """.trimIndent(),
            )!!
        assertEquals("dark", decoded.theme)
        val ws = decoded.workspaces.single()
        assertTrue(ws.id.isNotBlank())
        assertEquals("Desk", ws.name)
        assertEquals("", ws.color)
        assertEquals(10, ws.roundingMinutes)
        assertEquals("", ws.propertyMap.timeEntryRelationProp)
        assertEquals(
            listOf(ExtraProp("Project"), ExtraProp("Tags", required = true), ExtraProp("")),
            ws.extraProps,
        )
        assertEquals(
            mapOf("Tags" to "a,b", "Billable" to "true", "Hours" to "2", "Empty" to ""),
            ws.presets.single().extraValues,
        )
    }

    @Test
    fun rejectsTextThatIsNotASyncPayload() {
        assertNull(decodeSyncPayload("not json"))
        assertNull(decodeSyncPayload("""{"workspaces": []}"""))
        assertNull(decodeSyncPayload("""{"updatedAt": "2026-10-13", "workspaces": {}}"""))
        assertNull(decodeSyncPayload("""["updatedAt"]"""))
    }

    @Test
    fun requestBodiesKeepNulls() {
        val body = mapOf("date" to mapOf("start" to "2026-10-13", "end" to null), "number" to 2.5, "tags" to listOf("a"))
        assertEquals("""{"date":{"start":"2026-10-13","end":null},"number":2.5,"tags":["a"]}""", body.toJsonElement().toString())
    }

    @Test
    fun nullPropertiesReadAsAbsent() {
        val page = Json.parseToJsonElement("""{"select": null, "number": 3, "checkbox": true, "date": null}""").jsonObject
        assertNull(page.objOrNull("select"))
        assertNull(page.stringOrNull("select"))
        assertNull(page.objOrNull("date"))
        assertEquals("3", page.stringOrNull("number"))
        assertEquals(true, page.boolOrNull("checkbox"))
        assertNull(page.boolOrNull("select"))
    }
}
