package com.voidapp

import android.app.*
import android.content.*
import android.media.*
import android.media.session.MediaSession
import android.os.*
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.*
import java.util.UUID

class VoidService : Service() {

    enum class VoidState { IDLE, RECORDING, PROCESSING, SPEAKING, CONV, ERROR }

    inner class LocalBinder : Binder() { fun getService() = this@VoidService }

    private val binder = LocalBinder()
    var onStateChange: ((VoidState) -> Unit)? = null
    var backendUrl = "https://your-app.railway.app"

    private var state = VoidState.IDLE
    private var convMode = false
    private val sessionId = UUID.randomUUID().toString()

    private var recorder: AudioRecord? = null
    private var recordThread: Thread? = null
    private val recordBuf = ByteArrayOutputStream()
    private var isRecording = false

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var mediaSession: MediaSession? = null
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val CHANNEL_ID = "void_service"
    private val NOTIF_ID   = 1

    override fun onCreate() {
        super.onCreate()
        createNotifChannel()
        startForeground(NOTIF_ID, buildNotif("Void ist aktiv — Kopfhörer-Taste drücken"))
        loadPrefs()
        setupMediaSession()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.GERMAN
                ttsReady = true
            }
        }
    }

    private fun loadPrefs() {
        backendUrl = getSharedPreferences("void", MODE_PRIVATE)
            .getString("backend_url", backendUrl) ?: backendUrl
    }

    // ── MediaSession: intercepts headphone Play/Pause button ──────────────────
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "VoidSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay()  { toggleRecording() }
                override fun onPause() { toggleRecording() }
                override fun onMediaButtonEvent(intent: Intent): Boolean {
                    val ke = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (ke?.action == KeyEvent.ACTION_DOWN) toggleRecording()
                    return true
                }
            })
            isActive = true
        }
    }

    fun toggleRecording() {
        if (isRecording) stopRecording() else startRecording()
    }

    fun toggleConversation() {
        convMode = !convMode
        setState(if (convMode) VoidState.CONV else VoidState.IDLE)
        updateNotif(if (convMode) "Konversationsmodus aktiv" else "Void ist aktiv")
    }

    // ── Recording ─────────────────────────────────────────────────────────────
    private fun startRecording() {
        val sr        = 16000
        val bufSize   = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        recorder      = AudioRecord(MediaRecorder.AudioSource.MIC, sr, AudioFormat.CHANNEL_IN_MONO,
                                    AudioFormat.ENCODING_PCM_16BIT, bufSize)
        recorder?.startRecording()
        isRecording   = true
        recordBuf.reset()
        setState(VoidState.RECORDING)
        updateNotif("Aufnahme läuft… (nochmal drücken zum Stoppen)")

        recordThread = Thread {
            val buf = ByteArray(bufSize)
            // Write WAV header placeholder (filled after)
            recordBuf.write(ByteArray(44))
            while (isRecording) {
                val read = recorder?.read(buf, 0, bufSize) ?: -1
                if (read > 0) recordBuf.write(buf, 0, read)
            }
        }.also { it.start() }
    }

    private fun stopRecording() {
        isRecording = false
        recorder?.stop(); recorder?.release(); recorder = null
        recordThread?.join(2000)
        setState(VoidState.PROCESSING)
        updateNotif("Verarbeite…")

        val raw = recordBuf.toByteArray()
        val wav = addWavHeader(raw.copyOfRange(44, raw.size), 16000)

        Thread { sendToBackend(wav) }.start()
    }

    // ── WAV header ────────────────────────────────────────────────────────────
    private fun addWavHeader(pcm: ByteArray, sr: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val dl  = pcm.size
        val bw = ByteWriter(out)
        bw.str("RIFF"); bw.int32le(36 + dl); bw.str("WAVE")
        bw.str("fmt "); bw.int32le(16); bw.int16le(1); bw.int16le(1)
        bw.int32le(sr); bw.int32le(sr * 2); bw.int16le(2); bw.int16le(16)
        bw.str("data"); bw.int32le(dl)
        out.write(pcm)
        return out.toByteArray()
    }

    // ── API call ──────────────────────────────────────────────────────────────
    private fun sendToBackend(wav: ByteArray) {
        try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("audio", "audio.wav",
                    wav.toRequestBody("audio/wav".toMediaType()))
                .addFormDataPart("session_id", sessionId)
                .addFormDataPart("conv_mode",  if (convMode) "true" else "false")
                .addFormDataPart("preview",    "false")
                .build()

            val req  = Request.Builder().url("$backendUrl/api/voice").post(body).build()
            val resp = http.newCall(req).execute()

            if (!resp.isSuccessful) {
                setState(VoidState.ERROR); updateNotif("Fehler vom Server"); return
            }

            val json  = JSONObject(resp.body!!.string())
            val b64   = json.optString("audio", "")
            val text  = json.optString("response", "")
            val extra = json.optJSONObject("extra")

            // Handle backend-side state changes
            extra?.let {
                if (it.optBoolean("toggle_conv", false)) {
                    convMode = !convMode
                    updateNotif(if (convMode) "Konversationsmodus aktiv" else "Void ist aktiv")
                }
                if (it.has("preview")) { /* preview state tracked by backend */ }
            }

            if (b64.isNotEmpty()) {
                val mp3 = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                playMp3(mp3)
            } else if (text.isNotEmpty()) {
                speakTTS(text)
            } else {
                afterSpeak()
            }
        } catch (e: Exception) {
            Log.e("Void", "API error", e)
            setState(VoidState.ERROR)
            updateNotif("Verbindungsfehler")
        }
    }

    // ── Audio playback ────────────────────────────────────────────────────────
    private fun playMp3(bytes: ByteArray) {
        setState(VoidState.SPEAKING)
        updateNotif("Void spricht…")
        try {
            val tmp = File.createTempFile("void_resp", ".mp3", cacheDir)
            tmp.writeBytes(bytes)
            val mp = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(tmp.absolutePath)
                prepare()
                start()
                setOnCompletionListener {
                    it.release(); tmp.delete()
                    afterSpeak()
                }
            }
        } catch (e: Exception) {
            setState(VoidState.ERROR)
        }
    }

    private fun afterSpeak() {
        if (convMode) {
            Handler(Looper.getMainLooper()).postDelayed({ startRecording() }, 600L)
        } else {
            setState(VoidState.IDLE)
            updateNotif("Void ist aktiv — Kopfhörer-Taste drücken")
        }
    }

    private fun speakTTS(text: String) {
        setState(VoidState.SPEAKING)
        updateNotif("Void spricht…")
        if (ttsReady && tts != null) {
            tts!!.speak(text, TextToSpeech.QUEUE_FLUSH, null, "void_tts")
            Handler(Looper.getMainLooper()).postDelayed({
                afterSpeak()
            }, (text.length * 60L).coerceAtLeast(1500L))
        } else {
            afterSpeak()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private fun setState(s: VoidState) {
        state = s
        onStateChange?.invoke(s)
    }

    private fun createNotifChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Void", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Void Hintergrunddienst"
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification {
        val openIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

        // Notification button: tap to toggle recording
        val toggleIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(this, NotifButtonReceiver::class.java).apply { action = "TOGGLE" },
            PendingIntent.FLAG_IMMUTABLE
        )
        val btnLabel = if (isRecording) "⏹ Stopp" else "🎙 Sprechen"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Void")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_btn_speak_now, btnLabel, toggleIntent)
            .build()
    }

    private fun updateNotif(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotif(text))
    }

    override fun onBind(i: Intent): IBinder = binder
    override fun onStartCommand(i: Intent?, f: Int, id: Int) = START_STICKY
    override fun onDestroy() { mediaSession?.release(); tts?.shutdown(); http.dispatcher.executorService.shutdown() }
}

// Minimal binary writer helper
private class ByteWriter(val out: ByteArrayOutputStream) {
    fun str(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
    fun int32le(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF); out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF) }
    fun int16le(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
}
