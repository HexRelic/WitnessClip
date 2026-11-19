// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val FORMAT = "audio-evidence/1"

/** Flags on an audio entry. */
const val FLAG_SILENT = 1      // the system delivered digital silence (microphone taken away)
const val FLAG_OVERRUN = 2     // samples were lost before this second
const val FLAG_CLIPPED = 4     // at least one sample reached full scale

/**
 * One line of the sealed log, in order.
 * k = "a": one slice of audio. f frames, h = SHA-256 of its PCM bytes, r = index into routes.
 * k = "e": an event. j is its JSON text, hashed exactly as written.
 * en = monotonic clock (ns since boot), w = wall clock (ms since 1970). Both are device-declared.
 */
@Serializable
data class Entry(
    val k: String, val en: Long, val w: Long,
    val f: Int = 0, val fl: Int = 0, val r: Int = 0, val h: String = "", val j: String = "",
)

@Serializable data class Checkpoint(val size: Int, val root: String, val en: Long, val w: Long, val sig: String)
@Serializable data class AppInfo(val pkg: String, val versionName: String, val versionCode: Long)
@Serializable data class AudioInfo(val sampleRate: Int, val channels: Int, val bits: Int, val totalFrames: Long, val leafFrames: Int, val source: String,
    /** Constant gain applied to the microphone signal before sealing, in dB. */
    val gainDb: Int = 0)
@Serializable data class DeviceInfo(val manufacturer: String, val model: String, val android: String, val sdk: Int, val patch: String)
@Serializable data class Route(val type: Int, val name: String, val product: String, val builtIn: Boolean)

/** Certificates are base64 DER, leaf first. */
@Serializable
data class KeyInfo(
    val deviceId: String, val deviceChain: List<String>, val sessionChain: List<String>,
    /** Device key's signature over the session key: proves the recording comes from this device. */
    val endorsement: String,
    /** "strongbox", "tee" or "software". Declared by the app; the certificate chain is the proof. */
    val level: String,
)

/** Timestamp taken before the session key existed. The key's attestation challenge commits to it. */
@Serializable data class OpenAnchor(val provider: String, val nonce: String, val token: String)

@Serializable
data class Manifest(
    val format: String = FORMAT,
    val sessionId: String,
    val app: AppInfo, val audio: AudioInfo, val device: DeviceInfo, val keys: KeyInfo,
    val open: OpenAnchor? = null,
    val routes: List<Route>,
    val entries: List<Entry>,
    val checkpoints: List<Checkpoint>,
    val mid: List<MidAnchor> = emptyList(),
    val root: String,
    /** "user", "power-loss" or "killed". */
    val endReason: String,
)

/** A timestamp taken while recording, over the signed checkpoint that covers the first [size] log entries. */
@Serializable data class MidAnchor(val size: Int, val provider: String, val token: String)

/** Present in exported parts and excerpts: which audio entries this file carries. */
@Serializable data class PartInfo(val index: Int, val count: Int, val firstAudio: Int, val audioEntries: Int)

@Serializable data class Token(val provider: String, val token: String)

val J = Json { encodeDefaults = false; ignoreUnknownKeys = true }

object Wire {
    fun entryBytes(sid: ByteArray, seq: Int, e: Entry): ByteArray = when (e.k) {
        "a" -> Buf().ascii("A").bytes(sid).i64(seq.toLong()).i32(e.f).i64(e.en).i64(e.w).i32(e.fl).i32(e.r).bytes(e.h.unhex()).done()
        "e" -> Buf().ascii("E").bytes(sid).i64(seq.toLong()).i64(e.en).i64(e.w).utf8(e.j).done()
        else -> throw IllegalArgumentException("unknown entry kind ${e.k}")
    }

    fun checkpoint(sid: ByteArray, size: Int, root: ByteArray, en: Long, w: Long): ByteArray =
        Buf().ascii("AE1-checkpoint").bytes(sid).i64(size.toLong()).bytes(root).i64(en).i64(w).done()

    fun endorsement(sid: ByteArray, sessionSpki: ByteArray): ByteArray =
        Buf().ascii("AE1-endorse").bytes(sid).bytes(sessionSpki).done()

    /** Attestation challenge of the session key. Commits to the opening timestamp when there is one. */
    fun sessionChallenge(sid: ByteArray, openToken: ByteArray?): ByteArray =
        sha256(Buf().ascii("AE1-session").bytes(sid).bytes(openToken ?: ByteArray(0)).done())
}
