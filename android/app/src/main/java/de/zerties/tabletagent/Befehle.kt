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
 *   {"bildschirm_aus_ms":600000}              Abschaltzeit des Bildschirms
 *   {"status":true}                           sofort Status senden
 *   {"aktualisieren":true}                    neue Version vom tabletviewer laden und den
 *                                             Installationsdialog zeigen ("erzwingen":true = auch gleiche Version)
 */
class Befehle(private val ctx: Context, private val ausgabe: Ausgabe) {

    interface Ausgabe {
        fun antwort(j: JSONObject)
        fun bildschirmfoto(jpeg: ByteArray)
        fun statusJetzt()
    }

    private val main = Handler(Looper.getMainLooper())
    private var weckLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsBereit = false
    private val ttsWartend = mutableListOf<String>()

    fun ausfuehren(text: String) {
        val cmd = try { JSONObject(text) } catch (e: Exception) {
            ausgabe.antwort(JSONObject().put("ok", false).put("fehler", "kein JSON: ${e.message}"))
            return
        }
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
                    "bildschirm_aus_ms" -> {
                        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, cmd.getInt(k))
                        ergebnis.put(k, cmd.getInt(k))
                    }
                    "status" -> { ausgabe.statusJetzt(); ergebnis.put(k, true) }
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

    @Suppress("DEPRECATION")
    fun wecken(sekunden: Int) {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        weckLock?.let { if (it.isHeld) it.release() }
        weckLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE, "tabletagent:wecken").apply {
            acquire(sekunden.coerceIn(1, 3600) * 1000L)
        }
    }

    private fun helligkeit(wert: Int): Int {
        val v = wert.coerceIn(0, 255)
        val cr = ctx.contentResolver
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, v)
        return v
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
