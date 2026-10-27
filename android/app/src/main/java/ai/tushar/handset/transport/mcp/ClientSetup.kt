package ai.tushar.handset.transport.mcp

import java.net.Inet4Address
import java.net.NetworkInterface

/** An address agents can reach the phone at: on the Wi-Fi it is on, or over Tailscale */
data class Address(val label: String, val host: String) {
    val wifi: Boolean get() = label == WIFI

    companion object {
        const val WIFI = "Wi-Fi"
    }
}

object Addresses {
    /** Tailscale first, since it works from anywhere, then Wi-Fi */
    fun list(): List<Address> {
        val found = runCatching { NetworkInterface.getNetworkInterfaces().toList() }.getOrDefault(emptyList())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>().map { network.name to it } }
            .mapNotNull { (name, address) ->
                when {
                    name.startsWith("tun") && isTailscale(address) -> Address("Tailscale", address.hostAddress.orEmpty())
                    name.startsWith("wlan") -> Address(Address.WIFI, address.hostAddress.orEmpty())
                    else -> null
                }
            }
        return found.sortedBy { if (it.label == "Tailscale") 0 else 1 }
    }

    /** Tailscale gives each device an address in 100.64.0.0/10 */
    private fun isTailscale(address: Inet4Address): Boolean {
        val bytes = address.address
        return bytes[0].toInt() and 0xff == 100 && bytes[1].toInt() and 0xc0 == 64
    }
}

/** What a person pastes into each client to connect it to the phone */
data class ClientSetup(val client: String, val text: String)

fun clientSetups(url: String, token: String): List<ClientSetup> = listOf(
    ClientSetup("Claude Code", "claude mcp add --transport http handset $url --header \"Authorization: Bearer $token\""),
    ClientSetup(
        "Codex",
        "export HANDSET_TOKEN=$token\ncodex mcp add handset --url $url --bearer-token-env-var HANDSET_TOKEN",
    ),
    // mcp-remote refuses plain http to anything but localhost without --allow-http. The header goes through env, since
    // some systems split an argument with a space in it.
    ClientSetup(
        "Claude Desktop",
        """
        {
          "mcpServers": {
            "handset": {
              "command": "npx",
              "args": ["mcp-remote", "$url", "--allow-http", "--header", "Authorization:${'$'}{HANDSET_AUTH}"],
              "env": { "HANDSET_AUTH": "Bearer $token" }
            }
          }
        }
        """.trimIndent(),
    ),
    ClientSetup("Any MCP client", "URL: $url\nHeader: Authorization: Bearer $token"),
)

fun serverUrl(host: String) = "http://$host:${McpServer.PORT}$PATH"
