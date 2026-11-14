package com.manajir.voicetest

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Email + password accounts through the Firebase Authentication REST API. */
object Auth {

    private const val PREFS = "auth"
    private val main = Handler(Looper.getMainLooper())

    private class ApiError(val code: String) : Exception(code)

    fun keyMissing(): Boolean = Config.FIREBASE_API_KEY.startsWith("PASTE")

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isLoggedIn(c: Context): Boolean = prefs(c).getString("refresh", null) != null

    fun email(c: Context): String = prefs(c).getString("email", "") ?: ""

    fun signOut(c: Context) {
        prefs(c).edit().clear().apply()
        Access.clear(c)
    }

    /** Firebase user id, read from the saved ID token. */
    fun uid(c: Context): String {
        val t = prefs(c).getString("id", "") ?: ""
        return try {
            val p = t.split(".")[1]
            val bytes = android.util.Base64.decode(
                p, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
            )
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            j.optString("user_id", j.optString("sub", ""))
        } catch (e: Exception) {
            ""
        }
    }

    /** A fresh ID token. Blocking: call from a background thread. */
    fun idToken(c: Context): String {
        ensureToken(c)
        return prefs(c).getString("id", "") ?: ""
    }

    // ---------- low-level HTTP ----------

    private fun post(url: String, body: String, contentType: String): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val json = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
            if (code !in 200..299) {
                val err = json.optJSONObject("error")
                val msg = err?.optString("message", "") ?: ""
                throw ApiError(if (msg.isEmpty()) "HTTP_$code" else msg.substringBefore(" :").trim())
            }
            return json
        } finally {
            conn.disconnect()
        }
    }

    private fun identity(path: String, json: JSONObject): JSONObject =
        post(
            "https://identitytoolkit.googleapis.com/v1/" + path + "?key=" + Config.FIREBASE_API_KEY,
            json.toString(),
            "application/json"
        )

    private fun saveSession(c: Context, j: JSONObject, email: String?) {
        val e = prefs(c).edit()
        e.putString("id", j.optString("idToken", j.optString("id_token", "")))
        e.putString("refresh", j.optString("refreshToken", j.optString("refresh_token", "")))
        val ttl = j.optString("expiresIn", j.optString("expires_in", "3600")).toLongOrNull() ?: 3600L
        e.putLong("exp", System.currentTimeMillis() + ttl * 1000)
        if (email != null) e.putString("email", email)
        e.apply()
    }

    private fun friendly(code: String): String = when {
        code.startsWith("EMAIL_EXISTS") -> Lang.en(R.string.a_email_exists)
        code.startsWith("INVALID_LOGIN_CREDENTIALS") ||
            code.startsWith("INVALID_PASSWORD") ||
            code.startsWith("EMAIL_NOT_FOUND") -> Lang.en(R.string.a_bad_creds)
        code.startsWith("WEAK_PASSWORD") -> Lang.en(R.string.a_weak)
        code.startsWith("INVALID_EMAIL") -> Lang.en(R.string.a_bad_email)
        code.startsWith("MISSING_PASSWORD") -> Lang.en(R.string.a_missing_pass)
        code.startsWith("TOO_MANY_ATTEMPTS") -> Lang.en(R.string.a_too_many)
        code.startsWith("USER_DISABLED") -> Lang.en(R.string.a_disabled)
        code.startsWith("OPERATION_NOT_ALLOWED") ||
            code.startsWith("PASSWORD_LOGIN_DISABLED") ->
            Lang.en(R.string.a_not_enabled)
        code.startsWith("API key not valid") || code.startsWith("API_KEY") ->
            Lang.en(R.string.a_bad_key)
        else -> Lang.en(R.string.error_fmt, code)
    }

    private fun runAsync(block: () -> Unit, cb: (String?) -> Unit) {
        Thread {
            val err: String? = try {
                block()
                null
            } catch (e: ApiError) {
                friendly(e.code)
            } catch (e: IOException) {
                Lang.en(R.string.a_no_internet)
            } catch (e: Exception) {
                Lang.en(R.string.error_fmt, e.javaClass.simpleName)
            }
            main.post { cb(err) }
        }.start()
    }

    // ---------- public API (callbacks run on the main thread; null = success) ----------

    fun signUp(c: Context, email: String, password: String, cb: (String?) -> Unit) {
        runAsync({
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .put("returnSecureToken", true)
            saveSession(c, identity("accounts:signUp", body), email)
        }, cb)
    }

    fun signIn(c: Context, email: String, password: String, cb: (String?) -> Unit) {
        runAsync({
            val body = JSONObject()
                .put("email", email)
                .put("password", password)
                .put("returnSecureToken", true)
            saveSession(c, identity("accounts:signInWithPassword", body), email)
        }, cb)
    }

    fun resetPassword(email: String, cb: (String?) -> Unit) {
        runAsync({
            val body = JSONObject()
                .put("requestType", "PASSWORD_RESET")
                .put("email", email)
            identity("accounts:sendOobCode", body)
        }, cb)
    }

    private fun ensureToken(c: Context) {
        val p = prefs(c)
        val exp = p.getLong("exp", 0L)
        if (System.currentTimeMillis() < exp - 60000L) return
        val refresh = p.getString("refresh", null) ?: throw ApiError("INVALID_REFRESH_TOKEN")
        val body = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(refresh, "UTF-8")
        val j = post(
            "https://securetoken.googleapis.com/v1/token?key=" + Config.FIREBASE_API_KEY,
            body,
            "application/x-www-form-urlencoded"
        )
        saveSession(c, j, null)
    }

    /**
     * Checks that the saved account still exists and is enabled.
     * Offline or unknown errors count as valid, so the app still opens without internet.
     */
    fun validate(c: Context, cb: (Boolean) -> Unit) {
        Thread {
            var valid = true
            try {
                ensureToken(c)
                val id = prefs(c).getString("id", "") ?: ""
                identity("accounts:lookup", JSONObject().put("idToken", id))
            } catch (e: ApiError) {
                val code = e.code
                if (code.startsWith("USER_NOT_FOUND") || code.startsWith("USER_DISABLED") ||
                    code.startsWith("TOKEN_EXPIRED") || code.startsWith("INVALID_REFRESH_TOKEN") ||
                    code.startsWith("INVALID_ID_TOKEN") || code.startsWith("CREDENTIAL_TOO_OLD")
                ) {
                    valid = false
                }
            } catch (e: Exception) {
                valid = true
            }
            main.post { cb(valid) }
        }.start()
    }
}
