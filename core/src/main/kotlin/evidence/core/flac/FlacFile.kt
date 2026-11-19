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

    private fun blockHeader(out: OutputStream, last: Boolean, type: Int, len: Int) {
        require(len <= MAX_BLOCK) { "metadata block too large" }
        out.write((if (last) 0x80 else 0) or type)
        out.write(len ushr 16); out.write(len ushr 8); out.write(len)
    }

    /** Writes header + STREAMINFO + optional evidence block, then copies [frames] verbatim. */
    fun write(out: OutputStream, info: StreamInfo, evidence: ByteArray?, frames: InputStream) {
        out.write("fLaC".toByteArray())
        blockHeader(out, evidence == null, 0, 34)
        out.write(info.encode())
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

    /** Copies [src] to [out] replacing (or adding) the evidence block. Audio frames are untouched. */
    fun replaceEvidence(src: InputStream, out: OutputStream, evidence: ByteArray) {
        val d = DataInputStream(src)
        val magic = ByteArray(4); d.readFully(magic)
        if (!magic.contentEquals("fLaC".toByteArray())) throw FlacFormatException("not a FLAC file")
        var info: StreamInfo? = null
        while (true) {
            val h = d.readUnsignedByte()
            val len = (d.readUnsignedByte() shl 16) or (d.readUnsignedByte() shl 8) or d.readUnsignedByte()
            if (h and 0x7f == 0 && len == 34) { val body = ByteArray(34); d.readFully(body); info = StreamInfo.decode(body) }
            else skipFully(d, len.toLong())
            if (h and 0x80 != 0) break
        }
        write(out, info ?: throw FlacFormatException("missing STREAMINFO"), evidence, d)
    }
}
