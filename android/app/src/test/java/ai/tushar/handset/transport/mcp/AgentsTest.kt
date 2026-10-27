package ai.tushar.handset.transport.mcp

import ai.tushar.handset.runner.ToolResult
import ai.tushar.handset.tools.PhoneTool
import ai.tushar.handset.tools.ToolOutput
import ai.tushar.handset.tools.objectSchema
import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
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

    @Test fun aPausedAgentIsRefusedAndRecordedThenItsTokenWorksAgain() {
        val agents = Agents(preferences())
        val (agent, token) = agents.add("Codex")
        agents.setSource(agent, Source.CONTACTS, true)
        val paused = mutableListOf<String>()
        agents.onPause = { paused += it.id }
        val ran = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val handler = McpHandler(
            listOf(PhoneTool("device_status", "Status", objectSchema()) { ToolOutput("Battery 80%") }),
            agents::find,
            { call -> ran += call.tool; ToolResult.Done(call.id, ToolOutput("Battery 80%"), 1) },
            "",
            "test",
            refused = { call, detail -> refused += "${call.agent} ${call.tool}: $detail" },
        )
        val call = """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"device_status"}}"""
        val auth = mapOf("Authorization" to "Bearer $token")

        agents.setEnabled(agent, false)
        assertEquals(listOf(agent.id), paused)
        val reply = runBlocking { handler.handle(call, auth) }
        assertEquals(200, reply.status)
        assertTrue(reply.json!!, reply.json.contains("\"isError\":true"))
        assertTrue(reply.json, reply.json.contains(McpHandler.paused("Codex")))
        assertEquals(listOf("Codex device_status: Paused"), refused)
        assertTrue(ran.isEmpty())

        agents.setEnabled(agent, true)
        val again = runBlocking { handler.handle(call, auth) }
        assertTrue(again.json!!, again.json.contains("Battery 80%"))
        assertEquals(listOf("device_status"), ran)
        assertEquals(setOf(Source.CONTACTS), agents.all.value.single().sources)
    }

    @Test fun anAppConnectingAgainWhilePausedIsTurnedOn() {
        val agents = Agents(preferences())
        val app = PhoneApp("ai.tushar.nanochat", "aa11")
        val (agent, _) = agents.connectApp("Nano Chat", app, setOf(Source.CONTACTS))
        agents.setEnabled(agent, false)
        assertFalse(agents.all.value.single().enabled)
        val (again, token) = agents.connectApp("Nano Chat", app, setOf(Source.CONTACTS))
        assertEquals(agent.id, again.id)
        assertTrue(agents.find(token)!!.enabled)
    }

    @Test fun aPausedRelayAgentSigningInAgainTakesTheNewGrant() {
        val agents = Agents(preferences())
        val revoked = mutableListOf<String>()
        agents.beforeRevoke = { revoked += it }
        val first = agents.addRelay("Claude", "grant-1", "claude.ai", "client")
        agents.setSource(first, Source.LOCATION, true)
        // Signing in again while it is on adds another agent, as before
        val second = agents.addRelay("Claude", "grant-2", "claude.ai", "client")
        assertNotEquals(first.id, second.id)
        agents.remove(second)
        agents.setEnabled(first, false)
        val back = agents.addRelay("Claude", "grant-3", "claude.ai", "client")
        assertEquals(first.id, back.id)
        val kept = agents.all.value.single()
        assertEquals("grant-3", kept.relayGrantId)
        assertTrue(kept.enabled)
        assertEquals(setOf(Source.LOCATION), kept.sources)
        assertEquals(listOf("grant-2", "grant-1"), revoked)
    }

    @Test fun aPausedAgentStaysPausedAfterReload() {
        val prefs = preferences()
        val agent = Agents(prefs).add("Codex").first
        Agents(prefs).setEnabled(agent, false)
        assertFalse(Agents(prefs).all.value.single().enabled)
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
