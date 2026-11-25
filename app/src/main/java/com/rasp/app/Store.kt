package com.rasp.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.nio.charset.Charset
import java.time.LocalDate

data class Item(
    val id: String,
    val kind: String,        // study / teach / extra / todo
    val ltype: String?,
    val title: String,
    val place: String?,
    val grp: String?,
    val subgroup: String?,
    val date: String?,
    val start: String?,
    val end: String?,
    val weekday: Int?,
    val doneOn: String? = null,
    val permanent: Boolean = false
)

data class Note(val id: String, val date: String, val body: String)
data class Friend(val fid: String, val uid: String, val nick: String, val accepted: Boolean, val incoming: Boolean)

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)

object Store {
    private fun p(c: Context) = c.getSharedPreferences("rasp", Context.MODE_PRIVATE)

    fun itemFrom(o: JSONObject) = Item(
        o.getString("id"), o.getString("kind"), o.str("ltype"), o.getString("title"), o.str("place"),
        o.str("grp"), o.str("subgroup"), o.str("date"), o.str("start_time"), o.str("end_time"),
        if (o.isNull("weekday")) null else o.getInt("weekday"),
        o.str("done_on"), o.optBoolean("permanent", false)
    )

    fun parseItems(text: String): List<Item> {
        val a = JSONArray(text)
        return (0 until a.length()).map { itemFrom(a.getJSONObject(it)) }
    }

    fun saveRaw(c: Context, key: String, text: String) = p(c).edit().putString(key, text).apply()
    private fun raw(c: Context, key: String): String = p(c).getString(key, "[]") ?: "[]"

    // ---- зеркало данных пользователя ----
    fun items(c: Context): List<Item> = try { parseItems(raw(c, "items")) } catch (e: Exception) { emptyList() }

    fun notes(c: Context, d: LocalDate): List<Note> = try {
        val a = JSONArray(raw(c, "notes"))
        (0 until a.length()).map { a.getJSONObject(it) }
            .filter { it.getString("date") == d.toString() }
            .map { Note(it.getString("id"), it.getString("date"), it.getString("body")) }
    } catch (e: Exception) { emptyList() }

    /** ключ "дата|начало|предмет" -> текст ДЗ */
    fun hw(c: Context): Map<String, String> = try {
        val a = JSONArray(raw(c, "hw"))
        val m = HashMap<String, String>()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            m[o.getString("date") + "|" + o.getString("start_time") + "|" + o.getString("subject")] = o.getString("body")
        }
        m
    } catch (e: Exception) { emptyMap() }

    fun friends(c: Context): List<Friend> = try {
        val a = JSONArray(raw(c, "friends"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Friend(o.getString("fid"), o.getString("uid"), o.getString("nick"), o.getBoolean("accepted"), o.getBoolean("incoming"))
        }
    } catch (e: Exception) { emptyList() }

    fun dayTodos(c: Context, d: LocalDate): List<Item> = items(c)
        .filter { it.kind == "todo" && ((it.date != null && it.date == d.toString()) || (it.date == null && it.weekday == d.dayOfWeek.value)) }
        .sortedBy { it.start ?: "" }

    /** Активные дела без даты (не отмечены выполненными). */
    fun undated(c: Context): List<Item> =
        items(c).filter { it.kind == "todo" && it.date == null && it.weekday == null && it.doneOn == null }

    /** Сделанные: обычные живут до конца дня, вечные (permanent) ждут ручного удаления. */
    fun doneList(c: Context, today: LocalDate): List<Item> =
        items(c).filter {
            it.kind == "todo" && it.date == null && it.weekday == null && it.doneOn != null &&
                (it.permanent || it.doneOn == today.toString())
        }

    // ---- настройки ----
    fun url(c: Context): String = p(c).getString("url", Schedule.PAGE_URL) ?: Schedule.PAGE_URL
    fun setUrl(c: Context, u: String) {
        p(c).edit().putString("url", u).apply()
        cacheFile(c).delete()
    }

    fun sub(c: Context): Int = p(c).getInt("sub", 2)
    fun setSub(c: Context, v: Int) = p(c).edit().putInt("sub", v).apply()
    fun teacher(c: Context): Boolean = p(c).getBoolean("teacher", false)
    fun setTeacher(c: Context, v: Boolean) = p(c).edit().putBoolean("teacher", v).apply()
    fun studyHash(c: Context): String = p(c).getString("studyHash", "") ?: ""
    fun setStudyHash(c: Context, h: String) = p(c).edit().putString("studyHash", h).apply()

    fun clearAll(c: Context) {
        p(c).edit().clear().apply()
        cacheFile(c).delete()
    }

    // ---- кэш страницы расписания ----
    private fun cacheFile(c: Context) = File(c.filesDir, "page.html")
    fun html(c: Context): String = try { cacheFile(c).readText() } catch (e: Exception) { "" }
    fun fetchAndSave(c: Context) = cacheFile(c).writeText(download(c))

    private fun download(c: Context): String {
        val con = java.net.URL(url(c)).openConnection() as HttpURLConnection
        con.connectTimeout = 5000
        con.readTimeout = 6000
        con.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
        val bytes = con.inputStream.use { it.readBytes() }
        val ct = (con.contentType ?: "").lowercase()
        val head = String(bytes, 0, minOf(bytes.size, 2000), Charsets.ISO_8859_1).lowercase()
        val cs: Charset = when {
            ct.contains("utf-8") -> Charsets.UTF_8
            ct.contains("1251") -> Charset.forName("windows-1251")
            head.contains("utf-8") -> Charsets.UTF_8
            else -> Charset.forName("windows-1251")
        }
        return String(bytes, cs)
    }
}
