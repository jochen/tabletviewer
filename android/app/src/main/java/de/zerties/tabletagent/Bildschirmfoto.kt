package de.zerties.tabletagent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.ByteArrayOutputStream

/**
 * Bildschirmfotos ueber MediaProjection (ohne Root).
 *
 * Android fragt einmal nach der Erlaubnis; mit "Nicht mehr anzeigen" kommt
 * der Dialog danach nicht wieder (auch nicht nach einem Neustart). Die Projektion
 * wird im Prozess gehalten und fuer jedes Foto wiederverwendet.
 */
object Bildschirmfoto {
    private const val TAG = "Bildschirmfoto"

    @Volatile var projektion: MediaProjection? = null
        private set
    val erlaubt: Boolean get() = projektion != null

    private val thread = HandlerThread("bildschirmfoto").apply { start() }
    private val handler = Handler(thread.looper)
    private val wartend = mutableListOf<Auftrag>()

    class Auftrag(val breite: Int, val qualitaet: Int, val fertig: (ByteArray?, String?) -> Unit)

    fun setzeProjektion(p: MediaProjection?) {
        projektion = p
        p?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { if (projektion === p) projektion = null }
        }, handler)
        val liste = synchronized(wartend) { wartend.toList().also { wartend.clear() } }
        for (a in liste) {
            if (p == null) a.fertig(null, "Bildschirmfoto nicht erlaubt")
            else handler.post { aufnehmen(p, a) }
        }
    }

    /** Foto machen; holt bei Bedarf erst die Erlaubnis (ProjektionActivity). */
    fun machen(ctx: Context, a: Auftrag) {
        val p = projektion
        if (p != null) { handler.post { aufnehmen(p, a) }; return }
        synchronized(wartend) { wartend.add(a) }
        ctx.startActivity(Intent(ctx, ProjektionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        // Kommt keine Antwort (Dialog wartet auf Antippen), nicht ewig haengen
        handler.postDelayed({
            val offen = synchronized(wartend) { wartend.remove(a) }
            if (offen) a.fertig(null, "Erlaubnis fuer Bildschirmfotos fehlt (Dialog am Tablet bestaetigen)")
        }, 20_000)
    }

    @Suppress("DEPRECATION")
    private fun aufnehmen(p: MediaProjection, a: Auftrag) {
        val ctx = App.ctx
        val dm = DisplayMetrics()
        (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var vd: VirtualDisplay? = null
        var erledigt = false
        fun ende(daten: ByteArray?, fehler: String?) {
            if (erledigt) return
            erledigt = true
            try { vd?.release() } catch (e: Exception) { }
            try { reader.close() } catch (e: Exception) { }
            a.fertig(daten, fehler)
        }
        try {
            vd = p.createVirtualDisplay("tablet-agent", w, h, dm.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "VirtualDisplay", e)
            projektion = null
            ende(null, "Projektion ungueltig: ${e.message}")
            return
        }
        var versuche = 0
        val holen = object : Runnable {
            override fun run() {
                val img = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                if (img == null) {
                    if (++versuche < 20) handler.postDelayed(this, 150)
                    else ende(null, "kein Bild bekommen")
                    return
                }
                try {
                    val plane = img.planes[0]
                    val pixelStride = plane.pixelStride
                    val rowPadding = plane.rowStride - pixelStride * w
                    val roh = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    roh.copyPixelsFromBuffer(plane.buffer)
                    img.close()
                    var bmp = Bitmap.createBitmap(roh, 0, 0, w, h)
                    if (a.breite in 1 until w) {
                        bmp = Bitmap.createScaledBitmap(bmp, a.breite, h * a.breite / w, true)
                    }
                    val out = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, a.qualitaet.coerceIn(10, 95), out)
                    ende(out.toByteArray(), null)
                } catch (e: Exception) {
                    Log.w(TAG, "Umwandeln", e)
                    try { img.close() } catch (x: Exception) { }
                    ende(null, "Umwandeln: ${e.message}")
                }
            }
        }
        // erstes Bild ist manchmal schwarz: kurz warten, dann das neueste nehmen
        handler.postDelayed(holen, 400)
    }
}
