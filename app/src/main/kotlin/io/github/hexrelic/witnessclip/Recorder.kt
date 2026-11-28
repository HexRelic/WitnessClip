// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.location.Location
import android.location.LocationManager
import android.media.MediaRecorder
import android.os.Looper
import java.util.Locale
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.Log
import evidence.core.Entry
import evidence.core.Checkpoint
import evidence.core.FLAG_CLIPPED
import evidence.core.MidAnchor
import evidence.core.unhex
import evidence.core.FLAG_SILENT
import evidence.core.pcmBytes
import evidence.core.J
import evidence.core.OpenAnchor
import evidence.core.Route
import evidence.core.Sealer
import evidence.core.Tsa
import evidence.core.Wire
import evidence.core.b64
import evidence.core.flac.FlacEncoder
import evidence.core.hex
import evidence.core.sha256
import evidence.core.unb64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/** Seconds between independent timestamps taken while a recording is running. */
const val MID_INTERVAL = 300

enum class Phase { IDLE, RECORDING, SEALING }

/** Live state the UI observes. */
object RecState {
    val phase = MutableStateFlow(Phase.IDLE)
    val seconds = MutableStateFlow(0)
    val startedAt = MutableStateFlow(0L)
    val levels = MutableStateFlow(FloatArray(72))
    val marks = MutableStateFlow(0)
    val mic = MutableStateFlow("")
    val builtIn = MutableStateFlow(true)
    /** null = still asking, true = opening timestamp obtained, false = offline. */
    val anchored = MutableStateFlow<Boolean?>(null)
    val openTime = MutableStateFlow(0L)
    val silent = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    /** Set when a recording has just been sealed, so the UI can open it. */
    val finishedId = MutableStateFlow<String?>(null)
    @Volatile var engine: Engine? = null
}

/** An input the user can choose. key is stable across reconnects. */
class MicOption(val key: String, val type: Int, val product: String, val builtIn: Boolean)

object Mics {
    private val types = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, BLE_HEADSET,
    )
    /** AudioDeviceInfo.TYPE_BLE_HEADSET, a constant that only exists from Android 12. */
    const val BLE_HEADSET = 26
    fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type == BLE_HEADSET

    fun typeName(type: Int) = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "built-in microphone"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, BLE_HEADSET -> "Bluetooth headset"
        else -> "type $type"
    }

    fun devices(ctx: Context): List<AudioDeviceInfo> =
        ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in types }

    /** The phone microphone first, then whatever is attached. */
    fun options(ctx: Context): List<MicOption> = devices(ctx)
        .map { MicOption(if (it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) "" else "${it.type}:${it.productName}", it.type, it.productName.toString(), it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) }
        .distinctBy { it.key }.sortedBy { if (it.builtIn) 0 else 1 }

    fun find(ctx: Context, key: String): AudioDeviceInfo? = devices(ctx).firstOrNull {
        if (key.isEmpty()) it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC else "${it.type}:${it.productName}" == key
    }
}

/**
 * One recording from start to sealed file. Audio is hashed into the sealed log one second at a
 * time, written to disk at once, and signed in the background every ten seconds.
 */
class Engine(private val app: App, private val service: RecorderService) {
    private val sid = ByteArray(16).also { SecureRandom().nextBytes(it) }
    val id = sid.hex()
    private val dir = app.store.dir(id).apply { mkdirs() }
    private val sealer = Sealer(sid)
    private val lock = Any()
    private val routes = ArrayList<Route>()
    private val journalFd = FileOutputStream(File(dir, "journal.jsonl"))
    private val journal = BufferedWriter(journalFd.writer())
    private val framesFile = File(dir, "frames.bin")
    private val framesFd = FileOutputStream(framesFile)
    private val framesOut = BufferedOutputStream(framesFd, 1 shl 16)
    private lateinit var enc: FlacEncoder
    private val signing = Executors.newSingleThreadExecutor()
    private val timestamping = Executors.newSingleThreadExecutor()
    private val mids = ArrayList<MidAnchor>()
    private var channels = 1
    private var bluetooth = false
    private var record: AudioRecord? = null
    private var source = "unprocessed"
    /**
     * The raw (unprocessed) microphone signal is very quiet by design: Android specifies about
     * -36 dBFS for a 94 dB SPL tone, which puts normal speech near -65 dBFS. We capture in
     * floating point, apply one constant, declared gain and store 24 bits, so nothing is lost
     * below about 100 dB SPL and the file is audible on any player. No automatic gain control.
     */
    private var gainDb = 30
    private var floatInput = true
    @Volatile private var running = false
    @Volatile private var signer: KeystoreSigner? = null
    @Volatile private var open: OpenAnchor? = null
    @Volatile private var totalFrames = 0L
    private var capture: Thread? = null
    private var keys: Thread? = null
    private val peaks = java.io.ByteArrayOutputStream()
    private var marks = 0
    private var notes = 0
    private val started = System.currentTimeMillis()
    private var lowSpace = false

    private fun write(line: JLine) { journal.write(J.encodeToString(line)); journal.newLine(); journal.flush() }

    private fun event(json: String) = synchronized(lock) {
        // A late background event (a slow location fix, for example) must never land after "stop".
        if (!logClosed) write(JLine(e = sealer.addEvent(json, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())))
    }
    private var logClosed = false

    private var unprocessed = true

    /** Routes audio to the chosen Bluetooth headset. Bluetooth voice links are narrow-band and processed by the headset. */
    @Suppress("DEPRECATION")
    private fun bluetoothRoute(on: Boolean) {
        val am = app.getSystemService(AudioManager::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) am.availableCommunicationDevices.firstOrNull { Mics.isBluetooth(it.type) }?.let { am.setCommunicationDevice(it) }
                else am.clearCommunicationDevice()
            } else {
                am.mode = if (on) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
                if (on) { am.startBluetoothSco(); am.isBluetoothScoOn = true } else { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
            }
        } catch (e: Exception) { Log.w("Engine", "bluetooth routing failed: $e") }
    }

    /** Opens the microphone, preferring floating-point samples. Null if it cannot be opened right now. */
    @Suppress("MissingPermission")
    private fun openRecord(): AudioRecord? {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val audioSource = when {
            bluetooth -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
        fun open(encoding: Int, bytesPerSample: Int): AudioRecord? {
            val min = AudioRecord.getMinBufferSize(RATE, mask, encoding)
            if (min <= 0) return null
            val r = try {
                AudioRecord(audioSource, RATE, mask, encoding, maxOf(min, RATE * channels * bytesPerSample * 4)) // four seconds of slack
            } catch (e: Exception) { null }
            if (r != null && r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return null }
            if (r != null && r.channelCount != channels) { r.release(); return null }
            return r
        }
        // The sample format is fixed by the first successful open so the whole file is uniform.
        val rec = (if (floatInput) open(AudioFormat.ENCODING_PCM_FLOAT, 4) else null)
            ?: run { if (totalFrames == 0L) floatInput = false; if (!floatInput) open(AudioFormat.ENCODING_PCM_16BIT, 2) else null }
            ?: return null
        try {
            Mics.find(app, app.prefs.mic)?.let { rec.preferredDevice = it }
            rec.startRecording()
        } catch (e: Exception) { rec.release(); return null }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) { rec.release(); return null }
        return rec
    }

    fun start(): Boolean {
        val am = app.getSystemService(AudioManager::class.java)
        bluetooth = Mics.find(app, app.prefs.mic)?.let { Mics.isBluetooth(it.type) } == true
        unprocessed = !bluetooth && am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        source = when { bluetooth -> "voice-communication (bluetooth)"; unprocessed -> "unprocessed"; else -> "voice-recognition" }
        // The voice-recognition source is about 18 dB hotter than the unprocessed one; a
        // Bluetooth headset applies its own level control, so nothing is added there.
        gainDb = when { bluetooth -> 0; unprocessed -> 30; else -> 12 }
        if (bluetooth) bluetoothRoute(true)
        channels = if (app.prefs.stereo && !bluetooth) 2 else 1
        var rec = openRecord()
        if (rec == null && channels == 2) { channels = 1; floatInput = true; rec = openRecord() }   // phone has no stereo input
        if (rec == null) { if (bluetooth) bluetoothRoute(false); cleanup(); return false }
        enc = FlacEncoder(framesOut, RATE, channels, BITS)
        record = rec
        running = true

        try {
            app.store.save(Meta(id, started, "recording"))
            write(JLine(h = JHeader(id, started, source, gainDb, channels)))
            event("""{"type":"start","source":"$source","gainDb":$gainDb,"channels":$channels,"input":"${if (floatInput) "float" else "pcm16"}"}""")
        } catch (e: Exception) {
            running = false
            rec.release(); cleanup(); app.store.delete(id)
            return false
        }

        RecState.seconds.value = 0; RecState.marks.value = 0; RecState.levels.value = FloatArray(72)
        RecState.anchored.value = null; RecState.openTime.value = 0; RecState.silent.value = false; RecState.error.value = null
        RecState.mic.value = ""; RecState.builtIn.value = true
        RecState.startedAt.value = SystemClock.elapsedRealtime()
        RecState.phase.value = Phase.RECORDING

        keys = thread(name = "keys") { prepareKey() }
        capture = thread(name = "capture", priority = Thread.MAX_PRIORITY) { captureLoop() }
        if (app.prefs.location) startLocation()
        return true
    }

    private var locListener: android.location.LocationListener? = null
    @Volatile private var lastFix: Location? = null
    @Volatile private var startLogged = false

    /**
     * Optional context: where the phone says it is. Declared by the device, not certified by
     * anyone, and recorded only if the user switched it on and granted the permission.
     *
     * The position is followed for the whole recording (the service holds the location
     * foreground type, so this keeps working with the screen locked). The first fix is sealed
     * as the start position, the latest one as the end position. If the phone never gives a
     * position, that is sealed too, so the report can say so instead of staying silent.
     */
    @Suppress("MissingPermission")
    private fun startLocation() {
        try {
            val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
            if (!fine && !coarse) { event("""{"type":"location-unavailable","pos":0,"reason":"permission"}"""); return }
            val lm = app.getSystemService(LocationManager::class.java)
            val providers = lm.getProviders(true).filter { it == LocationManager.NETWORK_PROVIDER || it == "fused" || (fine && it == LocationManager.GPS_PROVIDER) }
            if (providers.isEmpty()) { event("""{"type":"location-unavailable","pos":0,"reason":"location-off"}"""); return }
            // A very recent known position is a fair start value while a fresh fix is on its way.
            providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
                .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }.maxByOrNull { it.time }?.let { onFix(it) }
            val l = object : android.location.LocationListener {
                override fun onLocationChanged(location: Location) = onFix(location)
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            }
            for (p in providers) lm.requestLocationUpdates(p, 30_000L, 0f, l, Looper.getMainLooper())
            locListener = l
        } catch (e: Exception) { Log.w("Engine", "location unavailable: $e") }
    }

    private fun onFix(l: Location) {
        if (!running) return
        // Keep the newest fix, unless it is much less precise than one taken within the last minute.
        val prev = lastFix
        if (prev == null || l.time - prev.time > 60_000 || (if (l.hasAccuracy()) l.accuracy else 1e6f) <= (if (prev.hasAccuracy()) prev.accuracy else 1e6f)) lastFix = l
        if (!startLogged) { startLogged = true; logLocation(l, "start") }
    }

    private fun logLocation(l: Location, at: String) = event(
        """{"type":"location","at":"$at","pos":$totalFrames,"lat":${"%.6f".format(Locale.ROOT, l.latitude)},"lon":${"%.6f".format(Locale.ROOT, l.longitude)},"acc":${if (l.hasAccuracy()) l.accuracy.toInt() else -1},"provider":${JsonPrimitive(l.provider ?: "")},"fixTime":${l.time}}""")

    /** Stops following the position and seals the last one. Called while the log is still open. */
    private fun stopLocation() {
        val l = locListener ?: return
        try { app.getSystemService(LocationManager::class.java).removeUpdates(l) } catch (_: Exception) {}
        locListener = null
        val fix = lastFix
        if (fix != null) logLocation(fix, "end")
        else event("""{"type":"location-unavailable","pos":$totalFrames,"reason":"no-fix"}""")
    }

    private fun cleanup() { try { journal.close(); framesOut.close() } catch (_: Exception) {}; dir.deleteRecursively() }

    private val keyLock = Any()
    private var keyAbandoned = false

    /** Opening timestamp first, then a hardware key whose attestation commits to it. */
    private fun prepareKey() {
        var anchor: OpenAnchor? = null
        var time = 0L
        if (Finisher.online(app)) {
            val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
            for (p in Tsa.DEFAULTS) {
                if (!running) break
                try {
                    val token = Tsa.request(p.url, sha256(nonce), 4000)
                    time = Tsa.inspect(token, sha256(nonce)).time.time
                    anchor = OpenAnchor(p.id, nonce.hex(), token.b64())
                    break
                } catch (e: Exception) { Log.w("Engine", "opening timestamp from ${p.id} failed: $e") }
            }
        }
        // The timestamp and the key that commits to it are published together or not at all,
        // so stop() can never pair a key with a different timestamp.
        synchronized(keyLock) {
            if (keyAbandoned) return
            try {
                Keys.device()
                val s = Keys.create(Keys.sessionAlias(id), Wire.sessionChallenge(sid, anchor?.token?.unb64()))
                if (anchor != null) {
                    synchronized(lock) { write(JLine(o = anchor)) }; open = anchor; RecState.openTime.value = time
                    // Declares how much audio already existed when the certified start arrived.
                    event("""{"type":"anchored","pos":$totalFrames}""")
                }
                signer = s
            } catch (e: Exception) { Log.e("Engine", "key creation failed", e) }
        }
        RecState.anchored.value = open != null
    }

    private fun routeIndex(rec: AudioRecord): Int {
        val d = rec.routedDevice
        val r = if (d == null) Route(0, "unknown", "", false)
        else Route(d.type, Mics.typeName(d.type), d.productName.toString(), d.type == AudioDeviceInfo.TYPE_BUILTIN_MIC)
        val i = routes.indexOf(r)
        if (i >= 0) return i
        routes.add(r)
        synchronized(lock) { write(JLine(r = r)) }
        if (routes.size > 1) event("""{"type":"route","pos":$totalFrames,"to":${routes.size - 1}}""")
        RecState.mic.value = if (r.builtIn) "" else r.product.ifEmpty { r.name }
        RecState.builtIn.value = r.builtIn
        return routes.size - 1
    }

    private fun captureLoop() {
        var rec = record ?: return
        val ch = channels
        val chunk = FloatArray(RATE / 10 * ch)
        val shorts = ShortArray(if (floatInput) 0 else chunk.size)
        val leaf = IntArray(RATE * ch)
        val bytes = ByteArray(RATE * ch * BITS / 8)
        val scale = Math.pow(10.0, gainDb / 20.0).toFloat() * FULL_SCALE
        var clipped = false
        val ts = AudioTimestamp()
        var fill = 0          // samples (not frames) waiting in leaf
        var leaves = 0
        var wasSilent = false
        // Frames delivered by earlier AudioRecord instances, so hardware timestamps stay comparable after a reopen.
        var framesBeforeThisRecord = 0L

        fun emit(frames: Int) {
            var peak = 0
            for (i in 0 until frames * ch) { val a = abs(leaf[i]); if (a > peak) peak = a }
            val byteCount = pcmBytes(leaf, frames * ch, BITS, bytes)
            // Exactly-zero audio for a whole second means the system took the microphone away.
            val silent = peak == 0 && frames == RATE
            val end = totalFrames + frames
            // Prefer the audio hardware's own clock for "when was the last sample of this second
            // captured"; fall back to the system clock if the driver gives nothing plausible.
            val now = SystemClock.elapsedRealtimeNanos()
            var en = now
            try {
                if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                    val hw = ts.nanoTime + (end - framesBeforeThisRecord - ts.framePosition) * 1_000_000_000L / RATE
                    if (abs(hw - now) < 3_000_000_000L) en = hw
                }
            } catch (_: Exception) {}
            val route = routeIndex(rec)
            if (silent != wasSilent) {
                event("""{"type":"${if (silent) "gap" else "resumed"}","pos":$totalFrames}""")
                RecState.silent.value = silent
                if (silent) service.alertSilence()
                wasSilent = silent
            }
            synchronized(lock) { write(JLine(e = sealer.addAudio(bytes, byteCount, frames, en, System.currentTimeMillis(), (if (silent) FLAG_SILENT else 0) or (if (clipped) FLAG_CLIPPED else 0), route))) }
            clipped = false
            enc.write(leaf, frames)
            framesOut.flush()
            totalFrames = end
            leaves++
            peaks.write(((peak.toLong() * 255 / FULL_SCALE).toInt()))
            RecState.seconds.value = leaves
            // Recovery keeps only the seconds found in both files, so both reach the disk
            // together: a power cut loses at most the last five seconds.
            if (leaves % 5 == 0) try { framesFd.fd.sync(); journalFd.fd.sync() } catch (_: Exception) {}
            // Every ten seconds the key signs everything so far; every five minutes that
            // signed state is also sent for an independent timestamp, so a long recording is
            // certified along the way and not only at its two ends.
            val stampNow = leaves % MID_INTERVAL == 0
            if (leaves % 10 == 0) signer?.let { s -> try { signing.execute { val cp = checkpoint(s); if (stampNow && cp != null) try { timestamping.execute { midTimestamp(cp) } } catch (_: Exception) {} } } catch (_: Exception) {} }
            if (leaves % 30 == 0) checkSpace()
        }

        try {
            while (running) {
                val n = try {
                    if (floatInput) rec.read(chunk, 0, chunk.size, AudioRecord.READ_BLOCKING)
                    else rec.read(shorts, 0, shorts.size).also { got -> for (i in 0 until got) chunk[i] = shorts[i] / 32768f }
                } catch (e: Exception) { AudioRecord.ERROR }
                if (!running) break
                if (n < 0) {
                    // The audio input died (audio server restart, device unplugged...). The
                    // session stays open: declare the gap, alert the user and keep trying to
                    // get the microphone back until it works or the user stops.
                    Log.w("Engine", "audio input error $n, reopening")
                    fill -= fill % ch
                    event("""{"type":"gap","pos":${totalFrames + fill / ch},"reason":"input-error"}""")
                    RecState.silent.value = true
                    service.alertSilence()
                    try { rec.release() } catch (_: Exception) {}
                    var again: AudioRecord? = null
                    while (running && again == null) { Thread.sleep(1000); again = openRecord() }
                    if (again == null) break
                    rec = again; record = again
                    framesBeforeThisRecord = totalFrames + fill / ch
                    event("""{"type":"resumed","pos":${totalFrames + fill / ch}}""")
                    RecState.silent.value = false
                    continue
                }
                if (n == 0) continue
                var peak = 0
                for (i in 0 until n) {
                    var v = Math.round(chunk[i] * scale)
                    if (v >= FULL_SCALE) { v = FULL_SCALE - 1; clipped = true } else if (v < -FULL_SCALE) { v = -FULL_SCALE; clipped = true }
                    leaf[fill++] = v
                    val a = abs(v); if (a > peak) peak = a
                    if (fill == leaf.size) { emit(RATE); fill = 0 }
                }
                val lv = RecState.levels.value
                // square-root scale so ordinary speech is clearly visible on the live waveform
                RecState.levels.value = FloatArray(lv.size).also { System.arraycopy(lv, 1, it, 0, lv.size - 1); it[lv.size - 1] = kotlin.math.sqrt(peak.toFloat() / FULL_SCALE) }
            }
            if (fill >= ch) emit(fill / ch)
        } catch (e: Exception) {
            // Typically the disk is full or failing. Nothing more can be captured safely, so
            // seal what is already on disk instead of pretending to record.
            Log.e("Engine", "capture failed", e)
            service.alertFailure()
            if (running) thread { stop("capture-error") }
        }
    }

    /** Asks an authority to timestamp a signed checkpoint. Skipped silently when offline. */
    private fun midTimestamp(cp: Checkpoint) {
        if (!running || !Finisher.online(app)) return
        val digest = sha256(Wire.checkpoint(sid, cp.size, cp.root.unhex(), cp.en, cp.w))
        // Start from a different authority each time to spread the requests.
        val order = Tsa.DEFAULTS.indices.map { Tsa.DEFAULTS[(it + mids.size) % Tsa.DEFAULTS.size] }
        for (p in order) {
            try {
                val anchor = MidAnchor(cp.size, p.id, Tsa.request(p.url, digest, 5000).b64())
                synchronized(lock) { if (!midsClosed) { mids.add(anchor); write(JLine(m = anchor)) } }
                return
            } catch (e: Exception) { Log.w("Engine", "intermediate timestamp from ${p.id} failed: $e") }
        }
    }
    private var midsClosed = false

    private fun checkpoint(s: KeystoreSigner): Checkpoint? = try {
        val cp = sealer.checkpoint(s, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
        synchronized(lock) { write(JLine(c = cp)) }
        cp
    } catch (e: Exception) { Log.e("Engine", "checkpoint failed", e); null }

    private fun checkSpace() {
        val free = StatFs(dir.path).availableBytes
        if (free < 300L * 1024 * 1024 && !lowSpace) { lowSpace = true; service.alertSpace() }
        // Out of room: close cleanly now rather than lose the file when the disk fills.
        if (free < 30L * 1024 * 1024) thread { stop("storage-full") }
    }

    fun mark() {
        if (!running) return
        event("""{"type":"mark","pos":$totalFrames}""")
        marks++; RecState.marks.value = marks
    }

    fun note(text: String) {
        if (!running || text.isBlank()) return
        event("""{"type":"note","pos":$totalFrames,"text":${JsonPrimitive(text.trim().take(2000))}}""")
        notes++
    }

    /** Ends the recording and seals it. Blocks; call from a worker thread. */
    @Synchronized
    fun stop(reason: String = "user") {
        if (!running) return
        running = false
        RecState.phase.value = Phase.SEALING
        try {
            if (Thread.currentThread() !== capture) capture?.join(3000)
            record?.run { try { stop() } catch (_: Exception) {}; try { release() } catch (_: Exception) {} }
            // Releasing the input unblocks a stuck read; the capture thread must be gone
            // before anything else touches the encoder or the log.
            if (Thread.currentThread() !== capture) capture?.join(10000)
            keys?.join(20000)
            signing.shutdown(); signing.awaitTermination(20, TimeUnit.SECONDS)
            timestamping.shutdown(); timestamping.awaitTermination(8, TimeUnit.SECONDS)
            val midList = synchronized(lock) { midsClosed = true; mids.toList() }
            if (bluetooth) bluetoothRoute(false)
            stopLocation()
            event("""{"type":"stop","pos":$totalFrames}""")
            synchronized(lock) { logClosed = true }
            val s = synchronized(keyLock) {
                keyAbandoned = true
                signer ?: Keys.create(Keys.sessionAlias(id), Wire.sessionChallenge(sid, open?.token?.unb64())).also { signer = it }
            }
            checkpoint(s)
            val info = enc.finish()
            framesOut.close(); journal.close()
            Finisher.finish(app, id, sealer, routes.toList(), open, s, info, framesFile, source, gainDb, reason, midList)
            val r = routes.firstOrNull()
            app.store.save(Meta(
                id, started, "pending", totalFrames * 1000 / RATE, app.store.flac(id).length(), marks, notes,
                r?.product?.ifEmpty { r.name } ?: "", routes.all { it.builtIn }, Base64.encodeToString(peaks.toByteArray(), Base64.NO_WRAP),
                RecState.openTime.value, 0, reason, s.level, channels = channels,
            ))
            // The recording key has signed everything it ever will. Destroying it now means
            // nothing can be signed in this recording's name later, not even by this phone.
            Keys.delete(Keys.sessionAlias(id))
            RecState.finishedId.value = id
            app.work.execute { Finisher.anchor(app, id) }
        } catch (e: Exception) {
            Log.e("Engine", "sealing failed", e)
            RecState.error.value = "sealing failed: ${e.message}"
            // Audio and log are still on disk: seal them through the crash-recovery path.
            try { framesOut.close() } catch (_: Exception) {}
            try { journal.close() } catch (_: Exception) {}
            // Recovery skips the recording that is still being captured, so let go of it first.
            RecState.engine = null
            app.work.execute { Finisher.recoverAll(app) }
        } finally {
            RecState.engine = null
            RecState.phase.value = Phase.IDLE
            service.done()
        }
    }
}

/** Keeps the recording alive with the screen off: foreground service + wake lock. */
class RecorderService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private val nm get() = getSystemService(NotificationManager::class.java)
    private val s get() = Str.current(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        nm.createNotificationChannel(NotificationChannel("rec", s.chRecording, NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("alert", s.chAlerts, NotificationManager.IMPORTANCE_HIGH))
        val n = notification("rec", s.notifTitle, s.notifText, true)
        // Android requires this answer to every start request, including a repeated one.
        // With location switched on the service also declares the location type; without it
        // Android hides the position from the app as soon as the screen is locked.
        val app = application as App
        val wantsLocation = app.prefs.location && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= 30) {
            val mic = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            try { startForeground(1, n, if (wantsLocation) mic or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else mic) }
            catch (e: Exception) { startForeground(1, n, mic) }
        } else startForeground(1, n)
        if (RecState.engine != null) return START_NOT_STICKY
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { done(); return START_NOT_STICKY }
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "witnessclip:capture").apply { acquire() }
        val engine = Engine(application as App, this)
        RecState.engine = engine
        if (!engine.start()) {
            RecState.engine = null
            RecState.error.value = "microphone unavailable"
            done()
        }
        // If the system kills us it cannot hand the microphone back in the background, so we do
        // not ask to be restarted; the next launch seals what was captured.
        return START_NOT_STICKY
    }

    private fun notification(channel: String, title: String, text: String, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channel).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(text)
            .setOngoing(ongoing).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE)
            .setStyle(Notification.BigTextStyle().bigText(text)).build()
    }

    private fun buzz() {
        try { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1)) } catch (_: Exception) {}
    }

    fun alertSilence() { buzz(); nm.notify(2, notification("alert", s.alertSilentTitle, s.alertSilentText, false)) }
    fun alertFailure() { buzz(); nm.notify(4, notification("alert", s.alertFailTitle, s.alertFailText, false)) }
    fun alertSpace() { buzz(); nm.notify(3, notification("alert", s.alertSpaceTitle, s.alertSpaceText, false)) }

    fun done() {
        wake?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, RecorderService::class.java))
    }
}
