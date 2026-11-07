package de.zerties.tabletagent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * Vollbild-WebView mit der Flur-Seite (Ersatz fuer WallPanel).
 *
 * Admin: fuenfmal schnell in die linke obere Ecke tippen -> Einstellungen.
 */
class KioskActivity : Activity() {

    private lateinit var k: Konfig
    private lateinit var web: WebView
    private val h = Handler(Looper.getMainLooper())
    private var aktuelleUrl: String = ""
    private var fehler: String? = null
    private var geladenUm = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        k = Konfig(this)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        bildschirmAn(k.bildschirmImmerAn)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)   // HA im iframe

        web.webViewClient = object : WebViewClient() {
            // Alles in der WebView behalten
            @Deprecated("API < 24")
            override fun shouldOverrideUrlLoading(view: WebView, url: String) = false
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                fehler = null
            }

            override fun onPageFinished(view: WebView, url: String) {
                aktuelleUrl = url
                geladenUm = System.currentTimeMillis()
            }

            @Deprecated("API < 23")
            override fun onReceivedError(view: WebView, code: Int, beschreibung: String?, url: String?) {
                // nur Fehler der Hauptseite (API 22 meldet nur diese ueber diese Methode)
                fehler = "$code $beschreibung"
                Log.w(TAG, "Ladefehler $url: $fehler")
                AgentService.konsole(JSONObject().put("level", "LADEFEHLER")
                    .put("text", fehler).put("quelle", url ?: ""))
                h.removeCallbacks(neuVersuch)
                h.postDelayed(neuVersuch, 15_000)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR ||
                    m.messageLevel() == ConsoleMessage.MessageLevel.WARNING) {
                    AgentService.konsole(JSONObject()
                        .put("level", m.messageLevel().name)
                        .put("text", m.message())
                        .put("quelle", m.sourceId())
                        .put("zeile", m.lineNumber()))
                }
                return true
            }
        }
        setContentView(web)
        vollbild()
        aktiv = WeakReference(this)
        laden(intent?.getStringExtra("url") ?: k.startUrlWirksam)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.getStringExtra("url")?.let { laden(it) }
    }

    override fun onResume() {
        super.onResume()
        vorne = true
        vollbild()
        bildschirmAn(k.bildschirmImmerAn)
    }

    override fun onPause() {
        vorne = false
        super.onPause()
    }

    override fun onDestroy() {
        if (aktiv?.get() === this) aktiv = null
        h.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) vollbild()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Zurueck bleibt in der Seite")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack()
    }

    // --- Admin-Geste: 5 Taps links oben innerhalb von 3 s ---
    private val taps = ArrayDeque<Long>()

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val ecke = 80 * resources.displayMetrics.density
            if (ev.x < ecke && ev.y < ecke) {
                val t = SystemClock.uptimeMillis()
                taps.addLast(t)
                while (taps.isNotEmpty() && t - taps.first() > 3000) taps.removeFirst()
                if (taps.size >= 5) {
                    taps.clear()
                    startActivity(Intent(this, MainActivity::class.java))
                }
            } else taps.clear()
        }
        return super.dispatchTouchEvent(ev)
    }

    // --- Steuerung ---

    private val neuVersuch = Runnable { if (fehler != null) laden(aktuelleUrl.ifEmpty { k.startUrlWirksam }) }

    fun laden(url: String) {
        aktuelleUrl = url
        h.removeCallbacks(neuVersuch)
        web.loadUrl(url)
    }

    fun neuLaden() {
        h.removeCallbacks(neuVersuch)
        if (web.url.isNullOrEmpty()) laden(k.startUrlWirksam) else web.reload()
    }

    fun cacheLeeren() {
        web.clearCache(true)
        neuLaden()
    }

    fun js(code: String, fertig: (String?) -> Unit) = web.evaluateJavascript(code) { fertig(it) }

    fun bildschirmAn(an: Boolean) {
        if (an) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun zustand(): JSONObject = JSONObject()
        .put("vorne", vorne)
        .put("url", web.url ?: aktuelleUrl)
        .put("fehler", fehler ?: JSONObject.NULL)
        .put("geladen_um_ms", if (geladenUm > 0) geladenUm else JSONObject.NULL)

    @Suppress("DEPRECATION")
    private fun vollbild() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    companion object {
        private const val TAG = "Kiosk"
        @Volatile private var aktiv: WeakReference<KioskActivity>? = null
        @Volatile var vorne = false
            private set

        private val main = Handler(Looper.getMainLooper())

        /** Kiosk nach vorne holen (startet ihn bei Bedarf), optional mit URL. */
        fun nachVorne(ctx: Context, url: String? = null) {
            val i = Intent(ctx, KioskActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            if (url != null) i.putExtra("url", url)
            main.post { ctx.startActivity(i) }
        }

        /** Etwas mit dem laufenden Kiosk tun; false, wenn keiner laeuft. */
        fun mit(aktion: (KioskActivity) -> Unit): Boolean {
            val a = aktiv?.get() ?: return false
            main.post { if (!a.isDestroyed) aktion(a) }
            return true
        }

        /** Zustand fuer den Status (aus dem Agent-Thread, daher kurz auf den Main-Thread warten). */
        fun zustandSync(): JSONObject? {
            val a = aktiv?.get() ?: return null
            if (Looper.myLooper() == Looper.getMainLooper()) return a.zustand()
            var r: JSONObject? = null
            val lock = Object()
            synchronized(lock) {
                main.post { synchronized(lock) { r = try { a.zustand() } catch (e: Exception) { null }; lock.notifyAll() } }
                lock.wait(1000)
            }
            return r
        }
    }
}
