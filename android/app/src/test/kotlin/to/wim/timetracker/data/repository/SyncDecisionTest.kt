package to.wim.timetracker.data.repository

import to.wim.timetracker.data.api.SyncClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDecisionTest {
    private val t1 = "2026-10-31T10:00:00.000Z"
    private val t2 = "2026-10-31T11:00:00.000Z"

    @Test
    fun `newer settings on the server are pulled`() {
        assertEquals(SyncDecision.Pull, SyncDecision.of(t1, t1, hasSettings = true, remoteUpdatedAt = t2))
        assertEquals(SyncDecision.Pull, SyncDecision.of(null, null, hasSettings = false, remoteUpdatedAt = t1))
    }

    @Test
    fun `local edits the server does not have yet are pushed`() {
        assertEquals(SyncDecision.Push, SyncDecision.of(t2, t1, hasSettings = true, remoteUpdatedAt = t1))
        assertEquals(SyncDecision.Push, SyncDecision.of(t2, null, hasSettings = true, remoteUpdatedAt = t1))
    }

    @Test
    fun `an empty server gets the settings of this device`() {
        assertEquals(SyncDecision.Push, SyncDecision.of(t1, t1, hasSettings = true, remoteUpdatedAt = null))
        assertEquals(SyncDecision.Push, SyncDecision.of(null, null, hasSettings = true, remoteUpdatedAt = null))
    }

    @Test
    fun `a new install with nothing to share never pushes`() {
        assertEquals(SyncDecision.None, SyncDecision.of(null, null, hasSettings = false, remoteUpdatedAt = null))
    }

    @Test
    fun `in step, nothing to do`() {
        assertEquals(SyncDecision.None, SyncDecision.of(t1, t1, hasSettings = true, remoteUpdatedAt = t1))
    }

    @Test
    fun `server answers are read, including a refused push that returns the newer settings`() {
        val payload = """{"updatedAt":"$t2","workspaces":[{"id":"w","name":"Work","timeEntriesDbId":"db"}],"theme":"dark"}"""
        val accepted = SyncClient.parsePush(200, Json.parseToJsonElement("""{"accepted":true,"payload":$payload}""").jsonObject)
        assertTrue(accepted.accepted)
        val refused = SyncClient.parsePush(409, Json.parseToJsonElement("""{"accepted":false,"payload":$payload}""").jsonObject)
        assertFalse(refused.accepted)
        assertEquals(t2, refused.payload?.updatedAt)
        assertEquals("Work", refused.payload?.workspaces?.single()?.name)
        assertNull(SyncClient.payloadOf(Json.parseToJsonElement("""{"payload":null}""").jsonObject))
    }
}
