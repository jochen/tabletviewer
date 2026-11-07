package de.zerties.tabletagent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Haelt den Kiosk am Leben. Wird vom AgentService-Thread alle 15 s aufgerufen,
 * also auch dann, wenn der Main-Thread (WebView) haengt.
 *
 * 1. Lebenszeichen: alle 30 s fragt der Agent per JavaScript die Seite ab.
 *    Bleibt die Antwort aus, eskaliert er schrittweise (jeweils 60 s Abstand):
 *      neu laden -> Kiosk neu aufbauen -> App-Prozess neu starten (per Alarm).
 * 2. Verbindung der Seite: Meldet die Seite (window.tabletAgentPruefen) seit
 *    5 min keinen Kontakt zum tabletviewer, der Server ist aber erreichbar,
 *    dann haengt die Seite -> neu laden (hoechstens alle 10 min).
 * 3. Nachts (nacht_neustart, Standard 04:00) wird der Kiosk einmal frisch
 *    aufgebaut, wenn 10 min niemand getippt hat (gibt Speicher frei, 1 GB RAM).
 * 4. MQTT: Ist der Client 5 min nicht verbunden, wird er neu aufgebaut.
 */
class Selbstheilung(private val ctx: Context, private val k: Konfig, private val log: (String) -> Unit) {

    private var letzteAnfrage = 0L
    private var stufe = 0
    private var stufeSeit = 0L
    private var letzteSeitenHeilung = 0L
    private var nachtNeustartTag = -1
    var mqttGetrenntSeit = 0L

    val zaehler = JSONObject()
        .put("neu_laden", 0).put("neu_aufbauen", 0).put("prozess", 0)
        .put("seite_ohne_server", 0).put("nacht", 0).put("mqtt", 0)

    private fun zaehle(was: String) = zaehler.put(was, zaehler.optInt(was) + 1)

    fun pruefen() {
        if (!k.kioskAktiv) { stufe = 0; return }
        val jetzt = System.currentTimeMillis()
        if (!KioskActivity.laeuft) { stufe = 0; return }

        // --- 1. Lebenszeichen ---
        if (KioskActivity.vorne && jetzt - letzteAnfrage >= 30_000) {
            letzteAnfrage = jetzt
            KioskActivity.mit { it.pruefen() }
        }
        val alter = jetzt - KioskActivity.letzteAntwortMs
        if (KioskActivity.vorne && alter > 90_000) {
            if (stufe == 0 || jetzt - stufeSeit >= 60_000) {
                stufe++
                stufeSeit = jetzt
                when (stufe) {
                    1 -> {
                        log("Seite antwortet seit ${alter / 1000} s nicht: neu laden")
                        zaehle("neu_laden")
                        KioskActivity.mit { it.neuLaden() }
                    }
                    2 -> {
                        log("Seite antwortet weiter nicht: Kiosk neu aufbauen")
                        zaehle("neu_aufbauen")
                        KioskActivity.neuAufbauen(ctx)
                    }
                    else -> {
                        log("Kiosk haengt weiter: App-Prozess neu starten")
                        zaehle("prozess")
                        prozessNeustart()
                    }
                }
            }
            return
        }
        if (alter <= 90_000) stufe = 0

        // --- 2. Seite ohne Kontakt zum Server ---
        val kontakt = KioskActivity.seitenKontaktVorS
        if (kontakt != null && kontakt > 300 && jetzt - letzteSeitenHeilung > 600_000) {
            if (serverErreichbar()) {
                letzteSeitenHeilung = jetzt
                log("Seite seit $kontakt s ohne Serverkontakt, Server erreichbar: neu laden")
                zaehle("seite_ohne_server")
                KioskActivity.mit { it.neuLaden() }
            }
        }

        // --- 3. Nachts einmal frisch aufbauen ---
        nachtNeustart(jetzt)
    }

    private fun nachtNeustart(jetzt: Long) {
        val um = k.nachtNeustart
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(um) ?: return
        val c = Calendar.getInstance()
        val minuten = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val ziel = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        val tag = c.get(Calendar.DAY_OF_YEAR)
        if (minuten < ziel || minuten >= ziel + 30 || tag == nachtNeustartTag) return
        if (jetzt - KioskActivity.letzteBeruehrungMs < 600_000) return   // jemand bedient gerade
        nachtNeustartTag = tag
        log("Naechtlicher Neuaufbau des Kiosk")
        zaehle("nacht")
        KioskActivity.neuAufbauen(ctx)
    }

    /** Liefert true, wenn der MQTT-Client neu aufgebaut werden soll. */
    fun mqttPruefen(verbunden: Boolean, clientDa: Boolean): Boolean {
        val jetzt = System.currentTimeMillis()
        if (verbunden || !clientDa) { mqttGetrenntSeit = 0; return false }
        if (mqttGetrenntSeit == 0L) mqttGetrenntSeit = jetzt
        if (jetzt - mqttGetrenntSeit < 300_000) return false
        mqttGetrenntSeit = 0
        zaehle("mqtt")
        Log.w(TAG, "MQTT seit 5 min getrennt, baue Client neu auf")
        return true
    }

    private fun serverErreichbar(): Boolean = try {
        val c = URL(k.startUrlWirksam).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 5000
        val ok = c.responseCode in 200..399
        c.disconnect()
        ok
    } catch (e: Exception) { false }

    private fun prozessNeustart() {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getService(ctx, 7, Intent(ctx, AgentService::class.java),
            if (android.os.Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3000, pi)
        Process.killProcess(Process.myPid())
    }

    fun json(): JSONObject = JSONObject(zaehler.toString())
        .put("stufe", stufe)
        .put("antwort_vor_s", if (KioskActivity.laeuft) (System.currentTimeMillis() - KioskActivity.letzteAntwortMs) / 1000 else JSONObject.NULL)
        .put("seite_kontakt_vor_s", KioskActivity.seitenKontaktVorS ?: JSONObject.NULL)

    companion object { private const val TAG = "Selbstheilung" }
}
