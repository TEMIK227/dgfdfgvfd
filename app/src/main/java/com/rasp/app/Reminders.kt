package com.rasp.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object Reminders {
    private const val CH = "todo"

    private fun pending(c: Context, id: String, title: String?, flags: Int): PendingIntent? {
        val i = Intent(c, ReminderReceiver::class.java)
        if (title != null) i.putExtra("id", id).putExtra("title", title)
        return PendingIntent.getBroadcast(c, id.hashCode(), i, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancel(c: Context, id: String) {
        val pi = pending(c, id, null, PendingIntent.FLAG_NO_CREATE) ?: return
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    fun rescheduleAll(c: Context) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = LocalDateTime.now()
        for (x in Store.items(c)) {
            if (x.kind != "todo" && x.kind != "extra") continue
            if (x.doneOn != null) {
                cancel(c, x.id)
                continue
            }
            val st = x.start ?: continue
            val lt = try { LocalTime.parse(st) } catch (e: Exception) { continue }
            val at: LocalDateTime
            if (x.date != null) {
                at = LocalDateTime.of(LocalDate.parse(x.date), lt)
            } else if (x.weekday != null) {
                var d = now.toLocalDate()
                while (d.dayOfWeek.value != x.weekday) d = d.plusDays(1)
                var a = LocalDateTime.of(d, lt)
                if (a <= now) a = a.plusDays(7)
                at = a
            } else {
                continue
            }
            if (at <= now) {
                cancel(c, x.id)
                continue
            }
            val pi = pending(c, x.id, x.title, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            }
        }
    }

    fun notify(c: Context, id: String, title: String) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Напоминания о делах", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Напоминание")
            .setContentText(title)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(id.hashCode(), n)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        val title = intent.getStringExtra("title") ?: return
        if (Store.items(context).any { it.id == id }) Reminders.notify(context, id, title)
        Reminders.rescheduleAll(context)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                Reminders.rescheduleAll(context)
                Updater.refresh(context, true)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
