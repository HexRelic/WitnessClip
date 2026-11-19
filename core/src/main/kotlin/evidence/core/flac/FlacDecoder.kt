// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core.flac

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

class FlacFormatException(msg: String) : IOException(msg)

/** Byte-buffered bit reader that keeps running CRCs of every byte consumed. */
internal class BitReader(private val input: InputStream) {
    private val buf = ByteArray(65536)
    private var len = 0
    private var pos = 0
    private var cur = 0
    private var left = 0
    var crc16 = 0
    var crc8 = 0
    var bytesRead = 0L
        private set

    /** Next raw byte or -1 at end of stream. Only valid when byte-aligned. */
    fun byteOrEof(): Int {
        if (pos == len) { len = input.read(buf, 0, buf.size); pos = 0; if (len <= 0) { len = 0; return -1 } }
        val b = buf[pos++].toInt() and 0xff
        crc16 = Crc.up16(crc16, b); crc8 = Crc.up8(crc8, b); bytesRead++
        return b
    }

    private fun byte(): Int { val b = byteOrEof(); if (b < 0) throw EOFException(); return b }

    fun bits(n: Int): Long {
        var v = 0L
        var need = n
        while (need > 0) {
            if (left == 0) { cur = byte(); left = 8 }
            val take = minOf(need, left)
            v = (v shl take) or ((cur ushr (left - take)) and ((1 shl take) - 1)).toLong()
            left -= take; need -= take
        }
        return v
    }

    fun int(n: Int): Int = bits(n).toInt()
    fun signed(n: Int): Int { val v = bits(n); return ((v shl (64 - n)) shr (64 - n)).toInt() }

    fun unary(): Int {
        var q = 0
        while (true) {
            if (left == 0) { cur = byte(); left = 8 }
            val window = cur and ((1 shl left) - 1)
            if (window == 0) { q += left; left = 0 } else {
                val lead = Integer.numberOfLeadingZeros(window) - (32 - left)
                q += lead; left -= lead + 1
                return q
            }
        }
    }

    fun align() { left = 0 }
    fun bytes(n: Int): ByteArray { val b = ByteArray(n); for (i in 0 until n) b[i] = byte().toByte(); return b }
    fun skip(n: Int) { repeat(n) { byte() } }
}

/**
 * Streaming FLAC decoder covering the whole format (constant, verbatim, fixed and LPC
 * subframes, all stereo modes), so it can also check files produced by other encoders.
 */
class FlacDecoder private constructor(private val r: BitReader, val info: StreamInfo?, val application: Map<String, ByteArray>,
                                      private val sampleRate: Int, val channels: Int, val bps: Int) {
    /** Number of sample frames in the block returned by the last [readBlock]. */
    var blockFrames = 0
        private set
    private var chans = Array(channels) { IntArray(4096) }
    private var outBuf = IntArray(4096 * channels)

    /** Interleaved samples for the next block (valid for [blockFrames] frames) or null at the end. */
    fun readBlock(): IntArray? {
        r.crc16 = 0; r.crc8 = 0
        val b0 = r.byteOrEof()
        if (b0 < 0) return null
        val b1 = r.int(8)
        if (b0 != 0xFF || (b1 and 0xFE) != 0xF8) throw FlacFormatException("lost frame sync")
        val bsCode = r.int(4)
        val srCode = r.int(4)
        val chCode = r.int(4)
        val ssCode = r.int(3)
        r.int(1)
        var first = r.int(8)
        var extra = 0
        while (first and 0x80 != 0) { extra++; first = first shl 1 }
        if (extra > 0) r.skip(extra - 1)
        val n = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> r.int(8) + 1
            7 -> r.int(16) + 1
            in 8..15 -> 256 shl (bsCode - 8)
            else -> throw FlacFormatException("reserved block size")
        }
        when (srCode) { 12 -> r.skip(1); 13, 14 -> r.skip(2) }
        val headerCrc = r.crc8
        if (r.int(8) != headerCrc) throw FlacFormatException("frame header CRC mismatch")
        val depth = when (ssCode) { 0 -> bps; 1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24; 7 -> 32; else -> throw FlacFormatException("reserved sample size") }
        val nch = if (chCode < 8) chCode + 1 else 2
        if (nch != channels) throw FlacFormatException("channel count changed mid-stream")
        if (depth != bps) throw FlacFormatException("sample size changed mid-stream")
        if (chans[0].size < n) { chans = Array(channels) { IntArray(n) }; outBuf = IntArray(n * channels) }
        for (c in 0 until nch) {
            val side = (chCode == 8 && c == 1) || (chCode == 9 && c == 0) || (chCode == 10 && c == 1)
            subframe(chans[c], n, if (side) depth + 1 else depth)
        }
        r.align()
        val crc = r.crc16
        if (r.int(16) != crc) throw FlacFormatException("frame CRC mismatch")
        when (chCode) {
            8 -> for (i in 0 until n) chans[1][i] = chans[0][i] - chans[1][i]
            9 -> for (i in 0 until n) chans[0][i] = chans[0][i] + chans[1][i]
            10 -> for (i in 0 until n) {
                val s = chans[1][i]
                val m = (chans[0][i] shl 1) or (s and 1)
                chans[0][i] = (m + s) shr 1; chans[1][i] = (m - s) shr 1
            }
        }
        if (channels == 1) System.arraycopy(chans[0], 0, outBuf, 0, n)
        else for (i in 0 until n) for (c in 0 until channels) outBuf[i * channels + c] = chans[c][i]
        blockFrames = n
        return outBuf
    }

    private fun subframe(x: IntArray, n: Int, depthIn: Int) {
        if (r.int(1) != 0) throw FlacFormatException("subframe padding")
        val type = r.int(6)
        var wasted = 0
        if (r.int(1) == 1) wasted = r.unary() + 1
        val depth = depthIn - wasted
        if (depth < 1 || depth > 25) throw FlacFormatException("bad sample depth")
        when {
            type == 0 -> { val v = r.signed(depth); for (i in 0 until n) x[i] = v }
            type == 1 -> for (i in 0 until n) x[i] = r.signed(depth)
            type in 8..12 -> {
                val order = type - 8
                if (order > n) throw FlacFormatException("predictor longer than block")
                for (i in 0 until order) x[i] = r.signed(depth)
                residual(x, n, order)
                when (order) {
                    1 -> for (i in 1 until n) x[i] += x[i - 1]
                    2 -> for (i in 2 until n) x[i] += 2 * x[i - 1] - x[i - 2]
                    3 -> for (i in 3 until n) x[i] += 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]
                    4 -> for (i in 4 until n) x[i] += 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]
                }
            }
            type >= 32 -> {
                val order = type - 31
                if (order > n) throw FlacFormatException("predictor longer than block")
                for (i in 0 until order) x[i] = r.signed(depth)
                val precision = r.int(4) + 1
                val shift = r.signed(5)
                val coef = IntArray(order) { r.signed(precision) }
                residual(x, n, order)
                for (i in order until n) {
                    var sum = 0L
                    for (j in 0 until order) sum += coef[j].toLong() * x[i - 1 - j]
                    x[i] += (sum shr shift).toInt()
                }
            }
            else -> throw FlacFormatException("reserved subframe type")
        }
        if (wasted > 0) for (i in 0 until n) x[i] = x[i] shl wasted
    }

    private fun residual(x: IntArray, n: Int, order: Int) {
        val method = r.int(2)
        if (method > 1) throw FlacFormatException("reserved residual coding")
        val pbits = if (method == 0) 4 else 5
        val escape = (1 shl pbits) - 1
        val porder = r.int(4)
        val plen = n shr porder
        if ((plen shl porder) != n || plen < order) throw FlacFormatException("bad residual partition")
        var i = order
        for (p in 0 until (1 shl porder)) {
            val end = (p + 1) * plen
            val k = r.int(pbits)
            if (k == escape) {
                val raw = r.int(5)
                while (i < end) { x[i++] = if (raw == 0) 0 else r.signed(raw) }
            } else {
                while (i < end) {
                    val u = (r.unary() shl k) or (if (k > 0) r.int(k) else 0)
                    x[i++] = (u ushr 1) xor -(u and 1)
                }
            }
        }
    }

    val bytesRead get() = r.bytesRead

    companion object {
        /** Opens a complete .flac file (header + metadata + frames). */
        fun open(input: InputStream): FlacDecoder {
            val r = BitReader(input)
            if (!r.bytes(4).contentEquals("fLaC".toByteArray())) throw FlacFormatException("not a FLAC file")
            var info: StreamInfo? = null
            val apps = HashMap<String, ByteArray>()
            var evidenceSize = 0L
            var blocks = 0
            while (true) {
                val last = r.int(1)
                val type = r.int(7)
                val len = r.int(24)
                // Only STREAMINFO and our own APPLICATION blocks are kept; everything else is
                // skipped without being held in memory.
                if (type == 0 && len == 34 && info == null) info = StreamInfo.decode(r.bytes(len))
                else if (type == 2 && len >= 4) {
                    val id = String(r.bytes(4), Charsets.ISO_8859_1)
                    if (id == FlacFile.APP_ID) {
                        evidenceSize += len - 4
                        if (evidenceSize > FlacFile.MAX_EVIDENCE) throw FlacFormatException("evidence block too large")
                        apps[id] = (apps[id] ?: ByteArray(0)) + r.bytes(len - 4)
                    } else r.skip(len - 4)
                } else r.skip(len)
                if (++blocks > 4096) throw FlacFormatException("too many metadata blocks")
                if (last == 1) break
            }
            val si = info ?: throw FlacFormatException("missing STREAMINFO")
            if (si.channels !in 1..2 || (si.bps != 16 && si.bps != 24)) throw FlacFormatException("unsupported audio format")
            return FlacDecoder(r, si, apps, si.sampleRate, si.channels, si.bps)
        }

        /** Opens a bare frame stream as written by [FlacEncoder] during recording. */
        fun frames(input: InputStream, sampleRate: Int, channels: Int, bps: Int) =
            FlacDecoder(BitReader(input), null, emptyMap(), sampleRate, channels, bps)
    }
}
