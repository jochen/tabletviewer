package de.zerties.tabletagent

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Fuehrt Befehle aus tablet/<geraet>/befehl aus.
 *
 * Ein Befehl ist ein JSON-Objekt, mehrere Schluessel sind erlaubt und werden
 * nacheinander ausgefuehrt. Die WallPanel-Schluessel brightness/wake/wakeTime/
 * volume/speak gehen auch, damit Node-RED spaeter einfach umziehen kann.
 *
 *   {"id":"x1", "wecken":240}                 Bildschirm an fuer 240 s
 *   {"helligkeit":128}                        0..255
 *   {"lautstaerke":50}                        Prozent (Medien)
 *   {"sage":"Hallo"}                          Sprachausgabe
 *   {"starte":"com.thanksmister.iot.wallpanel", "neu":true}
 *   {"oeffne":"http://..."}                   URL im Standard-Browser
 *   {"bildschirmfoto":true} / {"bildschirmfoto":{"breite":640,"qualitaet":70}}
 *   {"apps":true}                             installierte Apps mit Version
 *   {"autostart":true}                        Apps, die beim Booten starten
 *   {"bildschirm_aus_ms":600000}              Abschaltzeit des Bildschirms
 *   {"status":true}                           sofort Status senden
 *   {"aktualisieren":true}                    neue Version vom tabletviewer laden und den
 *                                             Installationsdialog zeigen ("erzwingen":true = auch gleiche Version)
 *
 * Kiosk:
 *   {"kiosk":{"aktiv":true,"start_url":"…","wallpanel_topic":false,
 *             "wecken_helligkeit":200,"zurueck_s":120,"immer_an":true}}   Einstellungen
 *   {"kiosk":true}                            nur Einstellungen abfragen
 *   {"vorne":true}                            Kiosk nach vorne holen
 *   {"laden":"http://…"}                      URL im Kiosk laden (nicht gespeichert)
 *   {"neu_laden":true} / {"cache_leeren":true}
 *   {"js":"document.title"}                   JavaScript im Kiosk, Ergebnis in der Antwort
 *
 * Auf wallpanel/<geraet>/command (wenn eingeschaltet) werden die WallPanel-Namen
 * url, reload, clearCache, eval und relaunch uebersetzt.
 */
class Befehle(private val ctx: Context, private val ausgabe: Ausgabe) {

    interface Ausgabe {
        fun antwort(j: JSONObject)
        fun bildschirmfoto(jpeg: ByteArray)
        fun statusJetzt()
        fun neuVerbinden()
    }

    private val k = Konfig(ctx)

    private val main = Handler(Looper.getMainLooper())
    private var weckLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsBereit = false
    private val ttsWartend = mutableListOf<String>()

    fun ausfuehren(text: String, wallpanel: Boolean = false) {
        var cmd = try { JSONObject(text) } catch (e: Exception) {
            ausgabe.antwort(JSONObject().put("ok", false).put("fehler", "kein JSON: ${e.message}"))
            return
        }
        if (wallpanel) cmd = vonWallpanel(cmd)
        val id = cmd.opt("id")
        val ergebnis = JSONObject()
        val fehler = JSONObject()
        val schluessel = cmd.keys().asSequence().toList()
        var asynchron = false

        for (k in schluessel) {
            try {
                when (k) {
                    "id", "neu", "wakeTime", "erzwingen" -> {}
                    "aktualisieren" -> ergebnis.put(k, aktualisieren(cmd.optBoolean("erzwingen")))
                    "wecken", "wake" -> {
                        val v = cmd.get(k)
                        if (v == false) continue
                        val s = when {
                            v is Number -> v.toInt()
                            cmd.has("wakeTime") -> cmd.getInt("wakeTime")
                            else -> 60
                        }
                        wecken(s); ergebnis.put(k, s)
                    }
                    "helligkeit", "brightness" -> ergebnis.put(k, helligkeit(cmd.getInt(k)))
                    "lautstaerke", "volume" -> ergebnis.put(k, lautstaerke(cmd.getInt(k)))
                    "sage", "speak" -> { sage(cmd.getString(k)); ergebnis.put(k, true) }
                    "starte" -> { starte(cmd.getString(k), cmd.optBoolean("neu")); ergebnis.put(k, true) }
                    "oeffne" -> { oeffne(cmd.getString(k)); ergebnis.put(k, true) }
                    "apps" -> ergebnis.put(k, apps())
                    "autostart" -> ergebnis.put(k, autostart())
                    "bildschirm_aus_ms" -> {
                        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, cmd.getInt(k))
                        ergebnis.put(k, cmd.getInt(k))
                    }
                    "status" -> { ausgabe.statusJetzt(); ergebnis.put(k, true) }
                    "kiosk" -> ergebnis.put(k, kiosk(cmd.optJSONObject(k)))
                    "vorne" -> { if (cmd.optBoolean(k, true)) KioskActivity.nachVorne(ctx); ergebnis.put(k, true) }
                    "laden" -> {
                        val url = cmd.getString(k)
                        if (!KioskActivity.mit { it.laden(url) }) KioskActivity.nachVorne(ctx, url)
                        ergebnis.put(k, url)
                    }
                    "neu_laden" -> ergebnis.put(k, KioskActivity.mit { it.neuLaden() })
                    "cache_leeren" -> ergebnis.put(k, KioskActivity.mit { it.cacheLeeren() })
                    "js" -> {
                        asynchron = true
                        val code = cmd.getString(k)
                        val laeuft = KioskActivity.mit { a ->
                            a.js(code) { r ->
                                ergebnis.put(k, r ?: JSONObject.NULL)
                                ausgabe.antwort(antwort(id, ergebnis, fehler))
                            }
                        }
                        if (!laeuft) { fehler.put(k, "Kiosk laeuft nicht"); asynchron = false }
                    }
                    "bildschirmfoto" -> {
                        val o = cmd.optJSONObject(k)
                        asynchron = true
                        Bildschirmfoto.machen(ctx, Bildschirmfoto.Auftrag(
                            o?.optInt("breite", 800) ?: 800,
                            o?.optInt("qualitaet", 70) ?: 70) { jpeg, f ->
                            if (jpeg != null) {
                                ausgabe.bildschirmfoto(jpeg)
                                ergebnis.put(k, JSONObject().put("bytes", jpeg.size))
                            } else fehler.put(k, f)
                            ausgabe.antwort(antwort(id, ergebnis, fehler))
                        })
                    }
                    else -> fehler.put(k, "unbekannter Befehl")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Befehl $k", e)
                fehler.put(k, e.message ?: e.javaClass.simpleName)
            }
        }
        if (!asynchron) ausgabe.antwort(antwort(id, ergebnis, fehler))
    }

    private fun antwort(id: Any?, ergebnis: JSONObject, fehler: JSONObject) = JSONObject().apply {
        if (id != null) put("id", id)
        put("ok", fehler.length() == 0)
        put("ergebnis", ergebnis)
        if (fehler.length() > 0) put("fehler", fehler)
    }

    /** WallPanel-Befehl in unsere Schluessel uebersetzen. */
    private fun vonWallpanel(w: JSONObject): JSONObject {
        val r = JSONObject()
        for (key in w.keys()) {
            val v = w.get(key)
            when (key) {
                "url" -> r.put("laden", v)
                "reload" -> if (v == true) r.put("neu_laden", true)
                "clearCache" -> if (v == true) r.put("cache_leeren", true)
                "eval" -> r.put("js", v)
                "relaunch" -> if (v == true) { r.put("vorne", true); r.put("neu_laden", true) }
                "brightness", "wake", "wakeTime", "speak", "volume" -> r.put(key, v)
                else -> r.put(key, v)   // landet als "unbekannter Befehl" in der Antwort
            }
        }
        return r
    }

    private fun kiosk(j: JSONObject?): JSONObject {
        if (j != null) {
            val warAktiv = k.kioskAktiv
            if (k.kioskSetzen(j)) ausgabe.neuVerbinden()
            if (k.kioskAktiv && !warAktiv) KioskActivity.nachVorne(ctx)
            if (j.has("start_url")) KioskActivity.mit { it.laden(k.startUrlWirksam) }
            if (j.has("immer_an")) KioskActivity.mit { it.bildschirmAn(k.bildschirmImmerAn) }
        }
        return k.kioskJson()
    }

    // --- Wecken mit voruebergehend hellerem Bildschirm ---
    // Nachts steht die Helligkeit auf 0 (Zeitschaltuhr in Node-RED). Klingelt es,
    // soll man trotzdem etwas sehen: fuer die Weckzeit mindestens wecken_helligkeit,
    // danach zurueck auf den Wert, der zuletzt per Befehl gesetzt wurde.
    @Volatile private var helligkeitNachWecken: Int? = null
    private val weckenEnde = Runnable {
        helligkeitNachWecken?.let { setzeHelligkeit(it) }
        helligkeitNachWecken = null
    }

    @Suppress("DEPRECATION")
    fun wecken(sekunden: Int) {
        val dauer = sekunden.coerceIn(1, 3600) * 1000L
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        weckLock?.let { if (it.isHeld) it.release() }
        weckLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE, "tabletagent:wecken").apply { acquire(dauer) }
        val ziel = k.weckenHelligkeit
        if (ziel > 0) {
            val jetzt = Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 255)
            if (helligkeitNachWecken == null && jetzt < ziel) {
                helligkeitNachWecken = jetzt
                setzeHelligkeit(ziel)
            }
            if (helligkeitNachWecken != null) {
                main.removeCallbacks(weckenEnde)
                main.postDelayed(weckenEnde, dauer)
            }
        }
        if (k.kioskAktiv && !KioskActivity.vorne) KioskActivity.nachVorne(ctx)
    }

    private fun helligkeit(wert: Int): Int {
        val v = wert.coerceIn(0, 255)
        // Waehrend des Weckens nicht dunkler machen, nur den Wert fuer danach merken
        if (helligkeitNachWecken != null && v < k.weckenHelligkeit) {
            helligkeitNachWecken = v
            return v
        }
        if (helligkeitNachWecken != null) {
            helligkeitNachWecken = null
            main.removeCallbacks(weckenEnde)
        }
        setzeHelligkeit(v)
        return v
    }

    private fun setzeHelligkeit(v: Int) {
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
    }

    private fun lautstaerke(prozent: Int): Int {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = (prozent.coerceIn(0, 100) * max + 50) / 100
        am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
        return v
    }

    private fun sage(text: String) = main.post {
        if (tts == null) {
            tts = TextToSpeech(ctx) { st ->
                ttsBereit = st == TextToSpeech.SUCCESS
                if (ttsBereit) {
                    tts?.language = Locale.GERMAN
                    ttsWartend.forEach { sprich(it) }
                }
                ttsWartend.clear()
            }
        }
        if (ttsBereit) sprich(text) else ttsWartend.add(text)
    }

    @Suppress("DEPRECATION")
    private fun sprich(t: String) { tts?.speak(t, TextToSpeech.QUEUE_ADD, null) }

    private fun starte(paket: String, neu: Boolean) {
        val i = ctx.packageManager.getLaunchIntentForPackage(paket)
            ?: throw IllegalArgumentException("App $paket nicht gefunden")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (neu) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        main.post { ctx.startActivity(i) }
    }

    private fun oeffne(url: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post { ctx.startActivity(i) }
    }

    /** Laedt die APK und oeffnet den Installer (ohne Device Owner muss jemand tippen). */
    private fun aktualisieren(erzwingen: Boolean): JSONObject {
        val basis = Konfig(ctx).updateBasis.trimEnd('/') + "/"
        val info = JSONObject(java.net.URL(basis + "tablet-agent.json").readText())
        val neu = info.getInt("versionCode")
        @Suppress("DEPRECATION")
        val jetzt = ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionCode
        val r = JSONObject().put("installiert", jetzt).put("verfuegbar", neu)
        if (neu <= jetzt && !erzwingen) return r.put("aktion", "schon aktuell")
        val ziel = java.io.File(ctx.externalCacheDir ?: ctx.cacheDir, "tablet-agent.apk")
        java.net.URL(basis + "tablet-agent.apk").openStream().use { ein ->
            ziel.outputStream().use { ein.copyTo(it) }
        }
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(ziel.readBytes()).joinToString("") { "%02x".format(it) }
        if (info.has("sha256") && sha != info.getString("sha256")) {
            ziel.delete(); throw IllegalStateException("Pruefsumme passt nicht")
        }
        ziel.setReadable(true, false)
        wecken(120)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(ziel), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        main.post { ctx.startActivity(i) }
        return r.put("aktion", "Installationsdialog angezeigt")
    }

    /** Apps (ohne System), die beim Booten gestartet werden (BOOT_COMPLETED-Empfaenger). */
    private fun autostart(): JSONArray {
        val pm = ctx.packageManager
        val r = JSONArray()
        val gesehen = mutableSetOf<String>()
        for (aktion in listOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON")) {
            for (ri in pm.queryBroadcastReceivers(Intent(aktion), 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
                if (!gesehen.add(ai.packageName + "/" + ai.name)) continue
                r.put(JSONObject().put("paket", ai.packageName).put("empfaenger", ai.name)
                    .put("aktiv", ai.enabled && ai.applicationInfo.enabled))
            }
        }
        return r
    }

    private fun apps(): JSONArray {
        val pm = ctx.packageManager
        val a = JSONArray()
        for (pi in pm.getInstalledPackages(0).sortedBy { it.packageName }) {
            val ai = pi.applicationInfo ?: continue
            if (pm.getLaunchIntentForPackage(pi.packageName) == null &&
                ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) continue
            a.put(JSONObject()
                .put("paket", pi.packageName)
                .put("name", pm.getApplicationLabel(ai).toString())
                .put("version", pi.versionName ?: JSONObject.NULL)
                .put("system", ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0)
                .put("aktiv", ai.enabled))
        }
        return a
    }

    fun ende() {
        weckLock?.let { if (it.isHeld) it.release() }
        main.post { tts?.shutdown(); tts = null }
    }

    companion object { private const val TAG = "Befehle" }
}
