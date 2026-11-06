package to.wim.timetracker.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushRegistrarTest {
    private class Fake {
        var wanted = true
        var notionToken = "notion"
        var pushToken: String? = "push-1"
        var deleted = 0
        var failRegister = false
        val calls = mutableListOf<String>()
        var stored: String? = null

        val registrar = PushRegistrar(
            wanted = { wanted },
            notionToken = { notionToken },
            tokens = object : PushTokens {
                override suspend fun token() = pushToken
                override suspend fun delete() {
                    deleted += 1
                    pushToken = null
                }
            },
            api = object : DeviceApi {
                override suspend fun register(pushToken: String) {
                    if (failRegister) throw Exception("offline")
                    calls += "register $pushToken"
                }
                override suspend fun unregister(pushToken: String, notionToken: String) {
                    calls += "unregister $pushToken with $notionToken"
                }
            },
            registered = object : RegisteredPushToken {
                override fun get() = stored
                override fun set(token: String?) {
                    stored = token
                }
            },
        )
    }

    @Test
    fun `signed in with sync on registers this phone`() = runBlocking {
        val f = Fake()
        f.registrar.update()
        assertEquals(listOf("register push-1"), f.calls)
        assertEquals("push-1", f.stored)
    }

    @Test
    fun `turning sync off removes it and forgets the address`() = runBlocking {
        val f = Fake()
        f.registrar.update()
        f.wanted = false
        f.registrar.update()
        assertEquals(listOf("register push-1", "unregister push-1 with notion"), f.calls)
        assertNull(f.stored)
        assertEquals(1, f.deleted)
    }

    @Test
    fun `nothing to remove when it was never registered`() = runBlocking {
        val f = Fake()
        f.wanted = false
        f.registrar.update()
        assertEquals(emptyList<String>(), f.calls)
        assertEquals(0, f.deleted)
    }

    @Test
    fun `sign-out removes it with the sign-in that was just removed`() = runBlocking {
        val f = Fake()
        f.registrar.update()
        f.notionToken = ""
        f.registrar.signedOut("old-notion")
        assertEquals("unregister push-1 with old-notion", f.calls.last())
        assertNull(f.stored)
    }

    @Test
    fun `a new address is registered only while wanted`() = runBlocking {
        val f = Fake()
        f.registrar.onNewToken("push-2")
        assertEquals("push-2", f.stored)
        f.wanted = false
        f.registrar.onNewToken("push-3")
        assertEquals(listOf("register push-2"), f.calls)
    }

    @Test
    fun `without Play services nothing is registered`() = runBlocking {
        val f = Fake()
        f.pushToken = null
        f.registrar.update()
        assertEquals(emptyList<String>(), f.calls)
        assertNull(f.stored)
    }

    @Test(expected = Exception::class)
    fun `a failed registration is reported and not remembered`(): Unit = runBlocking {
        val f = Fake()
        f.failRegister = true
        try {
            f.registrar.update()
        } finally {
            assertNull(f.stored)
        }
    }
}
