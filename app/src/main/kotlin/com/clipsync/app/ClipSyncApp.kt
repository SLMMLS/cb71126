package com.clipsync.app

import android.app.Application
import com.clipsync.app.files.Transfers

class ClipSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The list of file transfers, the receiver and the sending queue exist once per process, before
        // any screen, service or share target needs them (files/Transfers.kt).
        Transfers.init(this)
    }
}
