// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import evidence.core.flac.FlacDecoder
import evidence.core.flac.FlacEncoder
import evidence.core.flac.FlacFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File

/**
 * Derives smaller files from a sealed recording without touching it. Each part is an ordinary
 * .flac holding a run of whole sealed slices plus the full evidence, so it verifies on its own
 * against the same sealed root.
 */
object Exporter {
    /** Splits [src] into parts of at most about [maxBytes]. Returns the files written to [dir]. */
    fun split(src: File, maxBytes: Long, dir: File, baseName: String): List<File> {
        val bundle = readBundle(src)
        val audio = bundle.manifest.entries.count { it.k == "a" }
        if (audio == 0) return emptyList()
        val perEntry = (src.length().toDouble() / audio).coerceAtLeast(1.0)
        var perPart = ((maxBytes * 0.9) / perEntry).toInt().coerceAtLeast(1)
        // The estimate is an average; loud passages compress less. If any part comes out too
        // big, try again with fewer seconds per part.
        while (true) {
            val count = (audio + perPart - 1) / perPart
            val out = ArrayList<File>()
            BufferedInputStream(src.inputStream(), 1 shl 16).use { input ->
                val reader = PcmReader(FlacDecoder.open(input))
                for (p in 0 until count) {
                    val first = p * perPart
                    val n = minOf(perPart, audio - first)
                    val f = File(dir, "%s.part-%02d-of-%02d.flac".format(baseName, p + 1, count))
                    writePart(bundle, reader, PartInfo(p + 1, count, first, n), f)
                    out.add(f)
                }
            }
            if (perPart == 1 || out.all { it.length() <= maxBytes }) return out
            out.forEach { it.delete() }
            perPart = (perPart * 0.8).toInt().coerceAtLeast(1)
        }
    }

    fun readBundle(flac: File): Bundle = flac.inputStream().use {
        Bundle.decode(FlacDecoder.open(BufferedInputStream(it)).application[FlacFile.APP_ID] ?: throw IllegalArgumentException("no evidence block"))
    }

    private fun writePart(bundle: Bundle, reader: PcmReader, part: PartInfo, out: File) {
        val m = bundle.manifest
        val entries = m.entries.filter { it.k == "a" }.subList(part.firstAudio, part.firstAudio + part.audioEntries)
        val tmp = File(out.path + ".frames")
        try {
            val info = BufferedOutputStream(tmp.outputStream(), 1 shl 16).use { o ->
                val enc = FlacEncoder(o, m.audio.sampleRate, m.audio.channels, m.audio.bits)
                val buf = IntArray(entries.maxOf { it.f } * m.audio.channels)
                for (e in entries) {
                    if (reader.read(buf, e.f) != e.f) throw IllegalStateException("source audio shorter than its manifest")
                    enc.write(buf, e.f)
                }
                enc.finish()
            }
            BufferedOutputStream(out.outputStream(), 1 shl 16).use { o ->
                tmp.inputStream().use { FlacFile.write(o, info, bundle.with(part = part).encode(), it) }
            }
        } finally { tmp.delete() }
    }
}
