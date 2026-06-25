package com.voidapp

import android.content.*
import androidx.core.content.ContextCompat

class NotifButtonReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == "TOGGLE") {
            val svc = Intent(ctx, VoidService::class.java).apply { action = "TOGGLE" }
            ContextCompat.startForegroundService(ctx, svc)
        }
    }
}
