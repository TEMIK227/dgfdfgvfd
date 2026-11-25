package com.rasp.app

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection

object Api {
    @Volatile
    var lastError: String = ""

    private val base: String get() = Config.SUPABASE_URL.trimEnd('/')
    private fun prefs(c: Context) = c.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun configured(): Boolean = !Config.SUPABASE_URL.contains("ВСТАВЬ") && !Config.SUPABASE_KEY.contains("ВСТАВЬ")
    fun loggedIn(c: Context): Boolean = prefs(c).getString("refresh", null) != null
    fun userId(c: Context): String = prefs(c).getString("uid", "") ?: ""
    fun nick(c: Context): String = prefs(c).getString("nick", "") ?: ""
    fun logout(c: Context) = prefs(c).edit().clear().apply()

    private fun email(nick: String) = nick.lowercase() + "@" + Config.EMAIL_DOMAIN

    private fun http(method: String, path: String, token: String?, body: String?, prefer: String? = null): Pair<Int, String> {
        return try {
            val con = java.net.URL(base + path).openConnection() as HttpURLConnection
            con.requestMethod = method
            con.connectTimeout = 8000
            con.readTimeout = 10000
            con.setRequestProperty("apikey", Config.SUPABASE_KEY)
            // Новые ключи вида sb_publishable_... не являются JWT и в Authorization не отправляются
            if (token != null || Config.SUPABASE_KEY.startsWith("eyJ")) {
                con.setRequestProperty("Authorization", "Bearer " + (token ?: Config.SUPABASE_KEY))
            }
            con.setRequestProperty("Content-Type", "application/json")
            if (prefer != null) con.setRequestProperty("Prefer", prefer)
            if (body != null) {
                con.doOutput = true
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = con.responseCode
            val stream = if (code >= 400) con.errorStream else con.inputStream
            Pair(code, stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: "")
        } catch (e: Exception) {
            Pair(0, e.message ?: "нет связи")
        }
    }

    private fun saveSession(c: Context, j: JSONObject) {
        val user = j.getJSONObject("user")
        val e = prefs(c).edit()
        e.putString("access", j.getString("access_token"))
        e.putString("refresh", j.getString("refresh_token"))
        e.putLong("exp", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000)
        e.putString("uid", user.getString("id"))
        val n = user.optJSONObject("user_metadata")?.optString("nick") ?: ""
        if (n.isNotEmpty()) e.putString("nick", n)
        e.apply()
    }

    /** null = успех, иначе текст ошибки */
    fun signUp(c: Context, nick: String, pass: String): String? {
        val body = JSONObject().put("email", email(nick)).put("password", pass)
            .put("data", JSONObject().put("nick", nick.lowercase())).toString()
        val (code, text) = http("POST", "/auth/v1/signup", null, body)
        if (code == 0) return "Нет связи с сервером"
        if (code in 200..299) {
            return try {
                val j = JSONObject(text)
                if (j.has("access_token")) {
                    saveSession(c, j)
                    if (prefs(c).getString("nick", "").isNullOrEmpty()) prefs(c).edit().putString("nick", nick.lowercase()).apply()
                    null
                } else "В Supabase выключи подтверждение почты (Confirm email)"
            } catch (e: Exception) {
                "Ответ сервера не распознан"
            }
        }
        return if (text.contains("already", true) || text.contains("exists", true)) "Этот ник уже занят"
        else "Ошибка регистрации: " + text.take(150)
    }

    fun signIn(c: Context, nick: String, pass: String): String? {
        val body = JSONObject().put("email", email(nick)).put("password", pass).toString()
        val (code, text) = http("POST", "/auth/v1/token?grant_type=password", null, body)
        if (code == 0) return "Нет связи с сервером"
        if (code in 200..299) {
            return try {
                saveSession(c, JSONObject(text))
                prefs(c).edit().putString("nick", nick.lowercase()).apply()
                null
            } catch (e: Exception) {
                "Ответ сервера не распознан"
            }
        }
        return if (code == 400) "Неверный ник или пароль" else "Ошибка входа: " + text.take(150)
    }

    @Synchronized
    private fun token(c: Context): String? {
        val p = prefs(c)
        if (System.currentTimeMillis() < p.getLong("exp", 0) - 60000) return p.getString("access", null)
        val rt = p.getString("refresh", null) ?: return null
        val (code, text) = http("POST", "/auth/v1/token?grant_type=refresh_token", null, JSONObject().put("refresh_token", rt).toString())
        if (code in 200..299) {
            return try {
                saveSession(c, JSONObject(text))
                p.getString("access", null)
            } catch (e: Exception) {
                null
            }
        }
        lastError = if (code == 0) "нет связи" else "нужно войти заново"
        return null
    }

    fun get(c: Context, path: String): String? {
        val t = token(c)
        if (t == null) {
            if (lastError.isEmpty()) lastError = "нужно войти заново"
            return null
        }
        val (code, text) = http("GET", "/rest/v1/$path", t, null)
        if (code in 200..299) return text
        lastError = text
        return null
    }

    fun post(c: Context, path: String, body: String, prefer: String? = null): Boolean {
        val t = token(c) ?: return false
        val (code, text) = http("POST", "/rest/v1/$path", t, body, prefer)
        if (code in 200..299) return true
        lastError = text
        return false
    }

    fun delete(c: Context, path: String): Boolean {
        val t = token(c) ?: return false
        val (code, text) = http("DELETE", "/rest/v1/$path", t, null)
        if (code in 200..299) return true
        lastError = text
        return false
    }

    fun rpc(c: Context, fn: String, body: String): Boolean = post(c, "rpc/$fn", body)
}
