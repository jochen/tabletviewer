package de.zerties.tabletagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/** Unsichtbare Activity, die nur die Erlaubnis fuer Bildschirmfotos holt. */
class ProjektionActivity : Activity() {
    private val mpm by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (b == null) startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val p = if (resultCode == RESULT_OK && data != null) mpm.getMediaProjection(resultCode, data) else null
        Bildschirmfoto.setzeProjektion(p)
        finish()
        overridePendingTransition(0, 0)
    }
}
