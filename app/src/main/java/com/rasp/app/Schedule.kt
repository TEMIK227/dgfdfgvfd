package com.rasp.app

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

data class Lesson(
    val date: LocalDate,
    val who: String,      // УЧУСЬ / ПРЕПОДАЮ / МОЁ
    val start: LocalTime,
    val end: LocalTime,
    val title: String,
    val place: String,
    val subject: String
) {
    val startAt: LocalDateTime get() = LocalDateTime.of(date, start)
    val endAt: LocalDateTime get() = LocalDateTime.of(date, end)
    /** ключ ДЗ: к конкретной паре */
    val hwKey: String get() = date.toString() + "|" + start.toString() + "|" + subject
}

object Schedule {
    const val PAGE_URL = "https://kuzstu.ru/web-content/sitecontent/studentu/raspisanie/%D0%98%D0%98%D0%B1-263.html"

    private val CI = setOf(RegexOption.IGNORE_CASE)
    private val MS = listOf("январ", "феврал", "март", "апрел", "ма[яй]", "июн", "июл", "август", "сентябр", "октябр", "ноябр", "декабр")
    private val TIME_RANGE = Regex("(\\d{1,2})[:;.](\\d{2})\\s*[-–]\\s*(\\d{1,2})[:;.](\\d{2})")
    private val ROOM = Regex("^(.*?)\\s+(\\d{3,4}[а-яА-Я]?|\\d\\s*лекц\\.?)$")
    private val FIO = Regex("[А-ЯЁA-Z][а-яёa-z-]+\\s+[А-ЯЁA-Z]\\.\\s*[А-ЯЁA-Z]\\.?")
    private val KIND = Regex("^(?:лек|лекция|пр|практика|лаб|лабораторная|сем|фак|факультатив|доп|л)(?:\\.\\s*|\\s+)", CI)
    private val SUBGR_TAIL = Regex("\\s*-?\\s*[12]\\s*п/г\\s*$")
    private val META1 = Regex("^(пары|время|неделя)", CI)
    private val META2 = Regex("^\\d{1,2}\\s*-\\s*[яйе]\\s*$", CI)
    private val META3 = Regex("^(пнд|втр|срд|чтв|птн|сбт|вск)\\b", CI)

    private fun clean(s: String): String = s
        .replace(Regex("<br\\s*/?>", CI), " · ")
        .replace(Regex("<[^>]*>"), " ")
        .replace(Regex("&nbsp;", CI), " ")
        .replace("\u00A0", " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .replace(Regex("(\\s*·\\s*)+"), " · ")
        .replace(Regex("^\\s*·\\s*|\\s*·\\s*$"), "")
        .trim()

    private fun isMeta(s: String): Boolean {
        val t = s.trim()
        return t.isEmpty() || META1.containsMatchIn(t) || META2.containsMatchIn(t) || META3.containsMatchIn(t)
    }

    private fun tables(html: String): List<List<List<String>>> {
        val out = mutableListOf<List<List<String>>>()
        for (t in Regex("<table[^>]*>[\\s\\S]*?</table>", CI).findAll(html)) {
            val rows = mutableListOf<List<String>>()
            for (tr in Regex("<tr[^>]*>[\\s\\S]*?</tr>", CI).findAll(t.value)) {
                val cells = Regex("<t[dh][^>]*>[\\s\\S]*?</t[dh]>", CI).findAll(tr.value).map { clean(it.value) }.toList()
                if (cells.isNotEmpty()) rows.add(cells)
            }
            if (rows.size >= 2) out.add(rows)
        }
        return out
    }

    private fun matchDate(cell: String, d: LocalDate): Boolean {
        val s = cell.lowercase()
        if (s.isEmpty()) return false
        val day = d.dayOfMonth
        val mo = d.monthValue
        val hasDay = Regex("(^|[^0-9])0?" + day + "([^0-9]|$)").containsMatchIn(s)
        val num = Regex("(^|[^0-9])0?" + day + "[./-]0?" + mo + "([^0-9]|$)").containsMatchIn(s)
        return (hasDay && Regex(MS[mo - 1], CI).containsMatchIn(s)) || num
    }

    /** Оставляем строки моей подгруппы и без пометки. */
    private fun subgroup(info: String, mine: Int): String {
        val s = info.trim()
        val other = 3 - mine
        val any = Regex("[12]\\s*п/г", CI)
        val m = Regex(mine.toString() + "\\s*п/г", CI)
        val o = Regex(other.toString() + "\\s*п/г", CI)
        if (!any.containsMatchIn(s)) return s
        if (m.containsMatchIn(s) && !o.containsMatchIn(s)) return s
        return s.split(" · ").filter { m.containsMatchIn(it) }.joinToString(" · ")
    }

    private fun noFio(info: String): String =
        info.split(" · ")
            .map { FIO.replace(it, " ").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    fun subjectKey(title: String): String =
        SUBGR_TAIL.replace(KIND.replace(title.trim(), ""), "").trim().trimEnd('-').trim()

    private fun studentDay(tabs: List<List<List<String>>>, d: LocalDate, mine: Int): List<Lesson> {
        for (rows in tabs) {
            val tr = rows.firstOrNull { r -> r.any { TIME_RANGE.containsMatchIn(it) } } ?: continue
            val dr = rows.firstOrNull { matchDate(it[0], d) } ?: continue
            val out = mutableListOf<Lesson>()
            for (i in 1 until maxOf(tr.size, dr.size)) {
                val raw = dr.getOrElse(i) { "" }.trim()
                val m = TIME_RANGE.find(tr.getOrElse(i) { "" }) ?: continue
                if (raw.isEmpty() || raw == "-" || raw == "—" || isMeta(raw)) continue
                val info = subgroup(raw, mine)
                if (info.isEmpty() || isMeta(info)) continue
                val parts = noFio(info).split(" · ")
                val first = parts[0]
                val rm = ROOM.find(first)
                val title = rm?.groupValues?.get(1) ?: first
                var place = rm?.groupValues?.get(2)?.let { "ауд. $it" } ?: ""
                val rest = parts.drop(1).joinToString(" · ")
                if (rest.isNotEmpty()) place = if (place.isEmpty()) rest else "$place · $rest"
                out.add(
                    Lesson(
                        d, "УЧУСЬ",
                        LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()),
                        LocalTime.of(m.groupValues[3].toInt(), m.groupValues[4].toInt()),
                        title, place, subjectKey(title)
                    )
                )
            }
            return out
        }
        return emptyList()
    }

    fun parseRange(s: String): Pair<String, String>? {
        val m = TIME_RANGE.find(s) ?: return null
        fun f(h: String, mi: String) = h.padStart(2, '0') + ":" + mi
        return Pair(f(m.groupValues[1], m.groupValues[2]), f(m.groupValues[3], m.groupValues[4]))
    }

    fun parseTime(s: String): String? {
        val m = Regex("(\\d{1,2})[:;.](\\d{2})").find(s) ?: return null
        return m.groupValues[1].padStart(2, '0') + ":" + m.groupValues[2]
    }

    /** Занятия препода и факультативы из своих записей. Дела без времени сюда не попадают. */
    private fun itemLessons(items: List<Item>, d: LocalDate, teacher: Boolean): List<Lesson> {
        val out = mutableListOf<Lesson>()
        for (x in items) {
            val st = x.start ?: continue
            val en = x.end ?: continue
            val onDay = if (x.date != null) x.date == d.toString() else x.weekday == d.dayOfWeek.value
            if (!onDay) continue
            try {
                if (x.kind == "teach" && teacher) {
                    val where = listOfNotNull(x.place?.let { "ауд. $it" }, x.grp, x.subgroup).joinToString(" · ")
                    out.add(Lesson(d, "ПРЕПОДАЮ", LocalTime.parse(st), LocalTime.parse(en), (x.ltype?.let { "$it. " } ?: "") + x.title, where, x.title))
                } else if (x.kind == "extra") {
                    out.add(Lesson(d, "МОЁ", LocalTime.parse(st), LocalTime.parse(en), x.title, x.place?.let { if (it.isEmpty()) "" else "ауд. $it" } ?: "", x.title))
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    private fun dayAll(tabs: List<List<List<String>>>, items: List<Item>, mine: Int, teacher: Boolean, d: LocalDate): List<Lesson> {
        val out = mutableListOf<Lesson>()
        out.addAll(studentDay(tabs, d, mine))
        out.addAll(itemLessons(items, d, teacher))
        return out.sortedBy { it.start }
    }

    fun lessonsOn(html: String, items: List<Item>, mine: Int, teacher: Boolean, d: LocalDate): List<Lesson> =
        dayAll(tables(html), items, mine, teacher, d)

    fun upcoming(html: String, items: List<Item>, mine: Int, teacher: Boolean, now: LocalDateTime): List<Lesson> {
        val tabs = tables(html)
        val today = now.toLocalDate()
        val out = mutableListOf<Lesson>()
        for (i in 0..2) out.addAll(dayAll(tabs, items, mine, teacher, today.plusDays(i.toLong())))
        return out.filter { it.endAt > now }.sortedBy { it.startAt }
    }

    /** Только учебные пары за период (их мы отправляем друзьям). */
    fun studyWindow(html: String, mine: Int, from: LocalDate, to: LocalDate): List<Lesson> {
        val tabs = tables(html)
        val out = mutableListOf<Lesson>()
        var d = from
        while (!d.isAfter(to)) {
            out.addAll(studentDay(tabs, d, mine))
            d = d.plusDays(1)
        }
        return out
    }
}
