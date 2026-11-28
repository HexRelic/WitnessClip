// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import evidence.core.flac.FlacDecoder
import evidence.core.flac.FlacFile
import java.io.InputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.util.Date
import kotlin.math.abs

enum class Status { OK, WARN, FAIL, INFO }

/** id is stable (used by the UI for titles); detail is a technical English sentence. */
data class Check(val id: String, val status: Status, val detail: String)

class Report(
    val checks: List<Check>, val manifest: Manifest?, val part: PartInfo?,
    val openTime: Date?, val closeTime: Date?, val audioSeconds: Double, val entriesVerified: Int,
) {
    val failed get() = checks.any { it.status == Status.FAIL }
    /**
     * Checks meant for non-technical readers. Hardware attestation is listed only when it
     * positively passes or cryptographically fails: its absence proves nothing either way.
     */
    val shown get() = checks.filter { it.id != "attestation" || it.status == Status.OK || it.status == Status.FAIL }
    fun status(id: String) = checks.filter { it.id == id }.maxByOrNull { it.status.severity }?.status
    private val Status.severity get() = when (this) { Status.FAIL -> 3; Status.WARN -> 2; Status.OK -> 1; Status.INFO -> 0 }
}

/** Reads sample frames across FLAC block boundaries. */
class PcmReader(private val d: FlacDecoder) {
    private var block: IntArray? = null
    private var off = 0
    private var avail = 0
    val channels get() = d.channels

    /** Fills [dst] with up to [frames] frames; returns how many were available. */
    fun read(dst: IntArray, frames: Int): Int {
        var got = 0
        while (got < frames) {
            if (avail == 0) { block = d.readBlock() ?: break; off = 0; avail = d.blockFrames }
            val n = minOf(avail, frames - got)
            System.arraycopy(block!!, off * channels, dst, got * channels, n * channels)
            off += n; avail -= n; got += n
        }
        return got
    }
}

/** Little-endian PCM bytes, the form that is hashed. */
fun pcmBytes(src: IntArray, samples: Int, bps: Int, dst: ByteArray): Int {
    var p = 0
    for (i in 0 until samples) {
        val s = src[i]
        dst[p++] = s.toByte(); dst[p++] = (s shr 8).toByte()
        if (bps == 24) dst[p++] = (s shr 16).toByte()
    }
    return p
}

fun ecVerify(key: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
    Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(data); verify(sig) }
} catch (e: Exception) { false }

/**
 * Independent check of a sealed recording. Uses nothing but the file itself, the bundled
 * Google attestation roots and standard cryptography. No network.
 */
object Verifier {
    /** Never throws: a file that cannot be understood is reported as a failed check. */
    fun verify(flac: InputStream): Report = try { check(flac) } catch (e: Exception) {
        Report(listOf(Check("bundle", Status.FAIL, "Malformed evidence: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())), null, null, null, null, 0.0, 0)
    } catch (e: OutOfMemoryError) {
        // Everything allocated for this file is unreachable again here. A file that could
        // not be checked must never read as passing, so this is a failed check too.
        Report(listOf(Check("bundle", Status.FAIL, "File too large to check with the memory available.")), null, null, null, null, 0.0, 0)
    }

    private fun need(ok: Boolean, what: String) { if (!ok) throw IllegalArgumentException(what) }

    /** Rejects values an attacker could use to exhaust memory or index out of range. Runs before anything is allocated from them. */
    private fun sanity(m: Manifest, part: PartInfo?) {
        need(m.sessionId.length == 32, "bad session id")
        need(m.audio.sampleRate in 8000..192000 && m.audio.channels in 1..2 && (m.audio.bits == 16 || m.audio.bits == 24), "unsupported audio format")
        need(m.audio.leafFrames in 1..m.audio.sampleRate, "bad slice length")
        need(m.keys.sessionChain.isNotEmpty() && m.keys.deviceChain.isNotEmpty(), "missing certificates")
        need(m.keys.sessionChain.size <= 10 && m.keys.deviceChain.size <= 10, "certificate chain too long")
        need(m.mid.size <= 2000, "too many intermediate timestamps")
        var audio = 0
        for (e in m.entries) when (e.k) {
            "a" -> { need(e.f in 1..m.audio.leafFrames && e.h.length == 64, "bad audio entry"); audio++ }
            "e" -> need(e.j.length <= 16384, "event too large")
            else -> need(false, "unknown entry kind")
        }
        if (part != null) need(part.firstAudio >= 0 && part.audioEntries >= 0 && part.firstAudio.toLong() + part.audioEntries <= audio, "part range outside the recording")
    }

    private fun check(flac: InputStream): Report {
        val checks = ArrayList<Check>()
        fun add(id: String, s: Status, d: String) { checks.add(Check(id, s, d)) }

        val dec: FlacDecoder
        val bundle: Bundle
        val m: Manifest
        try {
            dec = FlacDecoder.open(flac)
            bundle = Bundle.decode(dec.application[FlacFile.APP_ID] ?: throw IllegalArgumentException("no evidence block in this file"))
            m = bundle.manifest
            if (m.format != FORMAT) throw IllegalArgumentException("unknown format ${m.format}")
            sanity(m, bundle.part)
        } catch (e: Exception) {
            add("bundle", Status.FAIL, "Cannot read evidence: ${e.message}")
            return Report(checks, null, null, null, null, 0.0, 0)
        }
        val sid = m.sessionId.unhex()
        val sessionCerts = m.keys.sessionChain.map { Attestation.parse(it.unb64()) }
        val deviceCerts = m.keys.deviceChain.map { Attestation.parse(it.unb64()) }
        val sessionKey = sessionCerts.first().publicKey

        // 1. manifest signature
        if (ecVerify(sessionKey, bundle.manifestBytes, bundle.signature)) add("signature", Status.OK, "Manifest signed by the recording key.")
        else add("signature", Status.FAIL, "Manifest signature is not valid.")

        // 2. sealed log: recompute every leaf, the root and every checkpoint
        val log = MerkleLog()
        var cpIndex = 0
        var cpBad = 0
        m.entries.forEachIndexed { i, e ->
            log.append(MerkleLog.leaf(Wire.entryBytes(sid, i, e)))
            while (cpIndex < m.checkpoints.size && m.checkpoints[cpIndex].size == log.size) {
                val cp = m.checkpoints[cpIndex++]
                val root = log.root()
                if (root.hex() != cp.root || !ecVerify(sessionKey, Wire.checkpoint(sid, cp.size, root, cp.en, cp.w), cp.sig.unb64())) cpBad++
            }
        }
        when {
            log.root().hex() != m.root -> add("log", Status.FAIL, "Merkle root does not match the entries.")
            cpBad > 0 || cpIndex != m.checkpoints.size -> add("log", Status.FAIL, "$cpBad of ${m.checkpoints.size} live checkpoints are invalid or out of order.")
            else -> add("log", Status.OK, "${m.entries.size} entries, ${m.checkpoints.size} live checkpoints, root ${m.root.take(16)}…")
        }

        // 3. audio: every slice must hash to its entry
        val audio = m.entries.filter { it.k == "a" }
        val part = bundle.part
        val range = if (part != null) audio.subList(part.firstAudio, part.firstAudio + part.audioEntries) else audio
        val si = dec.info!!
        val seconds = audio.sumOf { it.f.toLong() }.toDouble() / m.audio.sampleRate
        var verified = 0
        var frames = 0L
        try {
            if (si.sampleRate != m.audio.sampleRate || si.channels != m.audio.channels || si.bps != m.audio.bits) throw IllegalStateException("audio format differs from manifest")
            val reader = PcmReader(dec)
            val maxFrames = (range.maxOfOrNull { it.f } ?: 0).coerceAtLeast(1)
            val pcm = IntArray(maxFrames * si.channels)
            val bytes = ByteArray(maxFrames * si.channels * (si.bps / 8))
            val md = MessageDigest.getInstance("SHA-256")
            val md5 = MessageDigest.getInstance("MD5")   // FLAC's own whole-stream checksum
            var bad = -1
            for ((i, e) in range.withIndex()) {
                val got = reader.read(pcm, e.f)
                if (got != e.f) { bad = i; break }
                val len = pcmBytes(pcm, e.f * si.channels, si.bps, bytes)
                md.update(bytes, 0, len); md5.update(bytes, 0, len)
                if (md.digest().hex() != e.h) { bad = i; break }
                verified++; frames += e.f
            }
            val extra = reader.read(pcm, 1)
            when {
                bad >= 0 -> add("audio", Status.FAIL, "Audio differs from the seal at second ${bad + 1} of ${range.size}.")
                extra > 0 -> add("audio", Status.FAIL, "File contains audio after the sealed end.")
                si.totalSamples != frames -> add("audio", Status.FAIL, "FLAC header declares a different length than the audio it holds.")
                si.md5.any { it != 0.toByte() } && !si.md5.contentEquals(md5.digest()) -> add("audio", Status.FAIL, "FLAC header checksum does not match the audio.")
                part == null && frames != m.audio.totalFrames -> add("audio", Status.FAIL, "Frame count differs from manifest.")
                else -> add("audio", Status.OK, "$verified of ${range.size} audio slices match" + (part?.let { " (part ${it.index} of ${it.count}, ${audio.size} slices in the full recording)" } ?: "") + ".")
            }
        } catch (e: Exception) {
            add("audio", Status.FAIL, "Audio cannot be decoded: ${e.message}")
        }

        // 4. device identity
        val deviceKey = deviceCerts.first().publicKey
        val idOk = sha256(deviceKey.encoded).hex() == m.keys.deviceId
        val endorsed = ecVerify(deviceKey, Wire.endorsement(sid, sessionKey.encoded), m.keys.endorsement.unb64())
        if (idOk && endorsed) add("device", Status.OK, "Recording key endorsed by device ${shortDeviceId(m.keys.deviceId)} (${m.device.manufacturer} ${m.device.model}).")
        else add("device", Status.FAIL, "Device identity does not match the recording key.")

        // 5. time
        // Capture starts at once; the opening timestamp arrives a moment later. The sealed log
        // records how much audio already existed at that point.
        val anchoredAt = m.entries.firstOrNull { it.k == "e" && it.j.startsWith("{\"type\":\"anchored\"") }
            ?.let { Regex("\"pos\":(\\d{1,15})").find(it.j)?.groupValues?.get(1)?.toLong() }
        val preSeconds = (anchoredAt ?: 0L).toDouble() / m.audio.sampleRate
        var openTime: Date? = null
        val openBytes = m.open?.token?.unb64()
        if (m.open != null && openBytes != null) {
            try {
                val t = Tsa.inspect(openBytes, sha256(m.open.nonce.unhex()))
                when {
                    !t.valid -> add("time-open", Status.FAIL, "Opening timestamp is not valid.")
                    t.trustedService == null -> add("time-issuer", Status.WARN, "Opening timestamp is cryptographically valid but not issued by a recognised EU Trusted List service (${t.signer}). It is not counted.")
                    else -> { openTime = t.time; add("time-open", Status.OK, "Opening timestamp ${t.time.toInstant()}, obtained %.0f s into the recording; the recording key was created after it · ${describe(t)}".format(preSeconds)) }
                }
            } catch (e: Exception) { add("time-open", Status.FAIL, "Opening timestamp unreadable: ${e.message}") }
        } else add("time-open", Status.WARN, "No opening timestamp (recorded offline). Start time is device-declared.")

        var closeTime: Date? = null
        if (bundle.closeTokens.isEmpty()) add("time-close", Status.WARN, "Not yet timestamped by an independent authority.")
        for (tok in bundle.closeTokens) {
            try {
                val t = Tsa.inspect(tok.token.unb64(), sha256(bundle.manifestBytes))
                when {
                    !t.valid -> add("time-close", Status.FAIL, "Closing timestamp from ${tok.provider} is not valid.")
                    t.trustedService == null -> add("time-issuer", Status.WARN, "Closing timestamp is cryptographically valid but not issued by a recognised EU Trusted List service (${t.signer}). It is not counted.")
                    else -> {
                        if (closeTime == null || t.time.before(closeTime)) closeTime = t.time
                        add("time-close", Status.OK, "Existed unchanged at ${t.time.toInstant()} · ${describe(t)}")
                    }
                }
            } catch (e: Exception) { add("time-close", Status.FAIL, "Closing timestamp unreadable: ${e.message}") }
        }
        if (closeTime == null && bundle.closeTokens.isNotEmpty() && checks.none { it.id == "time-close" }) add("time-close", Status.WARN, "No closing timestamp from a recognised service.")
        // Intermediate timestamps: each proves that the first part of the recording already
        // existed, sealed, at a certified moment while the recording was still running.
        var midOk = 0
        // Frames of audio sealed before each log position, computed once.
        val framesBefore = LongArray(m.entries.size + 1)
        m.entries.forEachIndexed { i, e -> framesBefore[i + 1] = framesBefore[i] + (if (e.k == "a") e.f else 0) }
        var lastMid: Date? = null
        var lastMidSeconds = 0.0
        // Every checkpoint's digest is computed once, so each intermediate timestamp is read
        // once however many checkpoints share its size. The result is the same as trying them
        // in order: the first checkpoint of that size whose digest the token covers (the digest
        // commits to the size), and a root that is not hex before it makes the timestamp unreadable.
        val cpAt = HashMap<String, Int>()      // digest -> first checkpoint with it
        val badRootAt = HashMap<Int, Int>()    // size -> first checkpoint of that size whose root is not hex
        m.checkpoints.forEachIndexed { i, c ->
            try { cpAt.putIfAbsent(sha256(Wire.checkpoint(sid, c.size, c.root.unhex(), c.en, c.w)).hex(), i) }
            catch (e: IllegalArgumentException) { badRootAt.putIfAbsent(c.size, i) }
        }
        val cpSizes = m.checkpoints.mapTo(HashSet()) { it.size }
        for (a in m.mid.sortedBy { it.size }) {
            try {
                val raw = a.token.unb64()
                val at = if (a.size !in cpSizes) null else Tsa.imprint(raw)?.let { cpAt[it.hex()] }?.takeIf { m.checkpoints[it].size == a.size }
                val bad = badRootAt[a.size]
                if (bad != null && (at == null || bad < at)) throw IllegalArgumentException("invalid hex digit")
                val cp = at?.let { m.checkpoints[it] }
                val t = cp?.let { Tsa.inspect(raw, sha256(Wire.checkpoint(sid, it.size, it.root.unhex(), it.en, it.w))) }
                val covered = framesBefore[a.size.coerceIn(0, m.entries.size)].toDouble() / m.audio.sampleRate
                when {
                    t == null || !t.valid -> add("time-mid", Status.FAIL, "Intermediate timestamp for the first ${a.size} entries is not valid.")
                    t.trustedService == null -> add("time-issuer", Status.WARN, "An intermediate timestamp is not from a recognised EU Trusted List service (${t.signer}). It is not counted.")
                    openTime != null && t.time.before(openTime) || lastMid != null && t.time.before(lastMid) ->
                        add("time-mid", Status.FAIL, "Intermediate timestamps are not in chronological order.")
                    openTime != null && covered - preSeconds > (t.time.time - openTime.time) / 1000.0 + 5 ->
                        add("time-mid", Status.FAIL, "%.0f s of audio were already sealed only %.0f s after the opening timestamp.".format(covered - preSeconds, (t.time.time - openTime.time) / 1000.0))
                    // Audio is captured in real time, so no stretch between two certified
                    // instants can hold more audio than the time that passed between them.
                    lastMid != null && covered - lastMidSeconds > (t.time.time - lastMid.time) / 1000.0 + 30 ->
                        add("time-mid", Status.FAIL, "%.0f s of audio were sealed in the %.0f s between two intermediate timestamps.".format(covered - lastMidSeconds, (t.time.time - lastMid.time) / 1000.0))
                    else -> { midOk++; lastMid = t.time; lastMidSeconds = covered }
                }
            } catch (e: Exception) { add("time-mid", Status.FAIL, "Intermediate timestamp unreadable: ${e.message}") }
        }
        if (midOk > 0) add("time-mid", Status.OK, "$midOk intermediate timestamp(s) during the recording; the first %.0f s were already sealed at ${lastMid!!.toInstant()}.".format(lastMidSeconds))
        if (lastMid != null && closeTime != null) {
            val after = (closeTime.time - lastMid.time) / 1000.0
            if (after < 0) add("time-mid", Status.FAIL, "An intermediate timestamp is later than the closing timestamp.")
            else if (seconds - lastMidSeconds > after + 15) add("time-mid", Status.FAIL, "%.0f s of audio follow the last intermediate timestamp but only %.0f s passed until the closing timestamp.".format(seconds - lastMidSeconds, after))
        }
        // The two certified instants bracket the recording: it cannot hold more audio than fits between them.
        if (openTime != null && closeTime != null) {
            val between = (closeTime.time - openTime.time) / 1000.0
            if (between < 0) add("time-close", Status.FAIL, "Closing timestamp is earlier than the opening timestamp.")
            else if (seconds - preSeconds > between + (if (anchoredAt != null) 5 else 30)) add("time-close", Status.FAIL, "The file holds %.0f s of audio after the opening timestamp but only %.0f s passed between the certified timestamps.".format(seconds - preSeconds, between))
            if (preSeconds > 120) add("time-open", Status.WARN, "The opening timestamp was obtained %.0f s after the recording started.".format(preSeconds))
        }

        // 6. hardware attestation of the recording key
        val chain = Attestation.checkChain(sessionCerts)
        val att = try { Attestation.read(sessionCerts.first()) } catch (e: Exception) { null }
        when {
            att == null -> add("attestation", Status.INFO, "No hardware attestation present in this recording.")
            !chain.signaturesValid -> add("attestation", Status.FAIL, "Attestation certificate chain is broken: ${chain.error}")
            !att.challenge.contentEquals(Wire.sessionChallenge(sid, openBytes)) -> add("attestation", Status.FAIL, "Recording key was not created for this session.")
            else -> {
                val level = when (att.securityLevel) { 2 -> "StrongBox"; 1 -> "TEE"; else -> "software" }
                val boot = when (att.bootState) { 0 -> "verified"; 1 -> "self-signed"; 2 -> "unverified"; 3 -> "failed"; else -> "unknown" }
                val detail = "Key in $level, boot $boot, bootloader ${if (att.deviceLocked == true) "locked" else "not locked"}, app ${att.packages.joinToString()} signed by ${att.appSigners.joinToString { it.take(16) + "…" }}."
                val strong = chain.googleRoot && att.securityLevel >= 1 && att.bootState == 0 && att.deviceLocked == true && m.app.pkg in att.packages
                when {
                    strong && !Release.official(att.appSigners) -> add("attestation", Status.INFO, "The app that made this recording is not signed with the official release key. $detail")
                    strong -> add("attestation", Status.OK, detail)
                    !chain.googleRoot -> add("attestation", Status.INFO, "Attestation root is not a known Google key. $detail")
                    else -> add("attestation", Status.INFO, "Attestation present, device state not confirmed as verified. $detail")
                }
                if (openTime != null) add("attestation", Status.INFO, "Recording key did not exist before the opening timestamp.")
            }
        }

        // 7. continuity
        val gaps = m.entries.count { it.k == "e" && (it.j.startsWith("{\"type\":\"gap\"") || it.j.startsWith("{\"type\":\"interrupted\"")) }
        val silent = audio.count { it.fl and FLAG_SILENT != 0 }
        val overruns = audio.count { it.fl and FLAG_OVERRUN != 0 }
        if (audio.size >= 2) {
            val clock = (audio.last().en - audio.first().en) / 1e9
            val samples = audio.drop(1).sumOf { it.f.toLong() }.toDouble() / m.audio.sampleRate
            val drift = abs(clock - samples)
            val limit = 1.5 + samples * 0.002
            val detail = "Sample clock and device clock differ by %.2f s over %.0f s.".format(drift, samples)
            when {
                gaps > 0 || overruns > 0 -> add("continuity", Status.WARN, "$gaps declared interruption(s), $overruns overrun(s). $detail")
                drift > limit -> add("continuity", Status.WARN, "Unexplained time difference. $detail")
                silent > 0 -> add("continuity", Status.WARN, "$silent second(s) of system-imposed silence. $detail")
                else -> add("continuity", Status.OK, "Continuous, no declared gaps. $detail")
            }
        } else add("continuity", Status.INFO, "Too short to analyse.")
        if (m.endReason != "user") add("continuity", Status.WARN, "Recording ended by: ${m.endReason}.")
        return Report(checks, m, part, openTime, closeTime, seconds, verified)
    }

    private fun describe(t: TokenInfo) = buildString {
        append(t.signer)
        append(" · EU Trusted List qualified time stamp service: ").append(t.trustedService)
        if (!t.declaresQualified) append(" · token carries no qualified statement")
    }
}
