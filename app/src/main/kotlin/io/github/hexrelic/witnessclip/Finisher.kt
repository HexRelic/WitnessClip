// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Base64
import android.util.Log
import evidence.core.AppInfo
import evidence.core.Attestation
import evidence.core.AudioInfo
import evidence.core.Bundle
import evidence.core.Checkpoint
import evidence.core.DeviceInfo
import evidence.core.Entry
import evidence.core.Exporter
import evidence.core.J
import evidence.core.KeyInfo
import evidence.core.Manifest
import evidence.core.MidAnchor
import evidence.core.OpenAnchor
import evidence.core.PcmReader
import evidence.core.Route
import evidence.core.Sealer
import evidence.core.Token
import evidence.core.Tsa
import evidence.core.Wire
import evidence.core.b64
import evidence.core.flac.FlacDecoder
import evidence.core.flac.FlacEncoder
import evidence.core.flac.FlacFile
import evidence.core.flac.StreamInfo
import evidence.core.hex
import evidence.core.pcmBytes
import evidence.core.sha256
import evidence.core.unb64
import evidence.core.unhex
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.security.MessageDigest

const val RATE = 48000
const val BITS = 24
const val FULL_SCALE = 8_388_608

/** First line of the on-disk journal: what is needed to finish a recording after a crash. */
@Serializable data class JHeader(val sid: String, val started: Long, val source: String, val gainDb: Int = 0, val channels: Int = 1)

/** One journal line. Exactly one field is set. */
@Serializable
data class JLine(val h: JHeader? = null, val e: Entry? = null, val c: Checkpoint? = null, val r: Route? = null, val o: OpenAnchor? = null, val m: MidAnchor? = null)

/** Turns captured audio + sealed log into the final self-proving .flac, and adds timestamps. */
object Finisher {
    private const val TAG = "Finisher"

    fun online(app: App): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Builds and signs the manifest and writes recording.flac. [frames] is the bare FLAC frame file. */
    fun finish(
        app: App, id: String, sealer: Sealer, routes: List<Route>, open: OpenAnchor?, signer: KeystoreSigner,
        info: StreamInfo, frames: File, source: String, gainDb: Int, endReason: String, mids: List<MidAnchor> = emptyList(),
    ) {
        val sid = id.unhex()
        val device = Keys.device()
        val sessionSpki = Attestation.parse(signer.chain.first()).publicKey.encoded
        val pkg = app.packageManager.getPackageInfo(app.packageName, 0)
        val manifest = Manifest(
            sessionId = id,
            app = AppInfo(app.packageName, pkg.versionName ?: "", pkg.longVersionCode),
            audio = AudioInfo(info.sampleRate, info.channels, info.bps, info.totalSamples, RATE, source, gainDb),
            device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.VERSION.SECURITY_PATCH),
            keys = KeyInfo(
                sha256(device.publicKey).hex(), device.chain.map { it.b64() }, signer.chain.map { it.b64() },
                device.sign(Wire.endorsement(sid, sessionSpki)).b64(), signer.level,
            ),
            open = open, routes = routes,
            entries = sealer.entries.toList(), checkpoints = sealer.checkpoints.toList(), mid = mids,
            root = sealer.root().hex(), endReason = endReason,
        )
        val bytes = J.encodeToString(manifest).toByteArray()
        val bundle = Bundle(bytes, signer.sign(bytes), emptyList())
        val dir = app.store.dir(id)
        val tmp = File(dir, "recording.flac.tmp")
        durable(tmp) { o -> frames.inputStream().use { FlacFile.write(o, info, bundle.encode(), it) } }
        if (!tmp.renameTo(app.store.flac(id))) throw java.io.IOException("cannot move sealed file into place")
        frames.delete()
        File(dir, "journal.jsonl").delete()
    }

    /** Writes a file and forces it onto the disk, so a power cut cannot leave a half-written file under the final name. */
    private fun durable(file: File, write: (java.io.OutputStream) -> Unit) {
        java.io.FileOutputStream(file).use { f ->
            val o = BufferedOutputStream(f, 1 shl 16)
            write(o); o.flush(); f.fd.sync()
        }
    }

    /**
     * A sealed file whose list entry was lost (the app died between writing the file and
     * recording that it had). The file is complete, so it is put back in the list as it is.
     */
    private fun adopt(app: App, id: String, old: Meta?): Boolean {
        val flac = app.store.flac(id)
        if (!flac.exists()) return false
        val bundle = try { Exporter.readBundle(flac) } catch (e: Exception) { return false }
        val m = bundle.manifest
        if (m.sessionId != id) return false
        val dir = app.store.dir(id)
        File(dir, "frames.bin").delete(); File(dir, "frames.clean").delete(); File(dir, "journal.jsonl").delete()
        Keys.delete(Keys.sessionAlias(id))
        val r = m.routes.firstOrNull()
        app.store.save(Meta(
            id, old?.started ?: m.entries.firstOrNull()?.w ?: flac.lastModified(), if (bundle.closeTokens.isEmpty()) "pending" else "sealed",
            m.audio.totalFrames * 1000 / m.audio.sampleRate, flac.length(),
            m.entries.count { it.j.startsWith("{\"type\":\"mark\"") }, m.entries.count { it.j.startsWith("{\"type\":\"note\"") },
            r?.product?.ifEmpty { r.name } ?: "", m.routes.all { it.builtIn }, old?.peaks ?: "",
            endReason = m.endReason, keyLevel = m.keys.level, title = old?.title ?: "", channels = m.audio.channels,
        ))
        return true
    }

    /** Requests closing timestamps for one recording and embeds them. Safe to call repeatedly. */
    fun anchor(app: App, id: String): Boolean {
        val meta = app.store.get(id) ?: return false
        if (meta.state != "pending" || !online(app)) return false
        val flac = app.store.flac(id)
        return try {
            val bundle = Exporter.readBundle(flac)
            val digest = sha256(bundle.manifestBytes)
            val openProvider = bundle.manifest.open?.provider
            // Prefer a different authority from the opening one, then take a second for redundancy.
            val order = Tsa.DEFAULTS.sortedBy { if (it.id == openProvider) 1 else 0 }
            val tokens = ArrayList<Token>()
            var time = 0L
            for (p in order) {
                if (tokens.size == 2) break
                try {
                    val t = Tsa.request(p.url, digest)
                    tokens.add(Token(p.id, t.b64()))
                    val at = Tsa.inspect(t, digest).time.time
                    if (time == 0L || at < time) time = at
                } catch (e: Exception) { Log.w(TAG, "timestamp from ${p.id} failed: $e") }
            }
            if (tokens.isEmpty()) return false
            val tmp = File(flac.path + ".tmp")
            BufferedInputStream(flac.inputStream(), 1 shl 16).use { i ->
                durable(tmp) { o -> FlacFile.replaceEvidence(i, o, bundle.with(tokens = tokens).encode()) }
            }
            if (!tmp.renameTo(flac)) return false
            // Read the entry again: the user may have renamed or deleted the recording meanwhile.
            val now = app.store.get(id) ?: return false
            app.store.save(now.copy(state = "sealed", closeTime = time, size = flac.length()))
            true
        } catch (e: Exception) { Log.e(TAG, "anchor $id", e); false }
    }

    fun anchorPending(app: App) {
        for (m in app.store.items.value) if (m.state == "pending") anchor(app, m.id)
    }

    /** Finishes recordings that were cut short by a crash, a kill or the battery dying. */
    fun recoverAll(app: App) {
        // Sealed files that have no list entry at all.
        for (id in app.store.orphans()) if (RecState.engine?.id != id) try { adopt(app, id, null) } catch (e: Exception) { Log.e(TAG, "adopt $id", e) }
        for (m in app.store.items.value) {
            if (m.state != "recording" || RecState.engine?.id == m.id) continue
            // Already sealed, only the list entry is behind: never rebuild or delete it.
            try { if (adopt(app, m.id, m)) continue } catch (e: Exception) { Log.e(TAG, "adopt ${m.id}", e) }
            try { recover(app, m) } catch (e: Exception) {
                Log.e(TAG, "recover ${m.id}", e)
                // Nothing usable was captured (killed in the first instant): remove the empty shell.
                val dir = app.store.dir(m.id)
                if (e is NothingRecorded || !File(dir, "journal.jsonl").exists() || !File(dir, "frames.bin").exists()) {
                    Keys.delete(Keys.sessionAlias(m.id)); app.store.delete(m.id)
                }
            }
        }
    }

    private class NothingRecorded : Exception("no audio captured")

    private fun recover(app: App, meta: Meta) {
        val dir = app.store.dir(meta.id)
        val lines = File(dir, "journal.jsonl").readLines().mapNotNull { try { J.decodeFromString(JLine.serializer(), it) } catch (e: Exception) { null } }
        val header = lines.firstNotNullOfOrNull { it.h } ?: throw NothingRecorded()
        val sid = header.sid.unhex()
        val ch = header.channels.coerceIn(1, 2)
        val entries = lines.mapNotNull { it.e }
        val routes = lines.mapNotNull { it.r }
        val open = lines.firstNotNullOfOrNull { it.o }

        // Keep every second whose audio is really on disk and matches its sealed hash.
        val frames = File(dir, "frames.bin")
        val clean = File(dir, "frames.clean")
        val sealer = Sealer(sid)
        val peaks = java.io.ByteArrayOutputStream()
        var total = 0L
        val info = BufferedOutputStream(clean.outputStream(), 1 shl 16).use { out ->
            val enc = FlacEncoder(out, RATE, ch, BITS)
            BufferedInputStream(frames.inputStream(), 1 shl 16).use { input ->
                val reader = PcmReader(FlacDecoder.frames(input, RATE, ch, BITS))
                val pcm = IntArray(RATE * ch)
                val bytes = ByteArray(RATE * ch * BITS / 8)
                val md = MessageDigest.getInstance("SHA-256")
                for (e in entries) {
                    if (e.k == "a") {
                        val got = try { reader.read(pcm, e.f) } catch (x: Exception) { -1 }
                        if (got != e.f) break
                        md.update(bytes, 0, pcmBytes(pcm, e.f * ch, BITS, bytes))
                        if (md.digest().hex() != e.h) break
                        enc.write(pcm, e.f)
                        total += e.f
                        var peak = 0
                        for (i in 0 until e.f * ch) { val a = kotlin.math.abs(pcm[i]); if (a > peak) peak = a }
                        peaks.write((peak.toLong() * 255 / FULL_SCALE).toInt())
                    }
                    sealer.add(e)
                }
            }
            enc.finish()
        }
        if (total == 0L) { clean.delete(); throw NothingRecorded() }
        // Live checkpoints were signed by the recording key. They are kept only if that same
        // key still exists; a replacement key could not vouch for them.
        val existing = Keys.load(Keys.sessionAlias(meta.id))
        if (existing != null) for (c in lines.mapNotNull { it.c }) if (c.size <= sealer.size()) sealer.restoreCheckpoint(c)
        val now = System.currentTimeMillis()
        val en = android.os.SystemClock.elapsedRealtimeNanos()
        sealer.addEvent("""{"type":"interrupted","pos":$total}""", en, now)
        val signer = existing ?: Keys.create(Keys.sessionAlias(meta.id), Wire.sessionChallenge(sid, open?.token?.unb64()))
        sealer.checkpoint(signer, en, now)
        // Intermediate timestamps cover signed checkpoints, so they survive only with them.
        val kept = sealer.checkpoints.map { it.size }.toSet()
        val mids = if (existing != null) lines.mapNotNull { it.m }.filter { it.size in kept } else emptyList()
        finish(app, meta.id, sealer, routes, open, signer, info, clean, header.source, header.gainDb, "interrupted", mids)
        frames.delete()
        Keys.delete(Keys.sessionAlias(meta.id))
        app.store.save(meta.copy(
            state = "pending", durationMs = total * 1000 / RATE, size = app.store.flac(meta.id).length(),
            peaks = Base64.encodeToString(peaks.toByteArray(), Base64.NO_WRAP), endReason = "interrupted", keyLevel = signer.level, channels = ch,
            marks = entries.count { it.j.startsWith("{\"type\":\"mark\"") }, notes = entries.count { it.j.startsWith("{\"type\":\"note\"") },
        ))
    }
}
