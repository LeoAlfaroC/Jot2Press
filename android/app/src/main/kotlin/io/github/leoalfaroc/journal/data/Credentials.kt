package io.github.leoalfaroc.journal.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val siteUrl: String, val username: String, val password: String) {
    /** Never prints the password, so it cannot end up in a log. */
    override fun toString(): String = "Credentials($siteUrl, $username)"
}

/**
 * The site address as the app stores it, or null when it is not an https address.
 * An address typed without a scheme is taken as https.
 */
fun normalizeSiteUrl(input: String): String? {
    val typed = input.trim()
    val address = if ("://" in typed) typed else "https://$typed"
    val url = address.toHttpUrlOrNull() ?: return null
    if (url.scheme != "https" || url.query != null || url.fragment != null) return null
    return url.toString().trimEnd('/')
}

/** Encrypts and decrypts with a key the rest of the app never sees. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray
    fun open(sealed: ByteArray): ByteArray
}

/** AES-GCM with a key that is created in, and never leaves, the Android Keystore. */
class KeystoreBox : SecretBox {
    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(spec)
            generateKey()
        }
    }

    override fun seal(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun open(sealed: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        }
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "journal_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Keeps the site URL, the username and the application password, encrypted as one value. */
class CredentialStore(private val store: DataStore<Preferences>, private val box: SecretBox) {

    /** Null when nothing was saved, or when what was saved can no longer be decrypted. */
    suspend fun get(): Credentials? {
        val sealed = store.data.first()[SEALED] ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val json = Json.parseToJsonElement(String(box.open(Base64.getDecoder().decode(sealed)), Charsets.UTF_8)).jsonObject
                Credentials(
                    siteUrl = json.getValue("site_url").jsonPrimitive.content,
                    username = json.getValue("username").jsonPrimitive.content,
                    password = json.getValue("password").jsonPrimitive.content,
                )
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun save(credentials: Credentials) {
        val plain = buildJsonObject {
            put("site_url", credentials.siteUrl)
            put("username", credentials.username)
            put("password", credentials.password)
        }.toString().toByteArray(Charsets.UTF_8)
        val sealed = withContext(Dispatchers.IO) { Base64.getEncoder().encodeToString(box.seal(plain)) }
        store.edit { it[SEALED] = sealed }
    }

    private companion object {
        val SEALED = stringPreferencesKey("credentials")
    }
}
