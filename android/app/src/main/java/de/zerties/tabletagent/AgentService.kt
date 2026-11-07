package de.zerties.tabletagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONObject

/**
 * Dauerdienst: haelt die MQTT-Verbindung, meldet den Status und fuehrt Befehle aus.
 *
 * Topics (Basis tablet/<geraet>):
 *   online          "online"/"offline" (retained, offline per Last Will)
 *   status          JSON (retained), alle statusTakt Sekunden und nach jedem Befehl
 *   befehl          <- JSON-Befehle (siehe Befehle.kt)
 *   antwort         Ergebnis je Befehl (mit "id", falls mitgeschickt)
 *   bildschirmfoto  JPEG (nicht retained)
 *   konsole         Fehler/Warnungen aus der Kiosk-Seite (hoechstens 30 pro Minute)
 *
 * Mit wallpanel_topic hoert er zusaetzlich auf wallpanel/<geraet>/command.
 */
class AgentService : Service(), Befehle.Ausgabe {

    private lateinit var k: Konfig
    private val thread = HandlerThread("agent").apply { start() }
    private val h = Handler(thread.looper)
    private var client: MqttClient? = null
    private var befehle: Befehle? = null
    private var cpuLock: PowerManager.WakeLock? = null
    private var wlanLock: WifiManager.WifiLock? = null
    private var verbindeVersuche = 0

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        k = Konfig(this)
        startForeground(1, benachrichtigung("startet …"))
        befehle = Befehle(this, this)
        instanz = this
        // Das Tablet haengt am Netzteil: CPU und WLAN wach halten, sonst schlaeft
        // die MQTT-Verbindung bei dunklem Bildschirm ein.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tabletagent:cpu").apply {
            setReferenceCounted(false); acquire()
        }
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wlanLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "tabletagent:wlan").apply {
            setReferenceCounted(false); acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("neu_verbinden", false) == true) {
            h.post { trennen(); verbinden() }
        } else {
            h.post { if (client?.isConnected != true) verbinden() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instanz === this) instanz = null
        h.removeCallbacksAndMessages(null)
        h.post { trennen(); thread.quitSafely() }
        befehle?.ende()
        cpuLock?.release()
        wlanLock?.release()
        super.onDestroy()
    }

    // --- MQTT ---

    private fun verbinden() {
        if (!k.bereit) {
            zustand("nicht eingerichtet (Gerätename fehlt)")
            return
        }
        h.removeCallbacks(neuVersuch)
        val basis = k.topic
        val wpTopic = "wallpanel/${k.geraet}/command"
        try {
            val c = MqttClient("tcp://${k.mqttHost}:${k.mqttPort}", "tablet-agent-${k.geraet}", MemoryPersistence())
            val opt = MqttConnectOptions().apply {
                isCleanSession = true
                isAutomaticReconnect = true
                keepAliveInterval = 60
                connectionTimeout = 10
                maxInflight = 20
                if (k.mqttUser.isNotEmpty()) {
                    userName = k.mqttUser
                    password = k.mqttPass.toCharArray()
                }
                setWill("$basis/online", "offline".toByteArray(), 1, true)
            }
            c.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String) {
                    Log.i(TAG, "verbunden ($serverURI, reconnect=$reconnect)")
                    verbindeVersuche = 0
                    try {
                        c.subscribe("$basis/befehl", 1)
                        if (k.wallpanelTopic) c.subscribe(wpTopic, 0)
                        c.publish("$basis/online", "online".toByteArray(), 1, true)
                    } catch (e: Exception) { Log.w(TAG, "subscribe", e) }
                    zustand("verbunden mit ${k.mqttHost} als ${k.geraet}")
                    h.post { statusSenden() }
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Verbindung weg", cause)
                    zustand("Verbindung weg, versuche neu …")
                }

                override fun messageArrived(topic: String, message: MqttMessage) {
                    val text = String(message.payload, Charsets.UTF_8)
                    // nicht im Paho-Thread arbeiten
                    h.post {
                        Log.i(TAG, "Befehl: $text")
                        befehle?.ausfuehren(text, wallpanel = topic == wpTopic)
                        h.postDelayed({ statusSenden() }, 1500)
                    }
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })
            client = c
            c.connect(opt)
            planeStatus()
            planeKioskWache()
        } catch (e: Exception) {
            Log.w(TAG, "verbinden", e)
            client = null
            // Erstverbindung (z. B. WLAN nach dem Booten noch nicht da): selbst neu versuchen
            val warte = minOf(60_000L, 5_000L * (1 shl minOf(verbindeVersuche++, 4)))
            zustand("keine Verbindung (${e.message}), neuer Versuch in ${warte / 1000} s")
            h.postDelayed(neuVersuch, warte)
        }
    }

    private val neuVersuch = Runnable { verbinden() }

    private fun trennen() {
        h.removeCallbacks(statusTakt)
        h.removeCallbacks(kioskWache)
        val c = client ?: return
        client = null
        try {
            if (c.isConnected) {
                c.publish("${k.topic}/online", "offline".toByteArray(), 1, true)
                c.disconnect(2000)
            }
            c.close()
        } catch (e: Exception) { Log.w(TAG, "trennen", e) }
    }

    private fun senden(unter: String, daten: ByteArray, retained: Boolean = false) {
        val c = client
        if (c == null || !c.isConnected) return
        try { c.publish("${k.topic}/$unter", daten, if (retained) 1 else 0, retained) }
        catch (e: Exception) { Log.w(TAG, "senden $unter", e) }
    }

    // --- Status ---

    private val statusTakt = object : Runnable {
        override fun run() { statusSenden(); h.postDelayed(this, k.statusTakt * 1000L) }
    }

    private fun planeStatus() {
        h.removeCallbacks(statusTakt)
        h.postDelayed(statusTakt, k.statusTakt * 1000L)
    }

    private fun statusSenden() {
        try {
            val j = Status.sammeln(this, k)
            j.put("kiosk", k.kioskJson().put("laeuft", KioskActivity.zustandSync() ?: JSONObject.NULL))
            senden("status", j.toString().toByteArray(), retained = true)
        } catch (e: Exception) { Log.w(TAG, "status", e) }
    }

    // --- Befehle.Ausgabe ---

    override fun antwort(j: JSONObject) { h.post { senden("antwort", j.toString().toByteArray()) } }
    override fun bildschirmfoto(jpeg: ByteArray) { h.post { senden("bildschirmfoto", jpeg) } }
    override fun statusJetzt() { h.post { statusSenden() } }
    override fun neuVerbinden() { h.postDelayed({ trennen(); verbinden() }, 500) }

    // --- Kiosk vorne halten ---
    // Steht der Fire-Startbildschirm laenger als kiosk_zurueck_s vorne, kommt der
    // Kiosk zurueck. Einstellungen, Silk usw. bleiben unangetastet (Admin).
    private var launcherSeit = 0L
    private val launcher: Set<String> by lazy {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet() - packageName
    }

    private val kioskWache = object : Runnable {
        override fun run() {
            try { kioskPruefen() } catch (e: Exception) { Log.w(TAG, "Kiosk-Wache", e) }
            h.postDelayed(this, 15_000)
        }
    }

    private fun planeKioskWache() {
        h.removeCallbacks(kioskWache)
        h.postDelayed(kioskWache, 5_000)
    }

    private var kioskGestartet = false

    // Sperrbildschirm (bei Fire OS mit Werbung) aus, solange der Kiosk aktiv ist
    @Suppress("DEPRECATION")
    private val sperre by lazy {
        (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager)
            .newKeyguardLock("tabletagent")
    }
    private var sperreAus = false

    @Suppress("DEPRECATION")
    private fun sperreSetzen(aus: Boolean) {
        if (aus == sperreAus) return
        try {
            if (aus) sperre.disableKeyguard() else sperre.reenableKeyguard()
            sperreAus = aus
            Log.i(TAG, "Sperrbildschirm ${if (aus) "aus" else "wieder an"}")
        } catch (e: Exception) { Log.w(TAG, "Sperrbildschirm", e) }
    }

    @Suppress("DEPRECATION")
    private fun bildschirmWecken() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "tabletagent:immeran").acquire(10_000)
    }

    private fun kioskPruefen() {
        sperreSetzen(k.kioskAktiv)
        if (!k.kioskAktiv) { launcherSeit = 0; return }
        // "immer an": wie WallPanel; dunkel wird es nachts ueber die Helligkeit
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (k.bildschirmImmerAn && !pm.isInteractive) {
            Log.i(TAG, "Bildschirm aus, wecke (immer an)")
            bildschirmWecken()
            KioskActivity.nachVorne(this)
            return
        }
        if (!kioskGestartet) {          // nach dem Booten bzw. Dienststart einmal nach vorne
            kioskGestartet = true
            if (!KioskActivity.vorne) { bildschirmWecken(); KioskActivity.nachVorne(this) }
            return
        }
        val fg = Status.vordergrund(this)
        if (fg != null && fg in launcher) {
            val jetzt = System.currentTimeMillis()
            if (launcherSeit == 0L) launcherSeit = jetzt
            if (k.kioskZurueckS > 0 && jetzt - launcherSeit >= k.kioskZurueckS * 1000L) {
                Log.i(TAG, "Startbildschirm seit ${k.kioskZurueckS} s vorne, hole Kiosk zurueck")
                launcherSeit = 0
                KioskActivity.nachVorne(this)
            }
        } else launcherSeit = 0
    }

    // --- Konsole der Kiosk-Seite ---
    private val konsoleZeiten = ArrayDeque<Long>()

    private fun konsoleSenden(j: JSONObject) {
        val jetzt = System.currentTimeMillis()
        while (konsoleZeiten.isNotEmpty() && jetzt - konsoleZeiten.first() > 60_000) konsoleZeiten.removeFirst()
        if (konsoleZeiten.size >= 30) return
        konsoleZeiten.addLast(jetzt)
        senden("konsole", j.put("zeit_ms", jetzt).toString().toByteArray())
    }

    // --- Anzeige ---

    private fun zustand(text: String) {
        letzterZustand = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, benachrichtigung(text))
    }

    @Suppress("DEPRECATION")
    private fun benachrichtigung(text: String): Notification {
        val auf = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val b = if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel("agent", "Tablet-Agent", NotificationManager.IMPORTANCE_MIN))
            Notification.Builder(this, "agent")
        } else Notification.Builder(this).setPriority(Notification.PRIORITY_MIN)
        return b.setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Tablet-Agent")
            .setContentText(text)
            .setContentIntent(auf)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "AgentService"
        @Volatile var letzterZustand: String = "aus"
            private set
        @Volatile private var instanz: AgentService? = null

        /** Aus der Kiosk-Seite: Fehler/Warnung weitergeben. */
        fun konsole(j: JSONObject) {
            val s = instanz ?: return
            s.h.post { s.konsoleSenden(j) }
        }

        fun start(ctx: Context, neuVerbinden: Boolean = false) {
            ctx.startService(Intent(ctx, AgentService::class.java).putExtra("neu_verbinden", neuVerbinden))
        }
    }
}
