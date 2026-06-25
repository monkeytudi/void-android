package com.voidapp

import android.content.*
import android.media.session.MediaSessionManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat

class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val ke = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return
        if (ke.action != KeyEvent.ACTION_DOWN) return

        when (ke.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                val svc = Intent(ctx, VoidService::class.java)
                svc.action = "TOGGLE"
                ContextCompat.startForegroundService(ctx, svc)
                abortBroadcast()
            }
        }
    }
}
