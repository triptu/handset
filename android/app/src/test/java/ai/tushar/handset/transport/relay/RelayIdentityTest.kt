package ai.tushar.handset.transport.relay

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

class RelayIdentityTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    @Test fun protocolSignatureVector() {
        val key = hex("3059301306072a8648ce3d020106082a8648ce3d030107034200046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5")
        val der = hex("3046022100b8d74de0a82406c90dc06cb99cfd8a4f6709c02384fa22d69f6261177e21310e0221009ede61b246ca7ddec6954725d13445670734d10803709545ddc21c8d84743cf0")
        val expected = hex("b8d74de0a82406c90dc06cb99cfd8a4f6709c02384fa22d69f6261177e21310e9ede61b246ca7ddec6954725d13445670734d10803709545ddc21c8d84743cf0")
        val converted = derToP1363(der)
        assertArrayEquals(expected, converted)
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
        for ((algorithm, signature) in listOf("SHA256withECDSA" to der, "SHA256withECDSAinP1363Format" to converted)) {
            assertTrue(Signature.getInstance(algorithm).run {
                initVerify(publicKey); update("Handset DER to P1363 test vector".toByteArray()); verify(signature)
            })
        }
    }
    @Test fun padsShortIntegers() {
        val result = derToP1363(hex("3006020101020102"))
        assertEquals(64, result.size); assertEquals(1, result[31].toInt()); assertEquals(2, result[63].toInt())
    }
    @Test fun rejectsMalformedSignatures() {
        for (value in listOf("", "3006020180020102", "300602010102010200", "3006020101020202")) {
            assertThrows(IllegalArgumentException::class.java) { derToP1363(hex(value)) }
        }
    }
    @Test fun requiresSecureOrigin() {
        assertEquals("https://relay.example", relayOrigin("https://relay.example/"))
        assertEquals("http://127.0.0.1:8787", relayOrigin("http://127.0.0.1:8787"))
        for (url in listOf("http://relay.example", "https://relay.example/mcp", "https://user@relay.example", "https://relay.example?key=x")) {
            assertThrows(IllegalArgumentException::class.java) { relayOrigin(url) }
        }
    }
}
