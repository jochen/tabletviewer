package de.zerties.tabletagent

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Macht das Home-Assistant-Frontend fuer die alte WebView (Chrome 59) lesbar.
 *
 * HA liefert alten Browsern frontend_es5 aus, nutzt darin aber CSS mit env()
 * (safe-area-inset-*, ab Chrome 69). Chrome 59 verwirft solche Regeln komplett,
 * z. B. den Abstand unter der Kopfleiste -> die Kacheln liegen unter der Leiste.
 * Wir fangen die Skripte ab und ersetzen env(safe-area-inset-x[, ersatz]) durch
 * den Ersatzwert bzw. 0px. Dazu kommt ein Polyfill fuer scrollTo({…}) (ab Chrome 61).
 *
 * Die Dateien in frontend_es5 tragen einen Hash im Namen und aendern sich nie;
 * die gepatchte Fassung wird deshalb auf der Platte zwischengespeichert.
 */
object HaPatch {
    private const val TAG = "HaPatch"

    private val ENV_RE = Regex("""env\(\s*safe-area-inset-[a-z]+\s*(?:,\s*([^()]*(?:\([^()]*\)[^()]*)*))?\)""")

    private const val POLYFILL = """;(function(){try{
if('scrollBehavior' in document.documentElement.style)return;
function o(a,b,c){return(a&&typeof a==='object')?[a.left!==undefined?a.left:b,a.top!==undefined?a.top:c]:null}
function w(t,n,rel){var f=t[n];if(!f&&n!=='scrollTo')return;t[n]=function(a,b){
 var self=this===undefined?window:this;
 if(a&&typeof a==='object'){
  if(self===window||self===document){var x=rel?pageXOffset:0,y=rel?pageYOffset:0;
   var p=o(a,rel?0:pageXOffset,rel?0:pageYOffset);return (f||window.scrollTo).call(window,p[0]+x,p[1]+y)}
  var p2=o(a,rel?0:self.scrollLeft,rel?0:self.scrollTop);
  self.scrollLeft=(rel?self.scrollLeft:0)+p2[0];self.scrollTop=(rel?self.scrollTop:0)+p2[1];return}
 if(self!==window&&!f){self.scrollLeft=a;self.scrollTop=b;return}
 return f.apply(self,arguments)}}
w(window,'scrollTo',false);w(window,'scroll',false);w(window,'scrollBy',true);
var E=Element.prototype;w(E,'scrollTo',false);w(E,'scroll',false);w(E,'scrollBy',true);
}catch(e){}})();
"""

    @Volatile var gepatcht = 0
        private set

    private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "ha-patch").apply { mkdirs() }

    /** Alte Dateien (fruehere HA-Versionen) wegraeumen. */
    fun aufraeumen(ctx: Context) {
        val grenze = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        cacheDir(ctx).listFiles()?.forEach { if (it.lastModified() < grenze) it.delete() }
    }

    fun betrifft(url: String): Boolean = url.contains("/frontend_es5/") && url.substringBefore('?').endsWith(".js")

    fun patchen(text: String, mitPolyfill: Boolean): String {
        var t = ENV_RE.replace(text) { m -> m.groupValues[1].trim().ifEmpty { "0px" } }
        if (mitPolyfill) t = POLYFILL + t
        return t
    }

    /** Liefert die gepatchte Datei oder null (dann laedt die WebView normal). */
    fun abfangen(ctx: Context, url: String): WebResourceResponse? {
        return try {
            val name = url.substringBefore('?').substringAfterLast('/')
            val datei = File(cacheDir(ctx), name)
            val daten = if (datei.isFile) {
                datei.setLastModified(System.currentTimeMillis())
                datei.readBytes()
            } else {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000
                c.readTimeout = 30_000
                if (c.responseCode != 200) { c.disconnect(); return null }
                val roh = c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
                c.disconnect()
                // core/app laden zuerst: dort den scrollTo-Polyfill voranstellen
                val polyfill = name.startsWith("core.") || name.startsWith("app.")
                val neu = patchen(roh, polyfill).toByteArray(Charsets.UTF_8)
                val tmp = File(datei.path + ".tmp")
                tmp.writeBytes(neu)
                tmp.renameTo(datei)
                neu
            }
            gepatcht++
            WebResourceResponse("application/javascript", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-cache", "Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(daten))
        } catch (e: Exception) {
            Log.w(TAG, "abfangen $url", e)
            null
        }
    }
}
