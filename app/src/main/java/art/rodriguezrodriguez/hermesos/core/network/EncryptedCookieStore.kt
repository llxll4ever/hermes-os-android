package art.rodriguezrodriguez.hermesos.core.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

/**
 * Encrypted OkHttp CookieJar that persists Hermes Dashboard session cookies across app restarts.
 * Can also run purely in-memory (`EncryptedCookieStore(null)`) during JVM unit tests.
 */
class EncryptedCookieStore(context: Context?) : CookieJar {

    private val memoryCookies = ConcurrentHashMap<String, List<Cookie>>()
    private val prefs: SharedPreferences? = context?.let { ctx ->
        runCatching {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "hermes_os_secure_auth",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            ctx.getSharedPreferences("hermes_os_fallback_auth", Context.MODE_PRIVATE)
        }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val hostKey = url.host
        val existing = loadForRequest(url).associateBy { it.name }.toMutableMap()
        for (cookie in cookies) {
            if (cookie.expiresAt < System.currentTimeMillis()) {
                existing.remove(cookie.name)
            } else {
                existing[cookie.name] = cookie
            }
        }
        val merged = existing.values.toList()
        memoryCookies[hostKey] = merged
        prefs?.edit()?.putStringSet(
            "cookies_$hostKey",
            merged.map { serializeCookie(it) }.toSet()
        )?.apply()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val hostKey = url.host
        val now = System.currentTimeMillis()
        val cached = memoryCookies[hostKey] ?: run {
            val rawSet = prefs?.getStringSet("cookies_$hostKey", emptySet()).orEmpty()
            rawSet.mapNotNull { Cookie.parse(url, it) }.also {
                if (it.isNotEmpty()) memoryCookies[hostKey] = it
            }
        }
        return cached.filter { it.expiresAt >= now && it.matches(url) }
    }

    fun saveBearerOrSessionToken(key: String, token: String?) {
        if (token.isNullOrBlank()) {
            prefs?.edit()?.remove(key)?.apply()
        } else {
            prefs?.edit()?.putString(key, token)?.apply()
        }
    }

    fun readBearerOrSessionToken(key: String): String? = prefs?.getString(key, null)

    fun clearAll() {
        memoryCookies.clear()
        prefs?.edit()?.clear()?.apply()
    }

    private fun serializeCookie(c: Cookie): String = buildString {
        append(c.name).append('=').append(c.value)
        append("; Domain=").append(c.domain)
        append("; Path=").append(c.path)
        if (c.expiresAt != Long.MAX_VALUE) {
            append("; Max-Age=").append(((c.expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L))
        }
        if (c.secure) append("; Secure")
        if (c.httpOnly) append("; HttpOnly")
    }
}
