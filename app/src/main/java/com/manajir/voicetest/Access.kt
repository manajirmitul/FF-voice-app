package com.manajir.voicetest

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Subscription access. Data lives in Firestore:
 *   requests/{uid}  - the user's payment request (written by the app, approved by the owner)
 *   licenses/{uid}  - created by the owner after approval; holds the key and the plan length
 * The app opens only while licenses/{uid} is activated and not expired (server time).
 */
object Access {

    const val ACTIVE = 0
    const val NEED_PLAN = 1
    const val NEED_KEY = 2
    const val ERROR = 3

    class State(val code: Int, val note: String = "")
    class Plan(val id: String, val months: Int, val days: Int, val price: Int)
    private class HttpErr(val code: Int, msg: String) : Exception(msg)
    private class Resp(val json: JSONObject, val serverTime: Long)

    val PLANS = listOf(
        Plan("1m", 1, 30, 100),
        Plan("3m", 3, 90, 200),
        Plan("12m", 12, 365, 500)
    )

    private const val PREFS = "access"
    private val ui = Handler(Looper.getMainLooper())

    // ---------- local cache (lets the app open offline until the plan ends) ----------

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
    }

    private fun save(c: Context, uid: String, exp: Long, serverNow: Long) {
        prefs(c).edit().putString("uid", uid).putLong("exp", exp).putLong("seen", serverNow).apply()
    }

    /** True if a plan verified earlier is still running (and the phone clock was not turned back). */
    fun cachedActive(c: Context): Boolean {
        val p = prefs(c)
        val uid = Auth.uid(c)
        if (uid.isEmpty() || p.getString("uid", "") != uid) return false
        val now = System.currentTimeMillis()
        return now < p.getLong("exp", 0L) && now >= p.getLong("seen", 0L) - 120000L
    }

    fun expiresAt(c: Context): Long = prefs(c).getLong("exp", 0L)

    // ---------- Firestore REST ----------

    private fun fmt(ms: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(ms))
    }

    private fun parseTs(s: String): Long {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.parse(s.replace(Regex("\\.\\d+Z$"), "Z"))!!.time
    }

    private fun parseHttpDate(s: String?): Long? {
        if (s == null) return null
        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).parse(s)?.time
        } catch (e: Exception) {
            null
        }
    }

    private fun sv(v: String) = JSONObject().put("stringValue", v)
    private fun iv(v: Long) = JSONObject().put("integerValue", v.toString())
    private fun tv(ms: Long) = JSONObject().put("timestampValue", fmt(ms))

    private fun call(c: Context, method: String, path: String, body: JSONObject?): Resp {
        val token = Auth.idToken(c)
        val url = URL(
            "https://firestore.googleapis.com/v1/projects/" + Config.FIREBASE_PROJECT_ID +
                "/databases/(default)/documents/" + path
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.setRequestProperty("Authorization", "Bearer $token")
            if (method == "GET") {
                conn.requestMethod = "GET"
            } else {
                // HttpURLConnection has no PATCH: Google APIs accept a method override
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", method)
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.doOutput = true
                conn.outputStream.use { it.write((body ?: JSONObject()).toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpErr(code, text.take(200))
            val server = parseHttpDate(conn.getHeaderField("Date")) ?: System.currentTimeMillis()
            return Resp(JSONObject(if (text.isEmpty()) "{}" else text), server)
        } finally {
            conn.disconnect()
        }
    }

    private fun field(j: JSONObject, name: String): JSONObject? = j.optJSONObject("fields")?.optJSONObject(name)

    // ---------- checks ----------

    private fun doCheck(c: Context): State {
        val uid = Auth.uid(c)
        if (uid.isEmpty()) return State(ERROR, "auth")
        var lic: Resp? = null
        try {
            lic = call(c, "GET", "licenses/$uid", null)
        } catch (e: HttpErr) {
            if (e.code != 404) throw e
        }
        if (lic != null) {
            val act = field(lic.json, "activatedAt")
            val exp = field(lic.json, "expiresAt")
            if (act == null || !act.has("timestampValue") || exp == null || !exp.has("timestampValue")) {
                return State(NEED_KEY, "ready")
            }
            val expMs = parseTs(exp.getString("timestampValue"))
            if (lic.serverTime < expMs) {
                save(c, uid, expMs, lic.serverTime)
                return State(ACTIVE)
            }
            clear(c)
        }
        var status = ""
        try {
            val r = call(c, "GET", "requests/$uid", null)
            status = field(r.json, "status")?.optString("stringValue", "") ?: ""
        } catch (e: HttpErr) {
            if (e.code != 404) throw e
        }
        return when {
            status == "pending" -> State(NEED_KEY, "pending")
            status == "rejected" -> State(NEED_PLAN, "rejected")
            lic != null -> State(NEED_PLAN, "expired")
            else -> State(NEED_PLAN, "")
        }
    }

    private fun failure(c: Context, e: Throwable): State = when {
        e is IOException -> if (cachedActive(c)) State(ACTIVE, "offline") else State(ERROR, "offline")
        e is HttpErr -> State(ERROR, "http " + e.code)
        else -> State(ERROR, e.javaClass.simpleName)
    }

    fun check(c: Context, cb: (State) -> Unit) {
        Thread {
            val st = try {
                doCheck(c)
            } catch (e: Exception) {
                failure(c, e)
            }
            ui.post { cb(st) }
        }.start()
    }

    private fun errText(e: Throwable): String = when (e) {
        is IOException -> Lang.get(R.string.no_internet)
        is HttpErr -> Lang.get(R.string.error_fmt, "HTTP " + e.code)
        else -> Lang.get(R.string.error_fmt, e.javaClass.simpleName)
    }

    /** Saves the payment request for the owner to verify. cb(null) = success, else a message. */
    fun submitRequest(c: Context, plan: Plan, method: String, trx: String, cb: (String?) -> Unit) {
        Thread {
            val err = try {
                val uid = Auth.uid(c)
                val f = JSONObject()
                    .put("uid", sv(uid))
                    .put("email", sv(Auth.email(c)))
                    .put("plan", sv(plan.id))
                    .put("months", iv(plan.months.toLong()))
                    .put("amount", iv(plan.price.toLong()))
                    .put("method", sv(method))
                    .put("trx", sv(trx))
                    .put("status", sv("pending"))
                    .put("createdAt", tv(System.currentTimeMillis()))
                call(c, "PATCH", "requests/$uid", JSONObject().put("fields", f))
                null
            } catch (e: Exception) {
                errText(e)
            }
            ui.post { cb(err) }
        }.start()
    }

    private fun norm(s: String) = s.uppercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    /** Checks the key against the owner-issued license and starts the plan. cb(null) = success. */
    fun activate(c: Context, input: String, cb: (String?) -> Unit) {
        Thread {
            val err: String? = try {
                val uid = Auth.uid(c)
                var lic: Resp? = null
                try {
                    lic = call(c, "GET", "licenses/$uid", null)
                } catch (e: HttpErr) {
                    if (e.code != 404) throw e
                }
                val savedKey = lic?.let { field(it.json, "key")?.optString("stringValue", "") } ?: ""
                if (lic == null || savedKey.isEmpty() || norm(savedKey) != norm(input)) {
                    Lang.get(R.string.key_wrong)
                } else {
                    val act = field(lic.json, "activatedAt")
                    if (act != null && act.has("timestampValue")) {
                        // already started earlier: just re-check the end date
                        val st = doCheck(c)
                        if (st.code == ACTIVE) null else Lang.get(R.string.key_wrong)
                    } else {
                        val days = field(lic.json, "days")?.optString("integerValue", "30")?.toLongOrNull() ?: 30L
                        val now = lic.serverTime
                        val exp = now + days * 86400000L
                        val f = JSONObject().put("activatedAt", tv(now)).put("expiresAt", tv(exp))
                        call(
                            c, "PATCH",
                            "licenses/$uid?updateMask.fieldPaths=activatedAt&updateMask.fieldPaths=expiresAt",
                            JSONObject().put("fields", f)
                        )
                        save(c, uid, exp, now)
                        null
                    }
                }
            } catch (e: Exception) {
                errText(e)
            }
            ui.post { cb(err) }
        }.start()
    }
}
