package ai.tushar.handset.events

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where phone events go: a Claude routine's fire URL, or any address that takes a JSON POST */
@Serializable
data class Destination(val url: String, val token: String? = null) {
    /** A Claude routine, which takes the event as `text` and needs its own headers */
    val routine: Boolean get() = ROUTINE.matches(url)

    /** What the Settings row shows: the routine's id, or the address without its scheme */
    val label: String
        get() = ROUTINE.matchEntire(url)?.let { "Claude routine ${it.groupValues[1]}" } ?: url.substringAfter("://").trimEnd('/')

    /** The headers each request carries, beside `Content-Type: application/json` */
    fun headers(): Map<String, String> = buildMap {
        token?.let { put("Authorization", "Bearer $it") }
        if (routine) put("anthropic-version", ANTHROPIC_VERSION)
    }

    companion object {
        private val ROUTINE = Regex("https://api\\.anthropic\\.com/v1/claude_code/routines/(trig_[A-Za-z0-9]+)/fire")

        /** The routine fire endpoint accepts every version from 2023-06-01 on and behaves the same in each */
        const val ANTHROPIC_VERSION = "2026-10-21"

        private val LOOPBACK = setOf("localhost", "127.0.0.1", "[::1]")

        /**
         * The destination for what the user typed, or why it can't be one. Plain http is allowed only to the phone
         * itself, so the token never crosses a network in the clear.
         */
        fun parse(url: String, token: String): Result<Destination> {
            val trimmed = url.trim()
            val uri = runCatching { URI(trimmed) }.getOrNull()
            val host = uri?.host?.lowercase()
            val ok = host != null && (uri.scheme == "https" || (uri.scheme == "http" && host in LOOPBACK))
            if (!ok) return Result.failure(IllegalArgumentException("Use an address that starts with https://. Plain http works only for this phone itself."))
            val destination = Destination(trimmed, token.trim().removePrefix("Bearer ").trim().ifEmpty { null })
            if (destination.routine && destination.token == null) return Result.failure(IllegalArgumentException("A Claude routine needs its token, which starts with sk-ant-oat01-."))
            return Result.success(destination)
        }
    }
}

/** Encrypts and decrypts small secrets on the phone */
interface Sealer {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with a key that lives in the Android Keystore and never leaves it */
class KeystoreSealer(private val alias: String = "phone_events") : Sealer {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES)) }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** The one destination, kept encrypted in [preferences] */
class DestinationStore(private val preferences: SharedPreferences, private val sealer: Sealer) {
    @Volatile private var cached: Destination? = null

    fun load(): Destination? {
        cached?.let { return it }
        val sealed = preferences.getString(KEY, null) ?: return null
        return runCatching { Json.decodeFromString<Destination>(sealer.open(Base64.decode(sealed, Base64.NO_WRAP)).decodeToString()) }
            .getOrNull()
            .also { cached = it }
    }

    fun save(destination: Destination?) {
        cached = destination
        val edit = preferences.edit()
        if (destination == null) {
            edit.remove(KEY)
        } else {
            edit.putString(KEY, Base64.encodeToString(sealer.seal(Json.encodeToString(Destination.serializer(), destination).encodeToByteArray()), Base64.NO_WRAP))
        }
        edit.apply()
    }

    private companion object {
        const val KEY = "destination"
    }
}
