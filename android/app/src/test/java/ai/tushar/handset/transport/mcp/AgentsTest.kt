package ai.tushar.handset.transport.mcp

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class AgentsTest {
    @Test fun relayRenewalIsSafeAndLocalRenewalReplacesTheToken() {
        val agents = Agents(preferences())
        val relay = agents.addRelay("Inspector", "grant", "127.0.0.1")
        assertFalse(relay.canRenew)
        assertNull(agents.renew(relay))
        assertEquals(relay, agents.all.value.single())
        val (local, oldToken) = agents.add("Local")
        assertTrue(local.canRenew)
        val newToken = agents.renew(local)
        assertNotNull(newToken)
        assertNull(agents.find(oldToken))
        assertEquals(local.id, agents.find(newToken!!)!!.id)
        agents.remove(local)
        assertNull(agents.renew(local))
    }

    @Test fun relayOriginSurvivesReloadAndOldAgentsStillLoad() {
        val prefs = preferences()
        val agent = Agents(prefs).addRelay("Inspector", "grant", "claude.ai")
        assertTrue(agent.viaRelay)
        assertEquals("claude.ai", Agents(prefs).all.value.single().relayHost)
        prefs.edit().putString("agents", """[{"id":"old","name":"Local","tokenHash":"hash"}]""").commit()
        val old = Agents(prefs).all.value.single()
        assertFalse(old.viaRelay)
        assertTrue(old.canRenew)
        assertNull(old.relayHost)
    }

    private fun preferences(): SharedPreferences {
        var stored: String? = null
        val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
            when (method.name) {
                "putString" -> { stored = args[1] as String; proxy }
                "commit" -> true
                else -> error(method.name)
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) {
                "getString" -> stored
                "edit" -> editor
                else -> error(method.name)
            }
        } as SharedPreferences
    }
}
