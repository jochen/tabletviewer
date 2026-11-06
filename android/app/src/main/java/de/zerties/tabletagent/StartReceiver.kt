package de.zerties.tabletagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Startet den Agent nach dem Booten und nach einem App-Update. */
class StartReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ctx.startService(Intent(ctx, AgentService::class.java))
    }
}
