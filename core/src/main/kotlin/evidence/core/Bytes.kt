// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

fun sha256(vararg parts: ByteArray): ByteArray {
    val md = MessageDigest.getInstance("SHA-256")
    for (p in parts) md.update(p)
    return md.digest()
}

private const val HEX = "0123456789abcdef"

fun ByteArray.hex(): String {
    val sb = StringBuilder(size * 2)
    for (b in this) {
        sb.append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
    }
    return sb.toString()
}

fun String.unhex(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { ((Character.digit(this[it * 2], 16) shl 4) or Character.digit(this[it * 2 + 1], 16)).toByte() }
}

fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

/** Big-endian byte builder used for every hashed or signed structure. */
class Buf {
    private val out = ByteArrayOutputStream()
    fun u8(v: Int) = apply { out.write(v) }
    fun i32(v: Int) = apply { for (s in 24 downTo 0 step 8) out.write(v ushr s) }
    fun i64(v: Long) = apply { for (s in 56 downTo 0 step 8) out.write((v ushr s).toInt()) }
    fun bytes(b: ByteArray) = apply { out.write(b) }
    fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.US_ASCII)) }
    fun utf8(s: String) = apply { out.write(s.toByteArray(Charsets.UTF_8)) }
    fun done(): ByteArray = out.toByteArray()
}

/**
 * Device ID shown to people: the first 128 bits of SHA-256(device public key) as 32 hex
 * digits in groups of four. The full 256-bit value is always printed next to it in reports.
 */
fun shortDeviceId(deviceIdHex: String): String = deviceIdHex.take(32).uppercase().chunked(4).joinToString("-")

/**
 * Reads the rest of [input] but refuses more than [max] bytes. Written by hand because
 * InputStream.readNBytes does not exist on Android before version 13.
 */
fun readCapped(input: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(16384)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size() + n > max) throw IOException("input larger than $max bytes")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

fun skipFully(input: InputStream, count: Long) {
    var left = count
    val buf = ByteArray(8192)
    while (left > 0) {
        val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
        if (n < 0) throw EOFException()
        left -= n
    }
}
