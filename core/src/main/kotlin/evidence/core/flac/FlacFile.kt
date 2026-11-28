// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core.flac

import evidence.core.skipFully
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream

/** Assembles and rewrites .flac containers. The evidence travels in an APPLICATION block. */
object FlacFile {
    /** Registered-style 4-byte id of our APPLICATION metadata block. */
    const val APP_ID = "AEv1"
    private const val MAX_BLOCK = (1 shl 24) - 1
    private const val CHUNK = 8 * 1024 * 1024
    /** Upper bound on the evidence a reader will accept, to stay safe against hostile files. */
    const val MAX_EVIDENCE = 96 * 1024 * 1024
    /** PADDING, SEEKTABLE, VORBIS_COMMENT, CUESHEET, PICTURE: kept by [replaceEvidence] (APPLICATION is handled apart). */
    private val KEPT_TYPES = setOf(1, 3, 4, 5, 6)
    /** Far above a normal tagged file, and low enough that the output stays readable by [FlacDecoder]. */
    private const val MAX_KEPT = 16 * 1024 * 1024
    private const val MAX_KEPT_BLOCKS = 256

    private fun blockHeader(out: OutputStream, last: Boolean, type: Int, len: Int) {
        require(len <= MAX_BLOCK) { "metadata block too large" }
        out.write((if (last) 0x80 else 0) or type)
        out.write(len ushr 16); out.write(len ushr 8); out.write(len)
    }

    /** Writes header + STREAMINFO + optional evidence block, then copies [frames] verbatim. */
    fun write(out: OutputStream, info: StreamInfo, evidence: ByteArray?, frames: InputStream) = assemble(out, info, emptyList(), evidence, frames)

    /** As [write], with [kept] metadata blocks (type, body) placed between STREAMINFO and the evidence. */
    private fun assemble(out: OutputStream, info: StreamInfo, kept: List<Pair<Int, ByteArray>>, evidence: ByteArray?, frames: InputStream) {
        out.write("fLaC".toByteArray())
        blockHeader(out, evidence == null && kept.isEmpty(), 0, 34)
        out.write(info.encode())
        kept.forEachIndexed { i, (type, body) ->
            blockHeader(out, evidence == null && i == kept.lastIndex, type, body.size)
            out.write(body)
        }
        if (evidence != null) {
            // A metadata block holds at most 16 MB, so long recordings spread the evidence over
            // several consecutive blocks with the same id; readers join them in order.
            var off = 0
            do {
                val n = minOf(CHUNK, evidence.size - off)
                blockHeader(out, off + n == evidence.size, 2, n + 4)
                out.write(APP_ID.toByteArray(Charsets.ISO_8859_1))
                out.write(evidence, off, n)
                off += n
            } while (off < evidence.size)
        }
        frames.copyTo(out, 1 shl 16)
        out.flush()
    }

    /**
     * Copies [src] to [out] replacing (or adding) the evidence block. Audio frames are untouched;
     * standard metadata (padding, other applications, seek table, tags, cue sheet, pictures) is
     * carried over in its order. Reserved and malformed blocks are dropped, as before.
     */
    fun replaceEvidence(src: InputStream, out: OutputStream, evidence: ByteArray) {
        val d = DataInputStream(src)
        val magic = ByteArray(4); d.readFully(magic)
        if (!magic.contentEquals("fLaC".toByteArray())) throw FlacFormatException("not a FLAC file")
        var info: StreamInfo? = null
        val kept = ArrayList<Pair<Int, ByteArray>>()
        var keptBytes = 0L
        // Bounded before anything is allocated, so the copy stays small whatever the source holds.
        fun keep(type: Int, head: ByteArray, len: Int) {
            keptBytes += len
            if (kept.size >= MAX_KEPT_BLOCKS || keptBytes > MAX_KEPT) throw FlacFormatException("too much metadata to carry over")
            val body = head.copyOf(len); d.readFully(body, head.size, len - head.size)
            kept.add(type to body)
        }
        while (true) {
            val h = d.readUnsignedByte()
            val type = h and 0x7f
            val len = (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 8) or d.readUnsignedByte()
            if (type == 0 && len == 34) { val body = ByteArray(34); d.readFully(body); info = StreamInfo.decode(body) }
            else if (type == 2 && len >= 4) {
                val id = ByteArray(4); d.readFully(id)
                if (String(id, Charsets.ISO_8859_1) == APP_ID) skipFully(d, len - 4L)   // the old evidence
                else keep(type, id, len)
            }
            else if (type in KEPT_TYPES) keep(type, ByteArray(0), len)
            else skipFully(d, len.toLong())
            if (h and 0x80 != 0) break
        }
        assemble(out, info ?: throw FlacFormatException("missing STREAMINFO"), kept, evidence, d)
    }
}
