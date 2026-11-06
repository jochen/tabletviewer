package de.zerties.tabletagent

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.webkit.WebSettings
import org.json.JSONObject

/** Sammelt den Zustand des Tablets als JSON. */
object Status {

    private val START = SystemClock.elapsedRealtime()

    fun sammeln(ctx: Context, k: Konfig): JSONObject {
        val j = JSONObject()
        j.put("geraet", k.geraet)
        j.put("zeit_ms", System.currentTimeMillis())
        j.put("agent", JSONObject()
            .put("version", version(ctx))
            .put("laufzeit_s", (SystemClock.elapsedRealtime() - START) / 1000))
        j.put("system", JSONObject()
            .put("modell", Build.MODEL)
            .put("hersteller", Build.MANUFACTURER)
            .put("android", Build.VERSION.RELEASE)
            .put("sdk", Build.VERSION.SDK_INT)
            .put("build", Build.DISPLAY)
            .put("laufzeit_s", SystemClock.elapsedRealtime() / 1000))
        j.put("akku", akku(ctx))
        j.put("wlan", wlan(ctx))
        j.put("bildschirm", bildschirm(ctx))
        j.put("speicher", speicher(ctx))
        val vg = vordergrundMitQuelle(ctx)
        j.put("vordergrund", vg?.first ?: JSONObject.NULL)
        j.put("vordergrund_quelle", vg?.second ?: JSONObject.NULL)
        j.put("webview", webview(ctx))
        j.put("bildschirmfoto_erlaubt", Bildschirmfoto.erlaubt)
        return j
    }

    private fun version(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun akku(ctx: Context): JSONObject {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return JSONObject()
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return JSONObject()
            .put("prozent", if (level >= 0 && scale > 0) level * 100 / scale else JSONObject.NULL)
            .put("laedt", status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL)
            .put("netzteil", plugged != 0)
            .put("temperatur_c", i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0)
    }

    @Suppress("DEPRECATION")
    private fun wlan(ctx: Context): JSONObject {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo ?: return JSONObject()
        val ip = info.ipAddress
        return JSONObject()
            .put("ssid", info.ssid?.trim('"') ?: JSONObject.NULL)
            .put("rssi", info.rssi)
            // -100 dBm = 0 %, -50 dBm = 100 % (calculateSignalLevel ist auf Fire OS unbrauchbar)
            .put("signal_prozent", ((info.rssi + 100) * 2).coerceIn(0, 100))
            .put("mbit", info.linkSpeed)
            .put("ip", "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff))
    }

    private fun bildschirm(ctx: Context): JSONObject {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val cr = ctx.contentResolver
        return JSONObject()
            .put("an", pm.isInteractive)
            .put("helligkeit", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1))
            .put("auto", Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == 1)
            .put("abschalten_ms", Settings.System.getInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, -1))
    }

    private fun speicher(ctx: Context): JSONObject {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        return JSONObject()
            .put("ram_frei_mb", mi.availMem / 1048576)
            .put("ram_gesamt_mb", mi.totalMem / 1048576)
            .put("ram_knapp", mi.lowMemory)
            .put("agent_heap_mb", (rt.totalMemory() - rt.freeMemory()) / 1048576)
    }

    fun vordergrund(ctx: Context): String? = vordergrundMitQuelle(ctx)?.first

    /** Paket im Vordergrund und woher die Info stammt (usagestats oder proc). */
    fun vordergrundMitQuelle(ctx: Context): Pair<String, String>? {
        usageStats(ctx)?.let { return it to "usagestats" }
        proc(ctx)?.let { return it to "proc" }
        return null
    }

    /**
     * Ohne Berechtigung (bis Android 6): die App im Vordergrund hat oom_score_adj 0
     * und liegt nicht in der Hintergrund-cgroup. Fire OS versteckt den Nutzungsdatenzugriff.
     */
    private fun proc(ctx: Context): String? = try {
        val kandidaten = mutableListOf<String>()
        for (d in java.io.File("/proc").listFiles() ?: emptyArray()) {
            if (!d.name.all { it.isDigit() }) continue
            try {
                if (java.io.File(d, "oom_score_adj").readText().trim() != "0") continue
                val uid = java.io.File(d, "status").readLines()
                    .firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull() ?: continue
                if (uid < 1000) continue        // root und Systemdienste; Einstellungen laufen als 1000
                val name = java.io.File(d, "cmdline").readText().substringBefore('\u0000').trim()
                if (name.isEmpty() || ':' in name || '.' !in name) continue
                if (java.io.File(d, "cgroup").readText().contains("bg_non_interactive")) continue
                kandidaten.add(name)
            } catch (e: Exception) { /* Prozess weg oder nicht lesbar */ }
        }
        kandidaten.firstOrNull { it != ctx.packageName } ?: kandidaten.firstOrNull()
    } catch (e: Exception) { null }

    private fun usageStats(ctx: Context): String? = try {
        val usm = ctx.getSystemService("usagestats") as UsageStatsManager
        val jetzt = System.currentTimeMillis()
        val ev = usm.queryEvents(jetzt - 24 * 3600 * 1000L, jetzt)
        val e = UsageEvents.Event()
        var paket: String? = null
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) paket = e.packageName
        }
        paket
    } catch (e: Exception) { null }

    private val WEBVIEW_PAKETE = listOf(
        "com.amazon.webview.chromium", "com.android.webview", "com.google.android.webview")

    private fun webview(ctx: Context): JSONObject {
        val j = JSONObject()
        for (p in WEBVIEW_PAKETE) {
            try {
                val pi = ctx.packageManager.getPackageInfo(p, 0)
                j.put("paket", p).put("version", pi.versionName)
                break
            } catch (e: Exception) { /* naechstes */ }
        }
        try { j.put("ua", WebSettings.getDefaultUserAgent(ctx)) } catch (e: Exception) { }
        return j
    }
}
