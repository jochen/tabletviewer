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

    // --- Kiosk (Ersatz fuer WallPanel) ---

    /** Kiosk beim Booten starten und vorne halten. */
    var kioskAktiv: Boolean
        get() = p.getBoolean("kiosk_aktiv", false)
        set(v) = p.edit().putBoolean("kiosk_aktiv", v).apply()

    /** Leer = Flur-Seite des tabletviewers mit ?geraet=<geraet>. */
    var startUrl: String
        get() = p.getString("start_url", "") ?: ""
        set(v) = p.edit().putString("start_url", v.trim()).apply()

    val startUrlWirksam: String
        get() = startUrl.ifEmpty { "http://192.168.7.23:8080/flur?geraet=$geraet" }

    /** Auch auf wallpanel/<geraet>/command hoeren (erst einschalten, wenn WallPanel weg ist). */
    var wallpanelTopic: Boolean
        get() = p.getBoolean("wallpanel_topic", false)
        set(v) = p.edit().putBoolean("wallpanel_topic", v).apply()

    /** Beim Wecken mindestens so hell (0 = Helligkeit nicht anfassen). */
    var weckenHelligkeit: Int
        get() = p.getInt("wecken_helligkeit", 200)
        set(v) = p.edit().putInt("wecken_helligkeit", v.coerceIn(0, 255)).apply()

    /** Nach so vielen Sekunden auf dem Fire-Startbildschirm den Kiosk zurueckholen (0 = nie). */
    var kioskZurueckS: Int
        get() = p.getInt("kiosk_zurueck_s", 120)
        set(v) = p.edit().putInt("kiosk_zurueck_s", v.coerceAtLeast(0)).apply()

    /** Bildschirm anlassen, solange der Kiosk vorne ist. */
    var bildschirmImmerAn: Boolean
        get() = p.getBoolean("immer_an", true)
        set(v) = p.edit().putBoolean("immer_an", v).apply()

    /** HA-Frontend fuer die alte WebView nachbessern (env()/scrollTo). */
    var haPatch: Boolean
        get() = p.getBoolean("ha_patch", true)
        set(v) = p.edit().putBoolean("ha_patch", v).apply()

    /** Uhrzeit fuer den naechtlichen Neuaufbau des Kiosk ("" = aus). */
    var nachtNeustart: String
        get() = p.getString("nacht_neustart", "04:00") ?: ""
        set(v) = p.edit().putString("nacht_neustart", v.trim()).apply()

    fun kioskJson(): org.json.JSONObject = org.json.JSONObject()
        .put("aktiv", kioskAktiv)
        .put("start_url", startUrlWirksam)
        .put("wallpanel_topic", wallpanelTopic)
        .put("wecken_helligkeit", weckenHelligkeit)
        .put("zurueck_s", kioskZurueckS)
        .put("immer_an", bildschirmImmerAn)
        .put("ha_patch", haPatch)
        .put("nacht_neustart", nachtNeustart)

    /** {"aktiv":true,"start_url":"…",…}; liefert true, wenn neu verbunden werden muss. */
    fun kioskSetzen(j: org.json.JSONObject): Boolean {
        var neuVerbinden = false
        if (j.has("aktiv")) kioskAktiv = j.getBoolean("aktiv")
        if (j.has("start_url")) startUrl = j.getString("start_url")
        if (j.has("wallpanel_topic")) {
            neuVerbinden = j.getBoolean("wallpanel_topic") != wallpanelTopic
            wallpanelTopic = j.getBoolean("wallpanel_topic")
        }
        if (j.has("wecken_helligkeit")) weckenHelligkeit = j.getInt("wecken_helligkeit")
        if (j.has("zurueck_s")) kioskZurueckS = j.getInt("zurueck_s")
        if (j.has("immer_an")) bildschirmImmerAn = j.getBoolean("immer_an")
        if (j.has("ha_patch")) haPatch = j.getBoolean("ha_patch")
        if (j.has("nacht_neustart")) nachtNeustart = j.getString("nacht_neustart")
        return neuVerbinden
    }

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
        i.getStringExtra("start_url")?.let { startUrl = it; geaendert = true }
        if (i.hasExtra("kiosk")) { kioskAktiv = i.getBooleanExtra("kiosk", false); geaendert = true }
        i.getStringExtra("update_basis")?.let { updateBasis = it; geaendert = true }
        i.getStringExtra("status_takt")?.toIntOrNull()?.let { statusTakt = it; geaendert = true }
        return geaendert
    }

    companion object {
        val GERAET_RE = Regex("^[A-Za-z0-9_-]{1,32}$")   // wie im tabletviewer
    }
}
