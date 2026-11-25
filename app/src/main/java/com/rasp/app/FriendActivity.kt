package com.rasp.app

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.CalendarView
import android.widget.LinearLayout
import android.widget.ScrollView
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class FriendActivity : Activity() {
    private lateinit var dayBox: LinearLayout
    private var items: List<Item> = emptyList()
    private var sel: LocalDate = LocalDate.now()
    private var loaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = intent.getStringExtra("uid") ?: ""
        val nick = intent.getStringExtra("nick") ?: ""
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#FFF1F6"))
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(36), dp(14), dp(36))
        sv.addView(root)
        setContentView(sv)

        val c = card(root, "Календарь: $nick")
        c.addView(text("Видны занятия и дела с датой. Заметки и дела без даты скрыты.", 12f, MUTED))
        val cv = CalendarView(this)
        cv.firstDayOfWeek = java.util.Calendar.MONDAY
        cv.setOnDateChangeListener { _, y, m, d ->
            sel = LocalDate.of(y, m + 1, d)
            fill()
        }
        c.addView(cv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330)))
        dayBox = LinearLayout(this)
        dayBox.orientation = LinearLayout.VERTICAL
        c.addView(dayBox)
        c.addView(button("Назад", false) { finish() })
        fill()

        Thread {
            val r = Sync.friendItems(this, uid)
            runOnUiThread {
                if (r == null) {
                    toast("Не удалось загрузить расписание друга")
                } else {
                    items = r
                    loaded = true
                    fill()
                }
            }
        }.start()
    }

    private fun fill() {
        dayBox.removeAllViews()
        val title = sel.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru")))
        dayBox.addView(text(title.replaceFirstChar { it.uppercase() }, 16f, ACC, true))
        if (!loaded) {
            dayBox.addView(text("Загрузка...", 14f, MUTED))
            return
        }
        val day = items.filter {
            if (it.date != null) it.date == sel.toString() else it.weekday == sel.dayOfWeek.value
        }.sortedBy { it.start ?: "" }
        if (day.isEmpty()) dayBox.addView(text("На этот день ничего нет", 14f, MUTED))
        for (x in day) {
            val who = when (x.kind) {
                "study" -> "Учится"
                "teach" -> "Преподаёт"
                "extra" -> "Факультатив"
                else -> "Дело"
            }
            val color = when (x.kind) {
                "study" -> "УЧУСЬ"
                "teach" -> "ПРЕПОДАЮ"
                else -> "МОЁ"
            }
            val time = if (x.start != null && x.end != null) x.start + "–" + x.end else x.start ?: "весь день"
            dayBox.addView(text("$time · $who", 13f, hex(color), true))
            dayBox.addView(text((x.ltype?.let { "$it. " } ?: "") + x.title, 14f, INK))
            val where = listOfNotNull(x.place?.let { if (x.kind == "study") it else "ауд. $it" }, x.grp, x.subgroup).joinToString(" · ")
            if (where.isNotEmpty()) dayBox.addView(text(where, 12f, MUTED))
        }
    }
}
