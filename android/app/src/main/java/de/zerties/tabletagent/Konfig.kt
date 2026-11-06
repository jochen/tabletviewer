package de.zerties.tabletagent

import android.content.Context
import android.content.Intent

/** Einstellungen des Agents (SharedPreferences). */
class Konfig(ctx: Context) {
    private val p = ctx.getSharedPreferences("agent", Context.MODE_PRIVATE)

    /** Geraetename wie beim tabletviewer (?geraet=), z. B. fire10 / kueche. */
    var geraet: String
        get() = p.getString("geraet", "") ?: ""
        set(v) = p.edit().putString("geraet", v.trim()).apply()

    var mqttHost: String
        get() = p.getString("mqtt_host", "192.168.7.76") ?: "192.168.7.76"
        set(v) = p.edit().putString("mqtt_host", v.trim()).apply()

    var mqttPort: Int
        get() = p.getInt("mqtt_port", 1883)
        set(v) = p.edit().putInt("mqtt_port", v).apply()

    var mqttUser: String
        get() = p.getString("mqtt_user", "") ?: ""
        set(v) = p.edit().putString("mqtt_user", v).apply()

    var mqttPass: String
        get() = p.getString("mqtt_pass", "") ?: ""
        set(v) = p.edit().putString("mqtt_pass", v).apply()

    /** Sekunden zwischen zwei Status-Meldungen. */
    var statusTakt: Int
        get() = p.getInt("status_takt", 60)
        set(v) = p.edit().putInt("status_takt", v.coerceIn(10, 3600)).apply()

    /** Dort liegen tablet-agent.apk und tablet-agent.json (tabletviewer). */
    var updateBasis: String
        get() = p.getString("update_basis", "http://192.168.7.23:8080/app/") ?: ""
        set(v) = p.edit().putString("update_basis", v.trim()).apply()

    val bereit: Boolean get() = GERAET_RE.matches(geraet) && mqttHost.isNotEmpty()

    /** Basis der MQTT-Topics: tablet/<geraet> */
    val topic: String get() = "tablet/$geraet"

    /**
     * Einstellungen aus Intent-Extras uebernehmen, damit es auch ohne Tippen geht:
     * adb shell am start -n de.zerties.tabletagent/.MainActivity --es geraet fire10
     */
    fun ausIntent(i: Intent?): Boolean {
        if (i == null) return false
        var geaendert = false
        i.getStringExtra("geraet")?.let { geraet = it; geaendert = true }
        i.getStringExtra("mqtt_host")?.let { mqttHost = it; geaendert = true }
        i.getStringExtra("mqtt_port")?.toIntOrNull()?.let { mqttPort = it; geaendert = true }
        i.getStringExtra("mqtt_user")?.let { mqttUser = it; geaendert = true }
        i.getStringExtra("mqtt_pass")?.let { mqttPass = it; geaendert = true }
        i.getStringExtra("update_basis")?.let { updateBasis = it; geaendert = true }
        i.getStringExtra("status_takt")?.toIntOrNull()?.let { statusTakt = it; geaendert = true }
        return geaendert
    }

    companion object {
        val GERAET_RE = Regex("^[A-Za-z0-9_-]{1,32}$")   // wie im tabletviewer
    }
}
