package com.simplenotes.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object Reminders {
    private const val CHANNEL_PREFIX = "reminders_"
    const val ACTION_DISMISS = "com.simplenotes.app.DISMISS"
    const val ACTION_DONE = "com.simplenotes.app.DONE"
    const val EXTRA_SNOOZED = "snoozed"

    const val DAILY = 1440
    const val WEEKLY = 10080

    val snoozeOptions = intArrayOf(5, 10, 15)

    fun soundUri(ctx: Context): Uri? {
        val s = Prefs(ctx).alarmSound
        if (s == "silent") return null
        if (s.isNotEmpty()) return runCatching { Uri.parse(s) }.getOrNull()
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    fun channelId(ctx: Context) = CHANNEL_PREFIX + Integer.toHexString(Prefs(ctx).alarmSound.hashCode())

    fun createChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val id = channelId(ctx)
        nm.notificationChannels.forEach {
            if (it.id != id && (it.id == "reminders" || it.id.startsWith(CHANNEL_PREFIX))) nm.deleteNotificationChannel(it.id)
        }
        if (nm.getNotificationChannel(id) != null) return
        val ch = NotificationChannel(id, ctx.getString(R.string.reminder_channel), NotificationManager.IMPORTANCE_HIGH)
        ch.setSound(
            soundUri(ctx),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        ch.enableVibration(true)
        ch.vibrationPattern = longArrayOf(0, 400, 200, 400)
        ch.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        nm.createNotificationChannel(ch)
    }

    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    fun notificationsAllowed(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun fireIntent(ctx: Context, id: Long, snoozed: Boolean) =
        Intent(ctx, ReminderReceiver::class.java).putExtra("id", id).putExtra(EXTRA_SNOOZED, snoozed)

    private fun pending(ctx: Context, id: Long, snoozed: Boolean, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            ctx, if (snoozed) -id.toInt() - 1 else id.toInt(),
            fireIntent(ctx, id, snoozed), flags or PendingIntent.FLAG_IMMUTABLE
        )

    private fun set(ctx: Context, at: Long, pi: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (canExact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun schedule(ctx: Context, id: Long, at: Long) {
        set(ctx, at, pending(ctx, id, false, PendingIntent.FLAG_UPDATE_CURRENT) ?: return)
    }

    fun snooze(ctx: Context, id: Long, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        set(ctx, at, pending(ctx, id, true, PendingIntent.FLAG_UPDATE_CURRENT) ?: return)
        NotificationManagerCompat.from(ctx).cancel(id.toInt())
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (snoozed in booleanArrayOf(false, true)) {
            val pi = pending(ctx, id, snoozed, PendingIntent.FLAG_NO_CREATE) ?: continue
            am.cancel(pi)
            pi.cancel()
        }
    }

    fun repeatLabel(ctx: Context, min: Int): String = when {
        min <= 0 -> ""
        min == DAILY -> ctx.getString(R.string.repeat_daily)
        min == WEEKLY -> ctx.getString(R.string.repeat_weekly)
        else -> ctx.getString(R.string.repeat_every_days, min / DAILY)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    @SuppressLint("MissingPermission")
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0L)
        if (id == 0L) return
        val db = NoteDb.of(ctx)
        val nm = NotificationManagerCompat.from(ctx)

        when (intent.action) {
            Reminders.ACTION_DISMISS -> { nm.cancel(id.toInt()); return }
            Reminders.ACTION_DONE -> {
                nm.cancel(id.toInt())
                Reminders.cancel(ctx, id)
                db.clearReminder(id)
                return
            }
        }

        val n = db.get(id) ?: return
        if (n.trashedAt > 0) return
        if (!intent.getBooleanExtra(Reminders.EXTRA_SNOOZED, false)) {
            if (n.repeatMin > 0) {
                val step = n.repeatMin * 60_000L
                val now = System.currentTimeMillis()
                var next = maxOf(n.reminder, now - 1)
                do { next += step } while (next <= now)
                db.setReminder(id, next)
                Reminders.schedule(ctx, id, next)
            } else db.clearReminder(id)
        }
        if (!Reminders.notificationsAllowed(ctx)) return

        Reminders.createChannel(ctx)
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            ctx, id.toInt(),
            Intent(ctx, EditorActivity::class.java)
                .putExtra("id", id).putExtra("color", n.color)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags
        )
        val snoozeIntent = PendingIntent.getActivity(
            ctx, id.toInt(),
            Intent(ctx, SnoozeActivity::class.java).putExtra("id", id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            flags
        )
        fun action(a: String, req: Int) = PendingIntent.getBroadcast(
            ctx, id.toInt() * 4 + req,
            Intent(ctx, ReminderReceiver::class.java).setAction(a).putExtra("id", id), flags
        )
        val body = if (n.locked) ctx.getString(R.string.locked_note) else n.preview().trim().take(160)
        val b = NotificationCompat.Builder(ctx, Reminders.channelId(ctx))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title.ifBlank { ctx.getString(R.string.reminder_title_fallback) })
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, ctx.getString(R.string.snooze), snoozeIntent)
            .addAction(0, ctx.getString(R.string.dismiss), action(Reminders.ACTION_DISMISS, 1))
            .addAction(0, ctx.getString(R.string.mark_done), action(Reminders.ACTION_DONE, 2))
        if (Build.VERSION.SDK_INT < 26) {
            b.setSound(Reminders.soundUri(ctx), android.media.AudioManager.STREAM_ALARM)
            b.setDefaults(Notification.DEFAULT_VIBRATE)
        }
        nm.notify(id.toInt(), b.build())
    }
}

class SnoozeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getLongExtra("id", 0L)
        if (id == 0L) { finish(); return }
        val names = Reminders.snoozeOptions.map { getString(R.string.snooze_n, it) }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.snooze_title)
            .setItems(names) { _, which ->
                Reminders.snooze(this, id, Reminders.snoozeOptions[which])
                finish()
            }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                val now = System.currentTimeMillis()
                for (n in NoteDb.of(ctx).pendingReminders()) {
                    Reminders.schedule(ctx, n.id, maxOf(n.reminder, now + 3000L))
                }
            }
        }
    }
}
