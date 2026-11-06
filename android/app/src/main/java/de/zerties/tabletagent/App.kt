package de.zerties.tabletagent

import android.app.Application
import android.content.Context

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ctx = applicationContext
    }

    companion object {
        lateinit var ctx: Context
            private set
    }
}
