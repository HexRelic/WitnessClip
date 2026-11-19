// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core.flac

import java.io.OutputStream
import java.security.MessageDigest

class StreamInfo(
    val minBlock: Int, val maxBlock: Int, val minFrame: Int, val maxFrame: Int,
    val sampleRate: Int, val channels: Int, val bps: Int, val totalSamples: Long, val md5: ByteArray,
) {
    fun encode(): ByteArray {
        val w = BitWriter()
        w.bits(minBlock.toLong(), 16); w.bits(maxBlock.toLong(), 16)
        w.bits(minFrame.toLong(), 24); w.bits(maxFrame.toLong(), 24)
        w.bits(sampleRate.toLong(), 20); w.bits((channels - 1).toLong(), 3); w.bits((bps - 1).toLong(), 5)
        w.bits(totalSamples, 36)
        val b = w.toByteArray()
        return b + md5
    }

    companion object {
        fun decode(b: ByteArray): StreamInfo {
            fun u(off: Int, n: Int): Long { var v = 0L; for (i in 0 until n) v = (v shl 8) or (b[off + i].toLong() and 0xff); return v }
            val packed = u(10, 8)
            return StreamInfo(
                u(0, 2).toInt(), u(2, 2).toInt(), u(4, 3).toInt(), u(7, 3).toInt(),
                (packed ushr 44).toInt(), ((packed ushr 41) and 7).toInt() + 1, ((packed ushr 36) and 31).toInt() + 1,
                packed and 0xFFFFFFFFFL, b.copyOfRange(18, 34),
            )
        }
    }
}

class BitWriter(initial: Int = 16384) {
    private var buf = ByteArray(initial)
    private var pos = 0
    private var acc = 0L
    private var nacc = 0

    fun bits(value: Long, n: Int) {
        var left = n
        while (left > 0) {
            val take = minOf(left, 8 - nacc)
            val chunk = (value ushr (left - take)) and ((1L shl take) - 1)
            acc = (acc shl take) or chunk
            nacc += take
            left -= take
            if (nacc == 8) {
                if (pos == buf.size) buf = buf.copyOf(buf.size * 2)
                buf[pos++] = acc.toByte()
                acc = 0; nacc = 0
            }
        }
    }

    fun signed(value: Int, n: Int) = bits(value.toLong() and ((1L shl n) - 1), n)

    fun unary(q: Int) {
        var z = q
        while (z >= 32) { bits(0, 32); z -= 32 }
        bits(1, z + 1)
    }

    fun align() { if (nacc > 0) bits(0, 8 - nacc) }
    val byteLength get() = pos
    fun raw(): ByteArray = buf
    fun toByteArray(): ByteArray { align(); return buf.copyOf(pos) }
}

internal object Crc {
    private val t8 = IntArray(256) { var c = it; repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }; c and 0xff }
    private val t16 = IntArray(256) { var c = it shl 8; repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }; c and 0xffff }
    fun crc8(b: ByteArray, len: Int): Int { var c = 0; for (i in 0 until len) c = t8[c xor (b[i].toInt() and 0xff)]; return c }
    fun crc16(b: ByteArray, len: Int): Int { var c = 0; for (i in 0 until len) c = up16(c, b[i].toInt() and 0xff); return c }
    fun up8(c: Int, byte: Int) = t8[c xor byte]
    fun up16(c: Int, byte: Int) = ((c shl 8) and 0xffff) xor t16[(c ushr 8) xor byte]
}

/**
 * Small, deterministic FLAC encoder (fixed predictors + partitioned Rice coding, independent
 * channels). Same input always gives the same bytes. Writes frames only; the stream header is
 * added by [FlacFile] once the totals are known.
 */
class FlacEncoder(
    private val out: OutputStream,
    val sampleRate: Int, val channels: Int, val bps: Int, private val blockSize: Int = 4096,
) {
    private val pending = IntArray(blockSize * channels)
    private var fill = 0
    private var frameNo = 0L
    private var total = 0L
    private var minFrame = Int.MAX_VALUE
    private var maxFrame = 0
    private var minBlock = Int.MAX_VALUE
    private var maxBlock = 0
    private val md5 = MessageDigest.getInstance("MD5")
    private val bytesPerSample = bps / 8
    private val md5buf = ByteArray(blockSize * channels * bytesPerSample)
    private val chan = IntArray(blockSize)
    private val res = Array(5) { IntArray(blockSize) }

    init { require(bps == 16 || bps == 24) { "16 or 24 bit only" } }

    /** [pcm] holds interleaved samples, [frames] sample frames. */
    fun write(pcm: IntArray, frames: Int) {
        var off = 0
        while (off < frames) {
            val n = minOf(blockSize - fill, frames - off)
            System.arraycopy(pcm, off * channels, pending, fill * channels, n * channels)
            fill += n; off += n
            if (fill == blockSize) flushBlock()
        }
    }

    fun finish(): StreamInfo {
        if (fill > 0) flushBlock()
        out.flush()
        if (total == 0L) { minFrame = 0; minBlock = blockSize; maxBlock = blockSize }
        // The last block may be shorter; STREAMINFO min block size ignores it by convention.
        val minB = if (frameNo > 1) blockSize else minBlock
        return StreamInfo(minB, maxOf(maxBlock, minB), minFrame, maxFrame, sampleRate, channels, bps, total, md5.digest())
    }

    private fun flushBlock() {
        val n = fill
        var p = 0
        for (i in 0 until n * channels) {
            val s = pending[i]
            md5buf[p++] = s.toByte(); md5buf[p++] = (s shr 8).toByte()
            if (bytesPerSample == 3) md5buf[p++] = (s shr 16).toByte()
        }
        md5.update(md5buf, 0, p)

        // For stereo, try plain left/right and left/side (right stored as the difference)
        // and keep whichever is smaller. Both decode to exactly the same samples.
        var w = frame(n, false)
        if (channels == 2) { val alt = frame(n, true); if (alt.byteLength < w.byteLength) w = alt }
        val len = w.byteLength
        out.write(w.raw(), 0, len)
        minFrame = minOf(minFrame, len); maxFrame = maxOf(maxFrame, len)
        minBlock = minOf(minBlock, n); maxBlock = maxOf(maxBlock, n)
        total += n; frameNo++; fill = 0
    }

    private fun frame(n: Int, leftSide: Boolean): BitWriter {
        val w = BitWriter(n * channels * bytesPerSample + 64)
        w.bits(0x3FFE, 14); w.bits(0, 2)
        w.bits(if (n == 4096) 12 else 7, 4)
        w.bits(when (sampleRate) { 8000 -> 4; 16000 -> 5; 22050 -> 6; 24000 -> 7; 32000 -> 8; 44100 -> 9; 48000 -> 10; 96000 -> 11; else -> 0 }, 4)
        w.bits(if (leftSide) 8L else (channels - 1).toLong(), 4)
        w.bits(if (bps == 16) 4 else 6, 3); w.bits(0, 1)
        utf8(w, frameNo)
        if (n != 4096) w.bits((n - 1).toLong(), 16)
        w.bits(Crc.crc8(w.raw(), w.byteLength).toLong(), 8)
        for (c in 0 until channels) {
            if (leftSide && c == 1) for (i in 0 until n) chan[i] = pending[i * 2] - pending[i * 2 + 1]
            else for (i in 0 until n) chan[i] = pending[i * channels + c]
            subframe(w, n, if (leftSide && c == 1) bps + 1 else bps)
        }
        w.align()
        w.bits(Crc.crc16(w.raw(), w.byteLength).toLong(), 16)
        return w
    }

    private fun utf8(w: BitWriter, v: Long) {
        if (v < 0x80) { w.bits(v, 8); return }
        var bytes = 2
        while (bytes < 7 && v >= (1L shl (5 * bytes + 1))) bytes++
        w.bits(((0xFF00 ushr bytes) and 0xFF).toLong() or (v ushr (6 * (bytes - 1))), 8)
        for (i in bytes - 2 downTo 0) w.bits(0x80L or ((v ushr (6 * i)) and 0x3F), 8)
    }

    private fun subframe(w: BitWriter, n: Int, bps: Int) {
        val x = chan
        var constant = true
        for (i in 1 until n) if (x[i] != x[0]) { constant = false; break }
        if (constant) { w.bits(0, 8); w.signed(x[0], bps); return }

        // residuals for fixed orders 0..4, pick the smallest sum of magnitudes
        val maxOrder = minOf(4, n - 1)
        var best = 0
        var bestSum = Long.MAX_VALUE
        for (o in 0..maxOrder) {
            val r = res[o]
            var sum = 0L
            for (i in o until n) {
                val v = when (o) {
                    0 -> x[i]
                    1 -> x[i] - x[i - 1]
                    2 -> x[i] - 2 * x[i - 1] + x[i - 2]
                    3 -> x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
                    else -> x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
                }
                r[i] = (v shl 1) xor (v shr 31) // zigzag fold
                sum += r[i].toLong() and 0xFFFFFFFFL
            }
            if (sum < bestSum) { bestSum = sum; best = o }
        }
        val u = res[best]
        val maxK = if (bps > 16) 30 else 14
        // choose partition order
        var bestP = 0
        var bestBits = Long.MAX_VALUE
        var maxP = Integer.numberOfTrailingZeros(n).coerceAtMost(8)
        while (maxP > 0 && (n shr maxP) <= best) maxP--
        val ks = Array(maxP + 1) { IntArray(1 shl it) }
        for (p in 0..maxP) {
            val parts = 1 shl p
            val plen = n shr p
            var bitsTotal = 0L
            for (j in 0 until parts) {
                val from = if (j == 0) best else j * plen
                val to = (j + 1) * plen
                var sum = 0L
                for (i in from until to) sum += u[i].toLong() and 0xFFFFFFFFL
                val cnt = to - from
                var k = 0
                if (cnt > 0) { val mean = sum / cnt; while (k < maxK && (1L shl (k + 1)) <= mean) k++ }
                var bk = k
                var bb = Long.MAX_VALUE
                for (kk in maxOf(0, k - 1)..minOf(maxK, k + 1)) {
                    var b = cnt.toLong() * (kk + 1)
                    for (i in from until to) b += (u[i] ushr kk)
                    if (b < bb) { bb = b; bk = kk }
                }
                ks[p][j] = bk
                bitsTotal += bb + (if (bps > 16) 5 else 4)
            }
            if (bitsTotal < bestBits) { bestBits = bitsTotal; bestP = p }
        }
        val fixedBits = best.toLong() * bps + 6 + bestBits
        if (fixedBits >= n.toLong() * bps) { // verbatim
            w.bits(1L shl 1, 8)
            for (i in 0 until n) w.signed(x[i], bps)
            return
        }
        w.bits(((8 or best) shl 1).toLong(), 8)
        for (i in 0 until best) w.signed(x[i], bps)
        w.bits(if (bps > 16) 1 else 0, 2)
        w.bits(bestP.toLong(), 4)
        val plen = n shr bestP
        for (j in 0 until (1 shl bestP)) {
            val k = ks[bestP][j]
            w.bits(k.toLong(), if (bps > 16) 5 else 4)
            val from = if (j == 0) best else j * plen
            for (i in from until (j + 1) * plen) {
                w.unary(u[i] ushr k)
                if (k > 0) w.bits((u[i] and ((1 shl k) - 1)).toLong(), k)
            }
        }
    }
}
