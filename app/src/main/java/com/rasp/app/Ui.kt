package com.rasp.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

const val INK = "#4A1630"
const val MUTED = "#9C6B84"
const val ACC = "#D81B60"

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

fun Context.rounded(color: String, radius: Int): GradientDrawable {
    val g = GradientDrawable()
    g.setColor(Color.parseColor(color))
    g.cornerRadius = dp(radius).toFloat()
    return g
}

fun Context.hex(who: String): String = String.format("#%06X", 0xFFFFFF and Updater.color(who))

fun Context.text(s: String, size: Float, color: String, bold: Boolean = false): TextView {
    val t = TextView(this)
    t.text = s
    t.textSize = size
    t.setTextColor(Color.parseColor(color))
    if (bold) t.setTypeface(t.typeface, Typeface.BOLD)
    t.setPadding(0, dp(3), 0, dp(3))
    return t
}

fun Context.card(root: LinearLayout, title: String): LinearLayout {
    val c = LinearLayout(this)
    c.orientation = LinearLayout.VERTICAL
    c.background = rounded("#FFFFFF", 20)
    c.setPadding(dp(16), dp(12), dp(16), dp(14))
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.setMargins(0, 0, 0, dp(12))
    root.addView(c, lp)
    if (title.isNotEmpty()) c.addView(text(title, 18f, INK, true))
    return c
}

fun Context.button(s: String, filled: Boolean = true, onClick: () -> Unit): Button {
    val b = Button(this)
    b.text = s
    b.isAllCaps = false
    b.background = rounded(if (filled) "#EC407A" else "#FCE4EC", 14)
    b.setTextColor(Color.parseColor(if (filled) "#FFFFFF" else ACC))
    b.setOnClickListener { onClick() }
    val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    lp.topMargin = dp(6)
    b.layoutParams = lp
    return b
}

fun Context.row(label: String, actions: List<Pair<String, () -> Unit>>): View {
    val r = LinearLayout(this)
    r.orientation = LinearLayout.HORIZONTAL
    r.gravity = Gravity.CENTER_VERTICAL
    r.addView(text(label, 15f, INK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    for ((caption, action) in actions) {
        val b = Button(this)
        b.text = caption
        b.background = rounded("#FCE4EC", 12)
        b.setOnClickListener { action() }
        val lp = LinearLayout.LayoutParams(dp(52), dp(44))
        lp.leftMargin = dp(6)
        r.addView(b, lp)
    }
    return r
}

fun Context.rowWithDelete(label: String, onDelete: () -> Unit): View = row(label, listOf("🗑" to onDelete))

fun Activity.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

/** Окно, которое выезжает снизу экрана. */
fun Activity.sheet(title: String, body: View?, buttons: List<Triple<String, Boolean, () -> Unit>>): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(dp(20), dp(18), dp(20), dp(18))
    val bg = GradientDrawable()
    bg.setColor(Color.WHITE)
    val r = dp(22).toFloat()
    bg.cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
    box.background = bg
    box.addView(text(title, 18f, INK, true))
    if (body != null) box.addView(body)
    for ((label, filled, action) in buttons) {
        box.addView(button(label, filled) {
            d.dismiss()
            action()
        })
    }
    val sv = ScrollView(this)
    sv.addView(box)
    d.setContentView(sv)
    d.show()
    d.window?.let {
        it.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        it.setGravity(Gravity.BOTTOM)
        it.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        it.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }
    return d
}

fun Activity.confirm(title: String, yes: String, action: () -> Unit) {
    sheet(title, null, listOf(Triple(yes, true, action), Triple("Отмена", false, {})))
}

fun Activity.ask(title: String, hints: List<String>, init: List<String>, extra: View?, labels: List<String>, cb: (Int, List<String>) -> Unit) {
    val box = LinearLayout(this)
    box.orientation = LinearLayout.VERTICAL
    val fields = hints.mapIndexed { i, h ->
        val e = EditText(this)
        e.hint = h
        e.setText(init.getOrElse(i) { "" })
        box.addView(e)
        e
    }
    if (extra != null) box.addView(extra)
    fun vals() = fields.map { it.text.toString().trim() }
    val btns = mutableListOf<Triple<String, Boolean, () -> Unit>>()
    btns.add(Triple(labels[0], true, { cb(0, vals()) }))
    if (labels.size > 1) btns.add(Triple(labels[1], false, { cb(1, vals()) }))
    btns.add(Triple("Отмена", false, {}))
    sheet(title, box, btns)
    fields.firstOrNull()?.requestFocus()
}
