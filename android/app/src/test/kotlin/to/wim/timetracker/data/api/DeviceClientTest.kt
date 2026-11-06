package to.wim.timetracker.data.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceClientTest {
    @Test
    fun `sends the push token as the server reads it`() {
        val body = Json.parseToJsonElement(DeviceClient.body("abc:12\"3")).jsonObject
        assertEquals(setOf("token"), body.keys)
        assertEquals("abc:12\"3", body["token"]!!.jsonPrimitive.content)
    }
}
