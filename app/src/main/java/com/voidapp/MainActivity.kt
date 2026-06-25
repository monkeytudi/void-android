package com.voidapp

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private var service: VoidService? = null
    private var bound = false

    private lateinit var orbBtn:    FrameLayout
    private lateinit var statusTxt: TextView
    private lateinit var convBtn:   Button
    private lateinit var backendUrl: EditText

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            service = (b as VoidService.LocalBinder).getService()
            bound = true
            service?.onStateChange = { state -> runOnUiThread { updateUI(state) } }
        }
        override fun onServiceDisconnected(n: ComponentName) { bound = false }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        orbBtn     = findViewById(R.id.orbBtn)
        statusTxt  = findViewById(R.id.statusText)
        convBtn    = findViewById(R.id.convBtn)
        backendUrl = findViewById(R.id.backendUrl)

        // Load saved backend URL
        val prefs = getSharedPreferences("void", Context.MODE_PRIVATE)
        backendUrl.setText(prefs.getString("backend_url", "https://your-app.railway.app"))

        // Save URL on change
        backendUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveUrl()
        }

        orbBtn.setOnClickListener {
            saveUrl()
            service?.toggleRecording()
        }

        convBtn.setOnClickListener {
            service?.toggleConversation()
        }

        requestMicPermission()
        startAndBindService()
    }

    private fun saveUrl() {
        val url = backendUrl.text.toString().trim().trimEnd('/')
        getSharedPreferences("void", Context.MODE_PRIVATE).edit()
            .putString("backend_url", url).apply()
        service?.backendUrl = url
    }

    private fun startAndBindService() {
        val intent = Intent(this, VoidService::class.java)
        ContextCompat.startForegroundService(this, intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    fun updateUI(state: VoidService.VoidState) {
        when (state) {
            VoidService.VoidState.IDLE        -> { statusTxt.text = "Kopfhörer-Taste drücken"; orbBtn.alpha = 1f }
            VoidService.VoidState.RECORDING   -> { statusTxt.text = "Aufnahme läuft…";         orbBtn.alpha = 0.6f }
            VoidService.VoidState.PROCESSING  -> { statusTxt.text = "Verarbeite…";              orbBtn.alpha = 0.4f }
            VoidService.VoidState.SPEAKING    -> { statusTxt.text = "Void spricht…";            orbBtn.alpha = 0.8f }
            VoidService.VoidState.CONV        -> { statusTxt.text = "Konversation aktiv";        orbBtn.alpha = 1f }
            VoidService.VoidState.ERROR       -> { statusTxt.text = "Fehler — erneut versuchen"; orbBtn.alpha = 1f }
        }
        convBtn.text = if (state == VoidService.VoidState.CONV) "Konversation beenden" else "Konversation"
    }

    private fun requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
    }

    override fun onDestroy() { super.onDestroy(); if (bound) unbindService(conn) }
}
