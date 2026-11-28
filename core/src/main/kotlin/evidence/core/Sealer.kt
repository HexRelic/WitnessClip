// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import kotlinx.serialization.encodeToString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Signs with a private key the caller cannot read (Android Keystore on the phone). */
interface EvidenceSigner {
    /** ECDSA P-256 with SHA-256, DER-encoded signature. */
    fun sign(data: ByteArray): ByteArray
    /** Certificate chain, DER, leaf first. */
    val chain: List<ByteArray>
}

/**
 * Builds the sealed log while recording. Every second of audio and every event is appended
 * at once; a signed checkpoint commits to everything recorded so far.
 */
class Sealer(val sid: ByteArray) {
    private val log = MerkleLog()
    val entries = ArrayList<Entry>()
    val checkpoints = ArrayList<Checkpoint>()

    @Synchronized
    fun add(e: Entry): Entry {
        log.append(MerkleLog.leaf(Wire.entryBytes(sid, entries.size, e)))
        entries.add(e)
        return e
    }

    fun addAudio(pcm: ByteArray, len: Int, frames: Int, en: Long, w: Long, flags: Int, route: Int): Entry {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(pcm, 0, len)
        return add(Entry("a", en, w, f = frames, fl = flags, r = route, h = md.digest().hex()))
    }

    fun addEvent(json: String, en: Long, w: Long): Entry = add(Entry("e", en, w, j = json))

    /** Signing can be slow in secure hardware, so it happens outside the lock that audio needs. */
    fun checkpoint(signer: EvidenceSigner, en: Long, w: Long): Checkpoint {
        val size: Int
        val root: ByteArray
        synchronized(this) { size = log.size; root = log.root() }
        val cp = Checkpoint(size, root.hex(), en, w, signer.sign(Wire.checkpoint(sid, size, root, en, w)).b64())
        synchronized(this) { checkpoints.add(cp) }
        return cp
    }

    @Synchronized fun restoreCheckpoint(cp: Checkpoint) { checkpoints.add(cp) }
    @Synchronized fun root(): ByteArray = log.root()
    @Synchronized fun size(): Int = log.size
}

/**
 * Everything needed to verify a recording, as a small ZIP stored inside the audio file:
 *   evidence.json   the manifest (the signed bytes)
 *   evidence.sig    session key signature over evidence.json
 *   close-N.tsr     RFC 3161 tokens over evidence.json
 *   close-N.txt     which provider issued token N
 *   part.json       only in exported parts
 */
class Bundle(val manifestBytes: ByteArray, val signature: ByteArray, val closeTokens: List<Token>, val part: PartInfo? = null) {
    val manifest: Manifest by lazy { J.decodeFromString(Manifest.serializer(), manifestBytes.toString(Charsets.UTF_8)) }

    fun with(tokens: List<Token> = closeTokens, part: PartInfo? = this.part) = Bundle(manifestBytes, signature, tokens, part)

    fun encode(): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name).apply { time = 0 }); z.write(data); z.closeEntry() }
            put("evidence.json", manifestBytes)
            put("evidence.sig", signature)
            closeTokens.forEachIndexed { i, t ->
                put("close-${i + 1}.tsr", t.token.unb64())
                put("close-${i + 1}.txt", t.provider.toByteArray())
            }
            manifest.open?.let { put("open.tsr", it.token.unb64()) }
            part?.let { put("part.json", J.encodeToString(it).toByteArray()) }
        }
        return bo.toByteArray()
    }

    companion object {
        private const val MAX_UNPACKED = 192 * 1024 * 1024
        /**
         * A real bundle is hashes, signatures and certificates: it shrinks about 2.5 times, and
         * even an all-silent recording stays under 25 times. Anything expanding further than
         * this is not evidence, and would only make a small file cost a lot of memory.
         */
        private const val MAX_RATIO = 64
        /** Small bundles are never held to the ratio. */
        private const val MIN_UNPACKED = 1024 * 1024

        fun decode(zip: ByteArray): Bundle {
            val files = HashMap<String, ByteArray>()
            // Bounded: a hostile file must not be able to expand into gigabytes, nor into far
            // more than its own size.
            val limit = (zip.size.toLong() * MAX_RATIO).coerceIn(MIN_UNPACKED.toLong(), MAX_UNPACKED.toLong())
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                var total = 0L
                while (true) {
                    val e = z.nextEntry ?: break
                    if (files.size >= 64) throw IllegalArgumentException("too many evidence files")
                    val data = readCapped(z, (limit - total).toInt())
                    total += data.size
                    files[e.name] = data
                }
            }
            val tokens = ArrayList<Token>()
            var i = 1
            while (files.containsKey("close-$i.tsr")) {
                tokens.add(Token(files["close-$i.txt"]?.toString(Charsets.UTF_8) ?: "", files.getValue("close-$i.tsr").b64()))
                i++
            }
            return Bundle(
                files["evidence.json"] ?: throw IllegalArgumentException("evidence.json missing"),
                files["evidence.sig"] ?: throw IllegalArgumentException("evidence.sig missing"),
                tokens,
                files["part.json"]?.let { J.decodeFromString(PartInfo.serializer(), it.toString(Charsets.UTF_8)) },
            )
        }
    }
}
