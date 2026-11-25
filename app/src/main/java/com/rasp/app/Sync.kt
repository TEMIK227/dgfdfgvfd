package com.rasp.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate

object Sync {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun itemJson(kind: String, ltype: String?, title: String, place: String?, grp: String?, subgroup: String?,
                 date: String?, start: String?, end: String?, weekday: Int?, permanent: Boolean = false): JSONObject {
        val o = JSONObject()
        o.put("kind", kind)
        o.put("ltype", ltype ?: JSONObject.NULL)
        o.put("title", title)
        o.put("place", place ?: JSONObject.NULL)
        o.put("grp", grp ?: JSONObject.NULL)
        o.put("subgroup", subgroup ?: JSONObject.NULL)
        o.put("date", date ?: JSONObject.NULL)
        o.put("start_time", start ?: JSONObject.NULL)
        o.put("end_time", end ?: JSONObject.NULL)
        o.put("weekday", weekday ?: JSONObject.NULL)
        o.put("permanent", permanent)
        return o
    }

    /** Скачивает с сервера свои данные в локальное зеркало. */
    fun pull(c: Context, withFriends: Boolean): Boolean {
        if (!Api.loggedIn(c)) return false
        val uid = Api.userId(c)
        val items = Api.get(c, "items?select=*&user_id=eq.$uid&kind=neq.study") ?: return false
        val notes = Api.get(c, "notes?select=*&user_id=eq.$uid") ?: return false
        val hw = Api.get(c, "homework?select=*&user_id=eq.$uid") ?: return false
        val prof = Api.get(c, "profiles?select=nick,is_teacher&id=eq.$uid") ?: return false
        Store.saveRaw(c, "items", items)
        Store.saveRaw(c, "notes", notes)
        Store.saveRaw(c, "hw", hw)
        try {
            val a = JSONArray(prof)
            if (a.length() > 0) Store.setTeacher(c, a.getJSONObject(0).optBoolean("is_teacher", false))
        } catch (e: Exception) {
        }
        if (withFriends) pullFriends(c)
        return true
    }

    fun pullFriends(c: Context): Boolean {
        if (!Api.loggedIn(c)) return false
        val me = Api.userId(c)
        val raw = Api.get(c, "friendships?select=*") ?: return false
        val rows = JSONArray(raw)
        if (rows.length() == 0) {
            Store.saveRaw(c, "friends", "[]")
            return true
        }
        val ids = (0 until rows.length()).map {
            val o = rows.getJSONObject(it)
            if (o.getString("requester") == me) o.getString("addressee") else o.getString("requester")
        }.distinct()
        val profs = Api.get(c, "profiles?select=id,nick&id=in.(" + ids.joinToString(",") + ")") ?: return false
        val nicks = HashMap<String, String>()
        val pa = JSONArray(profs)
        for (i in 0 until pa.length()) {
            val o = pa.getJSONObject(i)
            nicks[o.getString("id")] = o.getString("nick")
        }
        val out = JSONArray()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            val incoming = o.getString("addressee") == me
            val other = if (incoming) o.getString("requester") else o.getString("addressee")
            out.put(
                JSONObject().put("fid", o.getString("id")).put("uid", other).put("nick", nicks[other] ?: "?")
                    .put("accepted", o.getString("status") == "accepted").put("incoming", incoming)
            )
        }
        Store.saveRaw(c, "friends", out.toString())
        return true
    }

    // ---- дела, занятия, факультативы ----
    fun addItem(c: Context, kind: String, title: String, place: String?, date: String?, start: String?, end: String?, weekday: Int?, permanent: Boolean = false): Boolean {
        val o = itemJson(kind, null, title, place, null, null, date, start, end, weekday, permanent)
        return Api.post(c, "items", o.toString(), "return=minimal") && pull(c, false)
    }

    fun deleteItem(c: Context, id: String): Boolean = Api.delete(c, "items?id=eq.$id") && pull(c, false)

    /** Отметить дело выполненным (date) или вернуть в список (null). */
    fun setDone(c: Context, id: String, date: String?): Boolean =
        Api.rpc(c, "set_done", JSONObject().put("i", id).put("d", date ?: JSONObject.NULL).toString()) && pull(c, false)

    /** Удаляет вчерашние выполненные обычные дела без даты. */
    fun cleanupDone(c: Context): Boolean {
        val today = LocalDate.now()
        val stale = Store.items(c).any {
            it.kind == "todo" && it.date == null && it.weekday == null && !it.permanent && it.doneOn != null && it.doneOn < today.toString()
        }
        if (!stale) return true
        return Api.delete(c, "items?kind=eq.todo&permanent=eq.false&date=is.null&weekday=is.null&done_on=lt.$today") && pull(c, false)
    }

    // ---- заметки ----
    fun addNote(c: Context, date: String, body: String): Boolean =
        Api.post(c, "notes", JSONObject().put("date", date).put("body", body).toString(), "return=minimal") && pull(c, false)

    fun deleteNote(c: Context, id: String): Boolean = Api.delete(c, "notes?id=eq.$id") && pull(c, false)

    // ---- ДЗ: к конкретной паре (дата + начало + предмет) ----
    fun setHw(c: Context, date: String, start: String, subject: String, body: String): Boolean {
        val ok = if (body.isEmpty()) {
            Api.delete(c, "homework?date=eq.$date&start_time=eq.${enc(start)}&subject=eq.${enc(subject)}")
        } else {
            val o = JSONObject().put("date", date).put("start_time", start).put("subject", subject).put("body", body)
            Api.post(c, "homework?on_conflict=user_id,date,start_time,subject", o.toString(), "resolution=merge-duplicates,return=minimal")
        }
        return ok && pull(c, false)
    }

    // ---- профиль ----
    fun setTeacher(c: Context, t: Boolean): Boolean =
        Api.rpc(c, "set_teacher", JSONObject().put("t", t).toString()) && pull(c, false)

    // ---- расписание преподавателя из Excel ----
    fun importTeach(c: Context, list: List<Item>): Boolean {
        if (!Api.delete(c, "items?kind=eq.teach")) return false
        if (list.isNotEmpty()) {
            val arr = JSONArray()
            for (x in list) arr.put(itemJson("teach", x.ltype, x.title, x.place, x.grp, x.subgroup, x.date, x.start, x.end, null))
            if (!Api.post(c, "items", arr.toString(), "return=minimal")) return false
        }
        return pull(c, false)
    }

    fun clearTeach(c: Context): Boolean = Api.delete(c, "items?kind=eq.teach") && pull(c, false)

    // ---- свои учебные пары (для друзей) ----
    fun syncStudy(c: Context): Boolean {
        if (!Api.loggedIn(c)) return false
        val html = Store.html(c)
        if (html.isEmpty()) return true
        val from = LocalDate.now().minusDays(3)
        val to = LocalDate.now().plusDays(45)
        val ls = Schedule.studyWindow(html, Store.sub(c), from, to)
        val h = ls.joinToString("|") { it.hwKey + it.title + it.place }.hashCode().toString() + ":" + ls.size
        if (Store.studyHash(c) == h) return true
        if (!Api.delete(c, "items?kind=eq.study&date=gte.$from")) return false
        if (ls.isNotEmpty()) {
            val arr = JSONArray()
            for (l in ls) {
                arr.put(itemJson("study", null, l.title, l.place.ifEmpty { null }, null, null, l.date.toString(), l.start.toString(), l.end.toString(), null))
            }
            if (!Api.post(c, "items", arr.toString(), "return=minimal")) return false
        }
        Store.setStudyHash(c, h)
        return true
    }

    // ---- друзья ----
    fun addFriend(c: Context, nickRaw: String): Boolean {
        val nick = nickRaw.trim().lowercase()
        val found = Api.get(c, "profiles?select=id&nick=eq." + enc(nick)) ?: return false
        val a = JSONArray(found)
        if (a.length() == 0) {
            Api.lastError = "Пользователь с таким ником не найден"
            return false
        }
        val id = a.getJSONObject(0).getString("id")
        if (id == Api.userId(c)) {
            Api.lastError = "Это твой собственный ник"
            return false
        }
        val ok = Api.post(c, "friendships", JSONObject().put("addressee", id).toString(), "return=minimal")
        if (!ok && Api.lastError.contains("duplicate")) Api.lastError = "Заявка уже отправлена"
        pullFriends(c)
        return ok
    }

    fun accept(c: Context, fid: String): Boolean =
        Api.rpc(c, "accept_friend", JSONObject().put("f", fid).toString()) && pullFriends(c)

    fun removeFriend(c: Context, fid: String): Boolean = Api.delete(c, "friendships?id=eq.$fid") && pullFriends(c)

    /** Что друг разрешил видеть: всё, у чего есть дата или повтор по дням недели. */
    fun friendItems(c: Context, uid: String): List<Item>? {
        val from = LocalDate.now().minusDays(7)
        val raw = Api.get(c, "items?select=*&user_id=eq.$uid&or=(date.gte.$from,weekday.not.is.null)") ?: return null
        return try { Store.parseItems(raw) } catch (e: Exception) { null }
    }
}
