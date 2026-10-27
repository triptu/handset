package ai.tushar.handset.ui.settings

import ai.tushar.handset.transport.mcp.Agents
import ai.tushar.handset.transport.mcp.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CoroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * An agent the user connected and what it may read. [added] is null for an agent added before the day was kept. [via]
 * is the site an agent signed in from through the relay, such as claude.ai, which has no token to renew.
 */
data class ConnectedAgent(val id: String, val name: String, val added: LocalDate?, val sources: Set<Source>, val via: String? = null) {
    val canRenew: Boolean get() = via == null
}

/** An agent's token, shown once, right after it is made */
data class NewToken(val agentId: String, val token: String)

/** The connected agents, as Settings shows and changes them */
interface AgentDirectory {
    val agents: StateFlow<List<ConnectedAgent>>

    fun add(name: String): NewToken

    /** Makes a new token for the agent, so the old one stops working at once. Null for an agent without one. */
    fun renew(id: String): NewToken?

    fun setSource(id: String, source: Source, on: Boolean)

    /** Revokes the agent's access at once */
    fun remove(id: String)
}

/** The agents the MCP server answers, kept in [Agents] */
class McpAgentDirectory(private val registry: Agents, scope: CoroutineScope, private val zone: ZoneId = ZoneId.systemDefault()) : AgentDirectory {
    override val agents: StateFlow<List<ConnectedAgent>> = registry.all
        .map { all -> all.map(::connected) }
        .stateIn(scope, SharingStarted.Eagerly, registry.all.value.map(::connected))

    private fun connected(agent: ai.tushar.handset.transport.mcp.Agent) = ConnectedAgent(
        agent.id,
        agent.name,
        agent.added.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
        agent.sources,
        agent.relayHost ?: if (agent.viaRelay) "the relay" else null,
    )

    private fun find(id: String) = registry.all.value.firstOrNull { it.id == id }

    override fun add(name: String): NewToken = registry.add(name).let { (agent, token) -> NewToken(agent.id, token) }

    override fun renew(id: String): NewToken? = find(id)?.let(registry::renew)?.let { NewToken(id, it) }

    override fun setSource(id: String, source: Source, on: Boolean) {
        find(id)?.let { registry.setSource(it, source, on) }
    }

    override fun remove(id: String) {
        find(id)?.let(registry::remove)
    }
}

/** Three made-up agents, for screenshots of the debug build and previews */
class FakeAgentDirectory(private val today: LocalDate = LocalDate.now()) : AgentDirectory {
    private val state = MutableStateFlow(
        listOf(
            ConnectedAgent("claude", "Claude", today.minusDays(15), setOf(Source.NOTIFICATIONS, Source.CONTACTS, Source.LOCATION), via = "claude.ai"),
            ConnectedAgent("claude-code", "Claude Code", today.minusDays(13), setOf(Source.NOTIFICATIONS)),
            ConnectedAgent("chatgpt", "ChatGPT", today.minusDays(25), setOf(Source.LOCATION), via = "chatgpt.com"),
        ),
    )

    override val agents: StateFlow<List<ConnectedAgent>> get() = state

    override fun add(name: String): NewToken {
        val id = name.lowercase().replace(' ', '-')
        state.update { it + ConnectedAgent(id, name, today, emptySet()) }
        return NewToken(id, Agents.newToken())
    }

    override fun renew(id: String): NewToken? = NewToken(id, Agents.newToken())

    override fun setSource(id: String, source: Source, on: Boolean) = state.update { all ->
        all.map { if (it.id == id) it.copy(sources = if (on) it.sources + source else it.sources - source) else it }
    }

    override fun remove(id: String) = state.update { all -> all.filter { it.id != id } }
}
