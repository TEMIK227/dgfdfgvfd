package com.rasp.app

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

// Читает расписание преподавателя из .xlsx (таблица как в файле университета):
// колонки "Вид занятий", "Дисциплина", "Дата", "Номера пар", "Аудитории", "Учебная группа".
object Xlsx {
    private fun parseShared(bytes: ByteArray): List<String> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val out = mutableListOf<String>()
        var sb: StringBuilder? = null
        var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> if (p.name == "si") sb = StringBuilder() else if (p.name == "t") inT = true
                XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
                XmlPullParser.END_TAG -> if (p.name == "t") inT = false else if (p.name == "si") {
                    out.add(sb?.toString() ?: "")
                    sb = null
                }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseSheet(bytes: ByteArray, shared: List<String>): List<Map<String, String>> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(bytes), "UTF-8")
        val rows = mutableListOf<Map<String, String>>()
        var cur = mutableMapOf<String, String>()
        var ref = ""
        var type = ""
        var inV = false
        val buf = StringBuilder()
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> cur = mutableMapOf()
                    "c" -> {
                        ref = p.getAttributeValue(null, "r") ?: ""
                        type = p.getAttributeValue(null, "t") ?: ""
                        buf.setLength(0)
                    }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) buf.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val raw = buf.toString().trim()
                        val v = if (type == "s") (raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: "") else raw
                        val col = ref.takeWhile { it.isLetter() }
                        if (v.isNotBlank() && col.isNotEmpty()) cur[col] = v
                    }
                    "row" -> rows.add(cur)
                }
            }
            ev = p.next()
        }
        return rows
    }

    private fun parseDate(s: String): LocalDate? {
        val m = Regex("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})").find(s)
        if (m != null) {
            return try {
                LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
            } catch (e: Exception) {
                null
            }
        }
        val n = s.toDoubleOrNull() ?: return null
        return if (n > 30000) LocalDate.of(1899, 12, 30).plusDays(n.toLong()) else null
    }

    /** Возвращает занятия препода или null, если таблица не распознана. */
    fun importTeacher(input: InputStream): List<Item>? {
        var sharedBytes: ByteArray? = null
        val sheets = sortedMapOf<String, ByteArray>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                when {
                    e.name == "xl/sharedStrings.xml" -> sharedBytes = z.readBytes()
                    e.name.startsWith("xl/worksheets/sheet") && e.name.endsWith(".xml") -> sheets[e.name] = z.readBytes()
                }
            }
        }
        val first = sheets.values.firstOrNull() ?: return null
        val shared = sharedBytes?.let { parseShared(it) } ?: emptyList()
        val rows = parseSheet(first, shared)

        val headerIdx = rows.indexOfFirst { r -> r.values.any { it.trim().lowercase().startsWith("номера пар") } }
        if (headerIdx < 0) return null
        var kindCol = ""
        var subjCol = ""
        var dateCol = ""
        var timeCol = ""
        var roomCol = ""
        var groupCol = ""
        for ((col, v) in rows[headerIdx]) {
            val t = v.trim().lowercase()
            when {
                t.startsWith("вид") -> kindCol = col
                t.startsWith("дисциплин") -> subjCol = col
                t == "дата" -> dateCol = col
                t.startsWith("номера") -> timeCol = col
                t.startsWith("аудитор") -> roomCol = col
                t.startsWith("учебная") || t.startsWith("групп") -> groupCol = col
            }
        }
        if (dateCol.isEmpty() || timeCol.isEmpty()) return null

        val out = mutableListOf<Item>()
        for (r in rows.drop(headerIdx + 1)) {
            val d = parseDate(r[dateCol] ?: continue) ?: continue
            val rg = Schedule.parseRange(r[timeCol] ?: continue) ?: continue
            val rawSubj = r[subjCol] ?: ""
            val ltype = (r[kindCol] ?: "").trim().trimEnd('.').trim().ifEmpty { null }
            val room = (r[roomCol] ?: "").trim().ifEmpty { null }
            val group = (r[groupCol] ?: "").trim().ifEmpty { null }
            val sub = Regex("([12])\\s*п/г").find(rawSubj)?.let { it.groupValues[1] + " п/г" }
            val title = Schedule.subjectKey(rawSubj).ifEmpty { "Занятие" }
            out.add(Item("", "teach", ltype, title, room, group, sub, d.toString(), rg.first, rg.second, null))
        }
        return if (out.isEmpty()) null else out
    }
}
