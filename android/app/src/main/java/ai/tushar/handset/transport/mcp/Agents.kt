package ai.tushar.handset.transport.mcp

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** The user's own things an agent can read only when the user turns them on for that agent */
enum class Source(val title: String, val detail: String) {
    NOTIFICATIONS("Notifications", "New notifications, and the ones Handset kept, in search and a day's timeline"),
    SMS("Text messages", "Your SMS, such as a code that just arrived"),
    CONTACTS("Contacts", "Names and numbers"),
    LOCATION("Location", "Where the phone is now, and the places kept in a day's timeline"),
    PHOTOS("Photos and videos", "Opening and sharing your photos"),
}

/**
 * An agent the user added by name. Its token is kept only as [tokenHash], so the phone can check it but never show it
 * again. [added] is when, in ms, or 0 for an agent added before it was kept.
 */
@Serializable
data class Agent(
    val id: String,
    val name: String,
    val tokenHash: String,
    val sources: Set<Source> = emptySet(),
    val relayGrantId: String? = null,
    val relayHost: String? = null,
    val added: Long = 0,
) {
    val viaRelay: Boolean get() = relayGrantId != null
    val canRenew: Boolean get() = relayGrantId == null
}

/** The agents the user added, kept in [preferences]. Removing an agent revokes its token. */
class Agents(private val preferences: SharedPreferences) {
    private val _all = MutableStateFlow(load())
    val all: StateFlow<List<Agent>> get() = _all

    /** Called before removal so a relay revocation is durable before the agent disappears. */
    var beforeRemove: (Agent) -> Unit = {}

    @Synchronized
    fun addRelay(name: String, grantId: String, host: String): Agent {
        _all.value.firstOrNull { it.relayGrantId == grantId }?.let { return it }
        val agent = Agent(UUID.randomUUID().toString(), name, "", relayGrantId = grantId, relayHost = host, added = System.currentTimeMillis())
        save(_all.value + agent)
        return agent
    }

    /** Adds an agent called [name] and returns it with its token, which is shown once */
    @Synchronized
    fun add(name: String): Pair<Agent, String> {
        val token = newToken()
        val agent = Agent(UUID.randomUUID().toString(), name.trim(), hash(token), added = System.currentTimeMillis())
        save(_all.value + agent)
        return agent to token
    }

    /** Gives [agent] a new token, so the old one stops working, and returns it */
    @Synchronized
    fun renew(agent: Agent): String? {
        if (!agent.canRenew || _all.value.none { it.id == agent.id && it.canRenew }) return null
        val token = newToken()
        update(agent.id) { it.copy(tokenHash = hash(token)) }
        return token
    }

    @Synchronized
    fun remove(agent: Agent) {
        beforeRemove(agent)
        save(_all.value.filter { it.id != agent.id })
    }

    @Synchronized
    fun setSource(agent: Agent, source: Source, on: Boolean) = update(agent.id) { it.copy(sources = if (on) it.sources + source else it.sources - source) }

    /** The agent [token] belongs to, or null */
    fun find(token: String): Agent? = find(_all.value, token)

    private fun update(id: String, change: (Agent) -> Agent) = save(_all.value.map { if (it.id == id) change(it) else it })

    @Synchronized
    private fun save(agents: List<Agent>) {
        check(preferences.edit().putString(KEY, json.encodeToString(agents)).commit())
        _all.value = agents
    }

    private fun load(): List<Agent> = preferences.getString(KEY, null)?.let { runCatching { json.decodeFromString<List<Agent>>(it) }.getOrNull() }.orEmpty()

    companion object {
        private const val KEY = "agents"
        private const val TOKEN_PREFIX = "hs_"
        private val json = Json { ignoreUnknownKeys = true }
        private val random = SecureRandom()

        fun newToken(): String = TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

        /** SHA-256 is enough: a token is 256 random bits, so there is nothing to guess */
        fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }

        /** Compares the hashes in constant time, and checks every agent, so the time taken says nothing about the token */
        fun find(agents: List<Agent>, token: String): Agent? {
            val presented = hash(token).toByteArray()
            var found: Agent? = null
            for (agent in agents) if (agent.relayGrantId == null && MessageDigest.isEqual(agent.tokenHash.toByteArray(), presented)) found = agent
            return found
        }
    }
}
