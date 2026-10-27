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

    @Test fun anAppConnectingAgainGetsANewTokenAndKeepsItsAgent() {
        val agents = Agents(preferences())
        val app = PhoneApp("ai.tushar.nanochat", "aa11")
        val (first, oldToken) = agents.connectApp("Nano Chat", app, setOf(Source.CONTACTS))
        val (second, newToken) = agents.connectApp("Nano Chat", app, setOf(Source.CONTACTS))
        assertEquals(first.id, second.id)
        assertEquals(setOf(Source.CONTACTS), agents.all.value.single().sources)
        assertNull(agents.find(oldToken))
        assertEquals(first.id, agents.find(newToken)!!.id)
        assertFalse(second.canRenew)
        assertNull(agents.renew(second))
    }

    @Test fun anotherCertificateForTheSamePackageIsAnotherAgent() {
        val agents = Agents(preferences())
        val (real, realToken) = agents.connectApp("Nano Chat", PhoneApp("ai.tushar.nanochat", "aa11"), setOf(Source.SMS))
        val (other, otherToken) = agents.connectApp("Nano Chat", PhoneApp("ai.tushar.nanochat", "bb22"), emptySet())
        assertNotEquals(real.id, other.id)
        assertEquals(2, agents.all.value.size)
        assertEquals(setOf(Source.SMS), agents.find(realToken)!!.sources)
        assertEquals(emptySet<Source>(), agents.find(otherToken)!!.sources)
    }

    @Test fun uninstallingAnAppRevokesItsTokenAndLeavesOtherAgents() {
        val agents = Agents(preferences())
        val (_, appToken) = agents.connectApp("Nano Chat", PhoneApp("ai.tushar.nanochat", "aa11"), emptySet())
        val (_, kept) = agents.connectApp("Other", PhoneApp("com.example.other", "cc33"), emptySet())
        val (_, typed) = agents.add("Claude Code")
        val installed = mutableMapOf("ai.tushar.nanochat" to "aa11", "com.example.other" to "cc33")
        agents.forgetGoneApps(installed::get)
        assertNotNull(agents.find(appToken))
        installed.remove("ai.tushar.nanochat")
        agents.forgetGoneApps(installed::get)
        assertNull(agents.find(appToken))
        assertNotNull(agents.find(kept))
        assertNotNull(agents.find(typed))
        // Reinstalled with another key, the old agent's token doesn't come back
        installed["com.example.other"] = "dd44"
        agents.forgetGoneApps(installed::get)
        assertNull(agents.find(kept))
        assertEquals(1, agents.all.value.size)
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
