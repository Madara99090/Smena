package com.smena.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object Shifts {
    val NAMES = listOf("Топа", "Илес", "Эми")
    val ANCHOR: LocalDate = LocalDate.of(2026, 10, 9)
    const val NIGHTS_PER_SHIFT = 2L

    fun shiftFor(d: LocalDate): Pair<String, Int> {
        val delta = ChronoUnit.DAYS.between(ANCHOR, d)
        val idx = Math.floorMod(Math.floorDiv(delta, NIGHTS_PER_SHIFT), NAMES.size.toLong()).toInt()
        val night = Math.floorMod(delta, NIGHTS_PER_SHIFT).toInt() + 1
        return NAMES[idx] to night
    }
}

/** status: none | asked | auto | yes | no */
data class Night(
    val date: String,
    var shift: String,
    var night: Int,
    var status: String,
    var lastNotified: Long
)

object Store {
    private const val PREFS = "smena"
    private const val KEY = "nights"

    private fun load(ctx: Context): MutableMap<String, Night> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}"
        val o = JSONObject(s)
        val map = linkedMapOf<String, Night>()
        for (k in o.keys()) {
            val r = o.getJSONObject(k)
            map[k] = Night(k, r.getString("shift"), r.getInt("night"), r.getString("status"), r.getLong("last"))
        }
        return map
    }

    private fun save(ctx: Context, map: Map<String, Night>) {
        val o = JSONObject()
        for ((k, n) in map) {
            o.put(k, JSONObject()
                .put("shift", n.shift)
                .put("night", n.night)
                .put("status", n.status)
                .put("last", n.lastNotified))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, o.toString()).apply()
    }

    fun get(map: MutableMap<String, Night>, ds: String): Night {
        return map.getOrPut(ds) {
            val (name, n) = Shifts.shiftFor(LocalDate.parse(ds))
            Night(ds, name, n, "none", 0L)
        }
    }

    @Synchronized
    fun update(ctx: Context, block: (MutableMap<String, Night>) -> Unit) {
        val map = load(ctx)
        block(map)
        save(ctx, map)
    }

    @Synchronized
    fun read(ctx: Context): Map<String, Night> = load(ctx)
}

object Checker {
    private const val CHANNEL = "smena_channel"
    private const val NOTIFY_EVERY_MS = 3_600_000L - 120_000L

    private fun due(r: Night) = System.currentTimeMillis() - r.lastNotified >= NOTIFY_EVERY_MS

    fun check(ctx: Context) {
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val h = now.hour
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val active = pm.isInteractive

        Store.update(ctx) { map ->
            if (h >= 20 || h < 7) {
                val nightDate = if (h >= 20) today else today.minusDays(1)
                val r = Store.get(map, nightDate.toString())
                if ((r.status == "none" || r.status == "asked") && active) {
                    r.status = "auto"
                }
                if (r.status in listOf("none", "asked", "auto") && due(r)) {
                    notify(ctx, r, false)
                    if (r.status == "none") r.status = "asked"
                }
            } else if (h in 7 until 20) {
                for (r in map.values) {
                    val d = LocalDate.parse(r.date)
                    if (!d.isBefore(today)) continue
                    if (ChronoUnit.DAYS.between(d, today) > 3) continue
                    if ((r.status == "asked" || r.status == "auto") && due(r)) {
                        notify(ctx, r, true)
                    }
                }
            }
        }
    }

    fun notify(ctx: Context, r: Night, reminder: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Смены", NotificationManager.IMPORTANCE_HIGH)
        )

        fun action(value: String): PendingIntent {
            val i = Intent(ctx, AnswerReceiver::class.java)
                .putExtra("date", r.date)
                .putExtra("value", value)
            val code = r.date.replace("-", "").toInt() * 2 + if (value == "yes") 1 else 0
            return PendingIntent.getBroadcast(
                ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val d = LocalDate.parse(r.date)
        val title = if (reminder) "Был ли вчера на работе?" else "Ты на работе?"
        val text = "%02d.%02d · смена «%s», ночь %d".format(d.dayOfMonth, d.monthValue, r.shift, r.night)

        @Suppress("DEPRECATION")
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(false)
            .addAction(0, "Да", action("yes"))
            .addAction(0, "Нет", action("no"))
            .build()

        nm.notify(r.date.replace("-", "").toInt(), n)
        r.lastNotified = System.currentTimeMillis()
    }
}

object Scheduler {
    private const val INTERVAL_MS = 5 * 60 * 1000L

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, TickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + INTERVAL_MS, pi)
    }
}

class TickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            Checker.check(context)
        } finally {
            Scheduler.schedule(context)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Checker.check(context)
        Scheduler.schedule(context)
    }
}

class AnswerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ds = intent.getStringExtra("date") ?: return
        val value = intent.getStringExtra("value") ?: return
        Store.update(context) { map ->
            Store.get(map, ds).status = if (value == "yes") "yes" else "no"
        }
        context.getSystemService(NotificationManager::class.java)
            .cancel(ds.replace("-", "").toInt())
    }
}

class MainActivity : Activity() {

    private lateinit var summary: TextView
    private lateinit var list: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        Scheduler.schedule(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Учёт ночных смен"
            textSize = 24f
        })

        summary = TextView(this).apply {
            textSize = 17f
            setPadding(0, dp(12), 0, dp(12))
        }
        root.addView(summary)

        root.addView(Button(this).apply {
            text = "Добавить / изменить дату"
            setOnClickListener { pickDate() }
        })
        root.addView(Button(this).apply {
            text = "Тест уведомления"
            setOnClickListener {
                Store.update(this@MainActivity) { map ->
                    Checker.notify(this@MainActivity, Store.get(map, LocalDate.now().toString()), false)
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Отключить оптимизацию батареи"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (e: Exception) {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                }
            }
        })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun mark(status: String) = when (status) {
        "yes" -> "✔ был"
        "auto" -> "~ по активности"
        "no" -> "✘ не был"
        else -> "? нет ответа"
    }

    private fun refresh() {
        val map = Store.read(this)
        val worked = Shifts.NAMES.associateWith { 0 }.toMutableMap()
        var unconfirmed = 0
        for (r in map.values) {
            if (r.status == "yes" || r.status == "auto") {
                worked[r.shift] = (worked[r.shift] ?: 0) + 1
            }
            if (r.status == "auto") unconfirmed++
        }
        val total = worked.values.sum()
        val sb = StringBuilder("Всего ночей: $total\n")
        for ((name, c) in worked) sb.append("$name: $c\n")
        if (unconfirmed > 0) sb.append("Не подтверждено: $unconfirmed")
        summary.text = sb.toString().trimEnd()

        list.removeAllViews()
        for (ds in map.keys.sortedDescending()) {
            val r = map[ds]!!
            val d = LocalDate.parse(ds)
            list.addView(TextView(this).apply {
                text = "%02d.%02d  %s · ночь %d\n%s".format(d.dayOfMonth, d.monthValue, r.shift, r.night, mark(r.status))
                textSize = 16f
                gravity = Gravity.START
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { statusDialog(ds) }
            })
        }
    }

    private fun statusDialog(ds: String) {
        val (name, n) = Shifts.shiftFor(LocalDate.parse(ds))
        AlertDialog.Builder(this)
            .setTitle("$ds · $name, ночь $n")
            .setItems(arrayOf("Был на работе", "Не был")) { _, i ->
                Store.update(this) { map ->
                    Store.get(map, ds).status = if (i == 0) "yes" else "no"
                }
                refresh()
            }
            .show()
    }

    private fun pickDate() {
        val t = LocalDate.now()
        DatePickerDialog(this, { _, y, m, d ->
            statusDialog(LocalDate.of(y, m + 1, d).toString())
        }, t.year, t.monthValue - 1, t.dayOfMonth).show()
    }
}