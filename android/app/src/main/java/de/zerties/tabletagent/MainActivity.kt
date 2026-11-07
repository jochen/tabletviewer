package de.zerties.tabletagent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Einrichtung: Geraetename und MQTT. Geht auch ohne Tippen per adb:
 *   adb shell am start -n de.zerties.tabletagent/.MainActivity --es geraet fire10
 */
class MainActivity : Activity() {
    private lateinit var k: Konfig
    private lateinit var info: TextView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        k = Konfig(this)
        val ausIntent = k.ausIntent(intent)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val l = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun feld(titel: String, wert: String, typ: Int = InputType.TYPE_CLASS_TEXT): EditText {
            l.addView(TextView(this).apply { text = titel })
            return EditText(this).apply { setText(wert); inputType = typ; setSingleLine() }.also { l.addView(it) }
        }
        l.addView(TextView(this).apply { text = "Tablet-Agent"; textSize = 24f })
        info = TextView(this).apply { setPadding(0, pad / 2, 0, pad) }
        l.addView(info)
        val geraet = feld("Gerätename (wie ?geraet= im tabletviewer, z. B. fire10)", k.geraet)
        val host = feld("MQTT-Server", k.mqttHost)
        val port = feld("MQTT-Port", k.mqttPort.toString(), InputType.TYPE_CLASS_NUMBER)
        val user = feld("MQTT-Benutzer (leer = ohne)", k.mqttUser)
        val pass = feld("MQTT-Passwort", k.mqttPass,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)

        l.addView(Button(this).apply {
            text = "Speichern und verbinden"
            setOnClickListener {
                if (!Konfig.GERAET_RE.matches(geraet.text.toString().trim())) {
                    Toast.makeText(this@MainActivity, "Gerätename: nur Buchstaben, Ziffern, - und _", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                k.geraet = geraet.text.toString()
                k.mqttHost = host.text.toString()
                k.mqttPort = port.text.toString().toIntOrNull() ?: 1883
                k.mqttUser = user.text.toString()
                k.mqttPass = pass.text.toString()
                AgentService.start(this@MainActivity, neuVerbinden = true)
            }
        })
        l.addView(Button(this).apply {
            text = "Zum Kiosk"
            setOnClickListener { KioskActivity.nachVorne(this@MainActivity) }
        })
        l.addView(Button(this).apply {
            text = "Bildschirmfotos erlauben"
            setOnClickListener { startActivity(Intent(this@MainActivity, ProjektionActivity::class.java)) }
        })
        setContentView(ScrollView(this).apply { addView(l, MATCH_PARENT, WRAP_CONTENT) })

        AgentService.start(this, neuVerbinden = ausIntent)
    }

    private val anzeigen = object : Runnable {
        override fun run() {
            info.text = "Zustand: ${AgentService.letzterZustand}\n" +
                    "Topics: ${if (k.bereit) k.topic + "/…" else "-"}\n" +
                    "Kiosk: ${if (k.kioskAktiv) "aktiv" else "aus"} (${k.startUrlWirksam})\n" +
                    "Bildschirmfoto: ${if (Bildschirmfoto.erlaubt) "erlaubt" else "noch nicht erlaubt"}\n" +
                    "Vordergrund-App: ${Status.vordergrund(this@MainActivity) ?: "unbekannt"}"
            h.postDelayed(this, 1000)
        }
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        if (k.ausIntent(i)) { AgentService.start(this, neuVerbinden = true); recreate() }
    }

    override fun onResume() { super.onResume(); h.post(anzeigen) }
    override fun onPause() { h.removeCallbacks(anzeigen); super.onPause() }
}
