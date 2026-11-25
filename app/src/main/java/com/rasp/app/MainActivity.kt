package com.rasp.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CalendarView
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var dayBox: LinearLayout
    private lateinit var drawer: ScrollView
    private lateinit var drawerBox: LinearLayout
    private lateinit var scrim: View
    private var drawerW = 0
    private var sel: LocalDate = LocalDate.now()
    private val PICK = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val frame = FrameLayout(this)
        val sv = ScrollView(this)
        sv.setBackgroundColor(Color.parseColor("#FFF1F6"))
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(14), dp(36), dp(14), dp(36))
        sv.addView(root)
        frame.addView(sv, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        scrim = View(this)
        scrim.setBackgroundColor(Color.parseColor("#66000000"))
        scrim.visibility = View.GONE
        scrim.setOnClickListener { closeDrawer() }
        frame.addView(scrim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        drawerW = resources.displayMetrics.widthPixels * 82 / 100
        drawer = ScrollView(this)
        drawer.setBackgroundColor(Color.parseColor("#FFF1F6"))
        drawerBox = LinearLayout(this)
        drawerBox.orientation = LinearLayout.VERTICAL
        drawerBox.setPadding(dp(14), dp(36), dp(14), dp(36))
        drawer.addView(drawerBox)
        drawer.translationX = -drawerW.toFloat()
        drawer.visibility = View.GONE
        frame.addView(drawer, FrameLayout.LayoutParams(drawerW, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(frame)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    private fun openDrawer() {
        renderDrawer()
        drawer.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        drawer.animate().translationX(0f).setDuration(200).start()
        scrim.animate().alpha(1f).setDuration(200).start()
    }

    private fun closeDrawer() {
        drawer.animate().translationX(-drawerW.toFloat()).setDuration(180).withEndAction { drawer.visibility = View.GONE }.start()
        scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (drawer.visibility == View.VISIBLE) closeDrawer() else super.onBackPressed()
    }

    override fun onResume() {
        super.onResume()
        if (!Api.loggedIn(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }
        render()
        Thread {
            Updater.refresh(this, true)
            Sync.cleanupDone(this)
            Sync.pullFriends(this)
            Sync.syncStudy(this)
            runOnUiThread { render() }
        }.start()
    }

    /** Выполняет изменение на сервере в фоне, потом обновляет экран и виджеты. */
    private fun work(after: () -> Unit = { render() }, f: () -> Boolean) {
        Thread {
            val ok = f()
            runOnUiThread {
                if (!ok) toast("Не получилось: " + Api.lastError.take(120))
                after()
                Thread { Updater.refresh(this, false) }.start()
            }
        }.start()
    }

    private fun hwButton(l: Lesson, after: () -> Unit): Button {
        val cur = Store.hw(this)[l.hwKey] ?: ""
        return button(if (cur.isEmpty()) "+ Записать ДЗ" else "ДЗ: $cur (изменить)", false) {
            ask("ДЗ: " + l.subject + ", " + l.date, listOf("Например: № 145, 148 стр. 32"), listOf(cur), null, listOf("Сохранить")) { _, v ->
                work(after) { Sync.setHw(this, l.date.toString(), l.start.toString(), l.subject, v[0]) }
            }
        }
    }

    /** Строка дела: галочка, текст, корзина. */
    private fun todoRow(x: Item, onChange: () -> Unit): View {
        val done = x.doneOn != null
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val cb = CheckBox(this)
        cb.isChecked = done
        cb.buttonTintList = ColorStateList.valueOf(Color.parseColor(ACC))
        cb.setOnCheckedChangeListener { _, checked ->
            if (checked) Reminders.cancel(this, x.id)
            work(onChange) { Sync.setDone(this, x.id, if (checked) LocalDate.now().toString() else null) }
        }
        r.addView(cb)
        val label = (if (x.permanent) "📌 " else "") + (if (x.start != null) x.start + " " else "") + x.title
        val t = text(label, 15f, if (done) MUTED else INK)
        if (done) t.paintFlags = t.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        r.addView(t, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val del = Button(this)
        del.text = "🗑"
        del.background = rounded("#FCE4EC", 12)
        del.setOnClickListener {
            Reminders.cancel(this, x.id)
            work(onChange) { Sync.deleteItem(this, x.id) }
        }
        val lp = LinearLayout.LayoutParams(dp(52), dp(44))
        lp.leftMargin = dp(6)
        r.addView(del, lp)
        return r
    }

    private fun addCalendarItem(def: LocalDate, extraOnly: Boolean, after: () -> Unit = { render() }) {
        val hints = listOf(
            "Название",
            if (extraOnly) "Время: 17:00-18:35" else "Время: 15:00 или 17:00-18:35 (необязательно)",
            "Место (необязательно)",
            "Дата ГГГГ-ММ-ДД"
        )
        ask(if (extraOnly) "Новый факультатив" else "Добавить в календарь", hints, listOf("", "", "", def.toString()), null,
            listOf("На эту дату", "Каждую неделю")) { which, v ->
            val range = Schedule.parseRange(v[1])
            if (v[0].isEmpty()) {
                toast("Напиши название")
            } else if (extraOnly && range == null) {
                toast("Укажи время, например 17:00-18:35")
            } else {
                val single = Schedule.parseTime(v[1])
                val d = try { LocalDate.parse(v[3]) } catch (e: Exception) { def }
                val kind = if (range != null) "extra" else "todo"
                val start = range?.first ?: single
                val end = range?.second
                val place = v[2].ifEmpty { null }
                if (which == 1) {
                    work(after) { Sync.addItem(this, kind, v[0], place, null, start, end, d.dayOfWeek.value) }
                } else {
                    work(after) { Sync.addItem(this, kind, v[0], place, d.toString(), start, end, null) }
                }
            }
        }
    }

    private fun renderDrawer() {
        drawerBox.removeAllViews()
        val teacher = Store.teacher(this)
        val mine = Store.sub(this)
        val items = Store.items(this)
        drawerBox.addView(text("Меню", 24f, ACC, true))

        val acc = card(drawerBox, "Аккаунт")
        acc.addView(text(Api.nick(this), 15f, INK, true))
        val sw = Switch(this)
        sw.text = "Я преподаю"
        sw.textSize = 16f
        sw.setTextColor(Color.parseColor(INK))
        sw.setPadding(0, dp(8), 0, dp(8))
        sw.isChecked = teacher
        sw.setOnCheckedChangeListener { _, v ->
            if (v != teacher) work { Sync.setTeacher(this, v) }
        }
        acc.addView(sw)
        acc.addView(button("Моя подгруппа в учёбе: $mine (сменить)", false) {
            Store.setSub(this, 3 - mine)
            work { Sync.syncStudy(this) }
        })
        acc.addView(button("Выйти из аккаунта", false) {
            confirm("Выйти из аккаунта?", "Выйти") {
                Api.logout(this)
                Store.clearAll(this)
                Thread { Updater.refresh(this, false) }.start()
                startActivity(Intent(this, LoginActivity::class.java))
                finish()
            }
        })

        if (teacher) {
            val tc = card(drawerBox, "Преподаю")
            tc.addView(text("Занятий в списке: " + items.count { it.kind == "teach" }, 13f, MUTED))
            tc.addView(button("Загрузить Excel с расписанием") {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                i.addCategory(Intent.CATEGORY_OPENABLE)
                i.type = "*/*"
                startActivityForResult(i, PICK)
            })
            tc.addView(button("Очистить список", false) { work { Sync.clearTeach(this) } })
        }

        val ex = card(drawerBox, "Факультативы")
        ex.addView(text("Появятся в календаре и в виджете", 12f, MUTED))
        ex.addView(button("+ Добавить факультатив") {
            closeDrawer()
            addCalendarItem(LocalDate.now(), true)
        })

        val ln = card(drawerBox, "Ссылка на расписание")
        ln.addView(text(Store.url(this), 11f, MUTED))
        ln.addView(button("Изменить ссылку", false) {
            ask("Ссылка на расписание", listOf("https://..."), listOf(Store.url(this)), null, listOf("Сохранить", "Сбросить")) { w, v ->
                val u = if (w == 1) Schedule.PAGE_URL else v[0]
                if (u.startsWith("http")) {
                    Store.setUrl(this, u)
                    render()
                    Thread {
                        Updater.refresh(this, true)
                        Sync.syncStudy(this)
                        runOnUiThread { render() }
                    }.start()
                } else {
                    toast("Ссылка должна начинаться с http")
                }
            }
        })
    }

    private fun render() {
        root.removeAllViews()
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val html = Store.html(this)
        val items = Store.items(this)
        val list = Schedule.upcoming(html, items, Store.sub(this), Store.teacher(this), now)
        val hm = DateTimeFormatter.ofPattern("HH:mm")

        // --- Шапка: три полоски и название ---
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        val menuBtn = Button(this)
        menuBtn.text = "☰"
        menuBtn.textSize = 20f
        menuBtn.setTextColor(Color.parseColor(ACC))
        menuBtn.background = rounded("#FFFFFF", 14)
        menuBtn.setOnClickListener { openDrawer() }
        bar.addView(menuBtn, LinearLayout.LayoutParams(dp(52), dp(48)))
        val ttl = text("Мои пары 🎀", 24f, ACC, true)
        ttl.setPadding(dp(12), 0, 0, 0)
        bar.addView(ttl)
        val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        blp.bottomMargin = dp(12)
        root.addView(bar, blp)

        // --- Следующая пара ---
        val top = card(root, "")
        val c = list.firstOrNull()
        if (c != null) {
            val day = if (c.startAt <= now) "Идёт сейчас" else Updater.dayWord(c.date, today)
            top.addView(text(c.who + " · " + day, 13f, hex(c.who), true))
            top.addView(text(c.start.format(hm) + "–" + c.end.format(hm), 30f, INK, true))
            top.addView(text(c.title, 16f, INK, true))
            if (c.place.isNotEmpty()) top.addView(text(c.place, 13f, MUTED))
            if (c.who == "УЧУСЬ") top.addView(hwButton(c) { render() })
        } else {
            top.addView(text(if (html.isEmpty()) "Расписание ещё не загружено (нужен интернет)" else "Ближайших пар нет", 15f, MUTED))
        }
        top.addView(button("Обновить") {
            Thread {
                Updater.refresh(this, true)
                Sync.syncStudy(this)
                runOnUiThread { render() }
            }.start()
        })

        // --- Календарь ---
        val cal = card(root, "Календарь")
        val cv = CalendarView(this)
        cv.firstDayOfWeek = java.util.Calendar.MONDAY
        cv.date = sel.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        cv.setOnDateChangeListener { _, y, m, d ->
            sel = LocalDate.of(y, m + 1, d)
            fillDay()
        }
        cal.addView(cv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(330)))
        dayBox = LinearLayout(this)
        dayBox.orientation = LinearLayout.VERTICAL
        cal.addView(dayBox)
        fillDay()

        // --- Мои дела (без даты) ---
        val dela = card(root, "Мои дела")
        val active = Store.undated(this)
        if (active.isEmpty()) dela.addView(text("Пусто", 14f, MUTED))
        for (t in active) dela.addView(todoRow(t) { render() })
        dela.addView(button("+ Добавить дело") {
            ask("Новое дело", listOf("Что сделать"), listOf(""), null, listOf("Добавить", "📌 Вечное")) { which, v ->
                if (v[0].isNotEmpty()) work { Sync.addItem(this, "todo", v[0], null, null, null, null, null, which == 1) }
            }
        })
        val done = Store.doneList(this, today)
        if (done.isNotEmpty()) {
            dela.addView(text("Сделанные", 14f, ACC, true))
            dela.addView(text("Обычные исчезнут завтра, 📌 вечные удаляй сам", 12f, MUTED))
            for (t in done) dela.addView(todoRow(t) { render() })
        }

        // --- Друзья ---
        val fr = card(root, "Друзья")
        val friends = Store.friends(this)
        if (friends.isEmpty()) fr.addView(text("Пока никого", 14f, MUTED))
        for (f in friends) {
            when {
                f.accepted -> fr.addView(
                    row("👤 " + f.nick, listOf(
                        "📅" to { startActivity(Intent(this, FriendActivity::class.java).putExtra("uid", f.uid).putExtra("nick", f.nick)) },
                        "🗑" to { work { Sync.removeFriend(this, f.fid) } }
                    ))
                )
                f.incoming -> fr.addView(
                    row(f.nick + " хочет дружить", listOf(
                        "✓" to { work { Sync.accept(this, f.fid) } },
                        "✕" to { work { Sync.removeFriend(this, f.fid) } }
                    ))
                )
                else -> fr.addView(row(f.nick + " — заявка отправлена", listOf("✕" to { work { Sync.removeFriend(this, f.fid) } })))
            }
        }
        fr.addView(button("+ Добавить друга по нику") {
            ask("Ник друга", listOf("Ник"), listOf(""), null, listOf("Отправить заявку")) { _, v ->
                if (v[0].isNotEmpty()) work { Sync.addFriend(this, v[0]) }
            }
        })
        fr.addView(text("Друзья видят твои занятия и дела с датой. Заметки и дела без даты скрыты.", 12f, MUTED))

        renderDrawer()
    }

    private fun fillDay() {
        dayBox.removeAllViews()
        val hm = DateTimeFormatter.ofPattern("HH:mm")
        val ls = Schedule.lessonsOn(Store.html(this), Store.items(this), Store.sub(this), Store.teacher(this), sel)
        val title = sel.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("ru")))
        dayBox.addView(text(title.replaceFirstChar { it.uppercase() }, 16f, ACC, true))
        if (ls.isEmpty()) dayBox.addView(text("Пар нет", 14f, MUTED))
        for (l in ls) {
            dayBox.addView(text(l.start.format(hm) + "–" + l.end.format(hm) + " · " + l.who, 13f, hex(l.who), true))
            dayBox.addView(text(l.title, 14f, INK))
            if (l.place.isNotEmpty()) dayBox.addView(text(l.place, 12f, MUTED))
            if (l.who == "УЧУСЬ") dayBox.addView(hwButton(l) { fillDay() })
        }

        // свои занятия и дела на этот день
        val own = Store.items(this).filter {
            (it.kind == "extra" || it.kind == "todo") && ((it.date != null && it.date == sel.toString()) || (it.date == null && it.weekday == sel.dayOfWeek.value))
        }
        if (own.isNotEmpty()) dayBox.addView(text("Мои занятия и дела", 13f, MUTED, true))
        for (x in own.sortedBy { it.start ?: "" }) {
            if (x.kind == "todo") {
                dayBox.addView(todoRow(x) { fillDay() })
            } else {
                val t = x.start + (if (x.end != null) "–" + x.end else "") + " " + x.title + (if (x.date == null) " (каждую неделю)" else "")
                dayBox.addView(rowWithDelete(t) {
                    Reminders.cancel(this, x.id)
                    work({ fillDay() }) { Sync.deleteItem(this, x.id) }
                })
            }
        }
        dayBox.addView(button("+ Дело или занятие на эту дату") { addCalendarItem(sel, false) { fillDay() } })

        dayBox.addView(text("Заметки (видны в виджете в этот день, друзьям не видны)", 13f, MUTED, true))
        for (n in Store.notes(this, sel)) {
            dayBox.addView(rowWithDelete("📝 " + n.body) { work({ fillDay() }) { Sync.deleteNote(this, n.id) } })
        }
        dayBox.addView(button("+ Заметка на эту дату") {
            ask("Заметка на " + sel, listOf("Текст заметки"), listOf(""), null, listOf("Добавить")) { _, v ->
                if (v[0].isNotEmpty()) work({ fillDay() }) { Sync.addNote(this, sel.toString(), v[0]) }
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK || resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            val res = try {
                contentResolver.openInputStream(uri)?.use { Xlsx.importTeacher(it) }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (res == null) {
                    toast("Не удалось прочитать файл: нужна таблица с колонками «Дата» и «Номера пар»")
                } else {
                    Toast.makeText(this, "Загружаю занятий: " + res.size, Toast.LENGTH_LONG).show()
                    work { Sync.importTeach(this, res) }
                }
            }
        }.start()
    }
}
