package ai.tushar.handset.transport.relay

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

internal fun base64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal fun relayOrigin(url: String): String {
    val uri = URI(url.trim())
    require(uri.scheme == "https" || (uri.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "[::1]"))) { "Enter an HTTPS relay URL" }
    require(uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path in listOf("", "/")) { "Enter the relay origin, without a path" }
    require(uri.port == -1 || uri.port in 1..65535) { "Invalid relay port" }
    val port = uri.port.takeUnless { it == -1 || (uri.scheme == "https" && it == 443) || (uri.scheme == "http" && it == 80) }
    return "${uri.scheme}://${uri.host.lowercase()}${port?.let { ":$it" }.orEmpty()}"
}

/** Android emits two DER integers; the wire uses two unsigned 32-byte integers. */
internal fun derToP1363(der: ByteArray): ByteArray {
    var at = 0
    fun byte(): Int { require(at < der.size); return der[at++].toInt() and 255 }
    require(byte() == 0x30)
    require(byte() == der.size - 2)
    fun integer(): ByteArray {
        require(byte() == 2)
        val length = byte()
        require(length in 1..33 && at + length <= der.size)
        val raw = der.copyOfRange(at, at + length)
        at += length
        require(raw[0].toInt() and 128 == 0)
        val unsigned = if (raw.size > 1 && raw[0] == 0.toByte()) raw.drop(1).toByteArray() else raw
        require(unsigned.size <= 32)
        return ByteArray(32 - unsigned.size) + unsigned
    }
    val result = integer() + integer()
    require(at == der.size)
    return result
}

internal class RelayIdentity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(ALIAS)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
        }.generateKeyPair()
    }
    val publicKey: ByteArray get() = store.getCertificate(ALIAS).publicKey.encoded
    val phoneId: String get() = base64url(MessageDigest.getInstance("SHA-256").digest(publicKey))
    fun sign(origin: String, nonce: String): String = Signature.getInstance("SHA256withECDSA").run {
        initSign(store.getKey(ALIAS, null) as java.security.PrivateKey)
        update("handset-relay-v1\n$origin\n$phoneId\n$nonce".toByteArray(Charsets.UTF_8))
        base64url(derToP1363(sign()))
    }
    private companion object { const val ALIAS = "handset-relay-p256" }
}
