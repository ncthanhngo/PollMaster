package com.ncthanhngo.pollmaster.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.ncthanhngo.pollmaster.core.model.PollJson
import com.ncthanhngo.pollmaster.core.project.ErpEnvelope
import com.ncthanhngo.pollmaster.core.project.ErpPage
import com.ncthanhngo.pollmaster.core.project.ErpProject
import com.ncthanhngo.pollmaster.core.project.ErpProjectSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Signed-in ERP user, shown in Cài đặt. */
@Serializable
data class ErpAccount(val name: String, val email: String, val org: String? = null)

/** Error with a message for the user; [sessionExpired] when the refresh token was refused (sign in again). */
class ErpException(message: String, val sessionExpired: Boolean = false) : Exception(message)

/**
 * ERP REST API (same backend and login as the ERP mobile app; EVSELab and tenant accounts such as Kimsen alike).
 * Access token 4 h, refresh token 30 days and rotated on every refresh; both encrypted with an Android Keystore key.
 */
class ErpClient(context: Context) {
    private val tokens = TokenStore(context)
    private val refreshLock = Mutex()

    val hasSession: Boolean get() = tokens.refresh != null

    @Serializable
    private data class Tokens(val token: String, @SerialName("refresh_token") val refresh: String)

    @Serializable
    private data class LoginData(
        val token: String,
        @SerialName("refresh_token") val refresh: String,
        val user: User? = null,
        @SerialName("must_change_password") val mustChangePassword: Boolean = false,
    ) {
        @Serializable
        data class User(
            val name: String? = null,
            @SerialName("display_name") val displayName: String? = null,
            val email: String? = null,
            @SerialName("org_name") val org: String? = null,
        )
    }

    suspend fun login(email: String, password: String): ErpAccount {
        val body = buildJsonObject { put("email", email); put("password", password) }.toString()
        val d = try {
            send("auth/login", LoginData.serializer(), "POST", body)
        } catch (e: HttpStatus) {
            // the backend answers a wrong password or a malformed one with 400/401 and an English text
            throw if (e.status == 400 || e.status == 401) ErpException("Sai email hoặc mật khẩu.") else e.toUser()
        }
        if (d.mustChangePassword) throw ErpException("Tài khoản cần đổi mật khẩu. Hãy đăng nhập ERP trên web hoặc app ERP để đổi, rồi đăng nhập lại.")
        tokens.save(d.token, d.refresh)
        val name = listOf(d.user?.displayName, d.user?.name).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
        return ErpAccount(name ?: email, d.user?.email ?: email, d.user?.org?.takeIf { it.isNotEmpty() })
    }

    /** Revokes the refresh token (best effort) and forgets both tokens. */
    suspend fun logout() {
        tokens.refresh?.let { r ->
            runCatching { send("auth/logout", JsonElement.serializer(), "POST", buildJsonObject { put("refresh_token", r) }.toString()) }
        }
        tokens.clear()
    }

    /** Active projects the user may see (the backend scopes them by permission and organisation), every page. */
    suspend fun projects(): List<ErpProject> {
        val all = mutableListOf<ErpProject>()
        var page = 1
        while (true) {
            val p = authorized("projects?status=active&page_size=100&page=$page", ErpPage.serializer(ErpProject.serializer()))
            all += p.items
            if (p.items.isEmpty() || all.size >= p.total) return all
            page++
        }
    }

    /** Project avatar / customer logo (public static file). */
    suspend fun file(path: String): ByteArray = withContext(Dispatchers.IO) {
        val c = URL(ErpProjectSync.fileUrl(path, HOST)).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    // plumbing

    private class HttpStatus(val status: Int, val error: String?) : Exception(error) {
        /** Machine codes (SCREAMING_SNAKE) are not for people; the backend's Vietnamese sentences are. */
        fun toUser() = ErpException(error?.takeIf { it != it.uppercase() } ?: "Máy chủ ERP báo lỗi $status.")
    }

    /** GET with the access token; a 401 refreshes once (shared by concurrent calls) and retries. */
    private suspend fun <T> authorized(path: String, ser: KSerializer<T>): T {
        val used = tokens.access
        return try {
            send(path, ser, token = used)
        } catch (e: HttpStatus) {
            if (e.status != 401) throw e.toUser()
            refresh(used)
            try { send(path, ser, token = tokens.access) } catch (e2: HttpStatus) { throw e2.toUser() }
        }
    }

    /** Only a refused token (400/401) ends the session; network trouble keeps it for the next try. */
    private suspend fun refresh(failedAccess: String?) = refreshLock.withLock {
        if (tokens.access != failedAccess) return@withLock // another call refreshed meanwhile
        val r = tokens.refresh ?: throw ErpException(EXPIRED, sessionExpired = true)
        try {
            val t = send("auth/refresh", Tokens.serializer(), "POST", buildJsonObject { put("refresh_token", r) }.toString())
            tokens.save(t.token, t.refresh)
        } catch (e: HttpStatus) {
            if (e.status == 400 || e.status == 401) {
                tokens.clear()
                throw ErpException(EXPIRED, sessionExpired = true)
            }
            throw e.toUser()
        }
    }

    private suspend fun <T> send(path: String, ser: KSerializer<T>, method: String = "GET", body: String? = null, token: String? = null): T =
        withContext(Dispatchers.IO) {
            val c = URL("$HOST/api/v1/$path").openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = TIMEOUT; c.readTimeout = TIMEOUT
                // The backend applies the per-organisation "may use the mobile app" switch to requests marked like this.
                c.setRequestProperty("X-Client-App", "evs-mobile")
                c.setRequestProperty("Accept", "application/json")
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = c.responseCode
                val text = (if (status in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().decodeToString() }.orEmpty()
                if (status !in 200..299) {
                    val error = runCatching { PollJson.decodeFromString(ErpEnvelope.serializer(JsonElement.serializer()), text).error }.getOrNull()
                    throw HttpStatus(status, error)
                }
                runCatching { PollJson.decodeFromString(ErpEnvelope.serializer(ser), text).data }.getOrNull()
                    ?: throw ErpException("Máy chủ ERP trả về dữ liệu không đọc được.")
            } finally { c.disconnect() }
        }

    companion object {
        /** Uploaded files (`/uploads/…`) are served from the host root, the API under `/api/v1`. */
        const val HOST = "https://api.evselab.com"
        private const val TIMEOUT = 20_000
        private const val EXPIRED = "Phiên đăng nhập ERP đã hết hạn. Hãy đăng nhập lại."
    }
}

/** Access and refresh tokens, AES-GCM encrypted with a key that never leaves the Android Keystore. */
private class TokenStore(context: Context) {
    private val prefs = context.getSharedPreferences("pollmaster_erp", Context.MODE_PRIVATE)

    val access: String? get() = read("access")
    val refresh: String? get() = read("refresh")

    fun save(access: String, refresh: String) {
        prefs.edit().putString("access", encrypt(access)).putString("refresh", encrypt(refresh)).apply()
    }

    fun clear() = prefs.edit().remove("access").remove("refresh").apply()

    private fun read(name: String): String? = prefs.getString(name, null)?.let { runCatching { decrypt(it) }.getOrNull() }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build(),
            )
        }.generateKey()
    }

    private fun encrypt(text: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12)) }
        return c.doFinal(bytes, 12, bytes.size - 12).decodeToString()
    }

    private companion object {
        const val ALIAS = "pollmaster-erp-tokens"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
