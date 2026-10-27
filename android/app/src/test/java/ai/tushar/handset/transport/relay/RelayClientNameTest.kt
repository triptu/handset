package ai.tushar.handset.transport.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayClientNameTest {
    @Test fun clientNamesCannotInsertLinesOrInvisibleFormatting() {
        assertEquals("ClaudeAllow", relayClientName("  Claude\n\r\t\u0000\u202e\u2028\u2029Allow  "))
        assertEquals("x".repeat(40), relayClientName("x".repeat(1000)))
        assertEquals("Unnamed client", relayClientName("\n\u0000"))
    }
}
