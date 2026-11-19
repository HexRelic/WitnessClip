// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core.pdf

import evidence.core.hex
import evidence.core.sha256
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** The parts of a TrueType font a PDF needs: character to glyph mapping and glyph widths. */
class TrueTypeFont(val data: ByteArray) {
    private val tables = HashMap<String, Int>()
    private fun u16(p: Int) = ((data[p].toInt() and 0xff) shl 8) or (data[p + 1].toInt() and 0xff)
    private fun i16(p: Int) = u16(p).toShort().toInt()
    private fun u32(p: Int) = (u16(p).toLong() shl 16) or u16(p + 2).toLong()

    val unitsPerEm: Int
    val bbox: IntArray
    val ascent: Int
    val descent: Int
    val capHeight: Int
    private val numHMetrics: Int
    private val cmap: Int   // offset of a format 4 subtable

    init {
        for (i in 0 until u16(4)) { val p = 12 + i * 16; tables[String(data, p, 4, Charsets.ISO_8859_1)] = u32(p + 8).toInt() }
        fun table(t: String) = tables[t] ?: throw IllegalArgumentException("font has no $t table")
        val head = table("head"); val hhea = table("hhea")
        unitsPerEm = u16(head + 18)
        bbox = IntArray(4) { i16(head + 36 + it * 2) }
        ascent = i16(hhea + 4); descent = i16(hhea + 6)
        numHMetrics = u16(hhea + 34)
        val os2 = tables["OS/2"]
        capHeight = if (os2 != null && u16(os2) >= 2) i16(os2 + 88) else ascent
        val c = table("cmap")
        var found = -1
        for (i in 0 until u16(c + 2)) {
            val rec = c + 4 + i * 8
            val sub = c + u32(rec + 4).toInt()
            if (u16(sub) == 4 && (u16(rec) == 3 && u16(rec + 2) == 1 || u16(rec) == 0)) { found = sub; if (u16(rec) == 3) break }
        }
        if (found < 0) throw IllegalArgumentException("font has no Unicode character map")
        cmap = found
    }

    /** Glyph index for a Unicode character, 0 if the font has none. */
    fun glyph(ch: Int): Int {
        if (ch > 0xFFFF) return 0
        val segs = u16(cmap + 6) / 2
        val ends = cmap + 14; val starts = ends + segs * 2 + 2; val deltas = starts + segs * 2; val offsets = deltas + segs * 2
        for (s in 0 until segs) {
            if (u16(ends + s * 2) < ch) continue
            val start = u16(starts + s * 2)
            if (start > ch) return 0
            val ro = u16(offsets + s * 2)
            if (ro == 0) return (ch + u16(deltas + s * 2)) and 0xFFFF
            val g = u16(offsets + s * 2 + ro + (ch - start) * 2)
            return if (g == 0) 0 else (g + u16(deltas + s * 2)) and 0xFFFF
        }
        return 0
    }

    /** Advance width of a glyph in thousandths of the font size. */
    fun width(glyph: Int): Int {
        val hmtx = tables.getValue("hmtx")
        return u16(hmtx + minOf(glyph, numHMetrics - 1) * 4) * 1000 / unitsPerEm
    }

    fun scaled(v: Int) = v * 1000 / unitsPerEm
}

/**
 * Minimal writer for text-only PDF/A-2b documents: one embedded TrueType font, device-independent
 * colour, XMP metadata, no transparency, no encryption. Output depends only on the input, so
 * the same report always produces the same bytes.
 */
class PdfA(fontBytes: ByteArray, private val fontName: String, private val title: String, private val lang: String, private val createdIso: String, private val producer: String) {
    private val font = TrueTypeFont(fontBytes)
    private val pages = ArrayList<StringBuilder>()
    private val used = java.util.TreeMap<Int, Int>()   // glyph -> unicode
    private var page = StringBuilder()
    private val width = 595f
    private val height = 842f
    private val margin = 50f
    /** Distance of the next line from the top of the page. */
    var y = margin

    init { newPage() }

    fun newPage() {
        page = StringBuilder("/CS0 cs /CS0 CS\n")
        pages.add(page)
        y = margin
    }

    private fun glyphs(s: String): IntArray = IntArray(s.length) { i ->
        var g = font.glyph(s[i].code)
        var ch = s[i].code
        if (g == 0) { ch = '?'.code; g = font.glyph(ch) }
        used[g] = ch
        g
    }

    private fun measure(s: String, size: Float): Float { var w = 0L; for (c in s) { val g = font.glyph(c.code).let { if (it == 0) font.glyph('?'.code) else it }; w += font.width(g) }; return w * size / 1000f }

    /** Breaks [s] into lines no wider than the text area; words longer than a line are split. */
    private fun wrap(s: String, size: Float): List<String> {
        val max = width - 2 * margin
        val lines = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                var w = word
                while (measure(w, size) > max) {
                    if (line.isNotEmpty()) { lines.add(line); line = "" }
                    var n = w.length
                    while (n > 1 && measure(w.substring(0, n), size) > max) n--
                    lines.add(w.substring(0, n)); w = w.substring(n)
                }
                val candidate = if (line.isEmpty()) w else "$line $w"
                if (measure(candidate, size) > max) { lines.add(line); line = w } else line = candidate
            }
            lines.add(line)
        }
        return lines
    }

    /** [gray] 0 = black … 1 = white. [bold] thickens the strokes of the single embedded font. */
    fun text(s: String, size: Float = 10f, bold: Boolean = false, gray: Float = 0f, after: Float = 5f) {
        val leading = size * 1.35f
        for (line in wrap(s, size)) {
            if (y + leading > height - margin) newPage()
            val baseline = height - y - size
            page.append("BT /F1 ").append(num(size)).append(" Tf ").append(num(gray)).append(' ').append(num(gray)).append(' ').append(num(gray)).append(" sc ")
            if (bold) page.append(num(gray)).append(' ').append(num(gray)).append(' ').append(num(gray)).append(" SC 2 Tr ").append(num(size * 0.03f)).append(" w ") else page.append("0 Tr ")
            page.append(num(margin)).append(' ').append(num(baseline)).append(" Td <")
            for (g in glyphs(line)) page.append("%04X".format(g))
            page.append("> Tj ET\n")
            y += leading
        }
        y += after
    }

    private fun num(f: Float): String = if (f == f.toLong().toFloat()) f.toLong().toString() else "%.3f".format(java.util.Locale.ROOT, f)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun deflate(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> DeflaterOutputStream(o, Deflater(9)).use { it.write(b) } }.toByteArray()

    fun writeTo(out: OutputStream) {
        val objects = ArrayList<ByteArray>()
        fun add(body: ByteArray): Int { objects.add(body); return objects.size }
        fun add(s: String) = add(s.toByteArray(Charsets.ISO_8859_1))
        fun stream(dict: String, data: ByteArray): Int = add("<< $dict /Length ${data.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1) + data + "\nendstream".toByteArray())

        // Object numbers are fixed up front so objects can refer to each other.
        val catalog = 1; val pagesObj = 2; val type0 = 3; val cidFont = 4; val descriptor = 5; val fontFile = 6; val toUnicode = 7; val metadata = 8
        val firstPage = 9
        val kids = pages.indices.joinToString(" ") { "${firstPage + it * 2} 0 R" }
        add("<< /Type /Catalog /Pages $pagesObj 0 R /Metadata $metadata 0 R /Lang ($lang) >>")
        add("<< /Type /Pages /Count ${pages.size} /Kids [$kids] >>")
        add("<< /Type /Font /Subtype /Type0 /BaseFont /$fontName /Encoding /Identity-H /DescendantFonts [$cidFont 0 R] /ToUnicode $toUnicode 0 R >>")
        val widths = used.keys.joinToString(" ") { "$it [${font.width(it)}]" }
        add("<< /Type /Font /Subtype /CIDFontType2 /BaseFont /$fontName /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> /FontDescriptor $descriptor 0 R /DW 1000 /W [$widths] /CIDToGIDMap /Identity >>")
        add("<< /Type /FontDescriptor /FontName /$fontName /Flags 32 /FontBBox [${font.bbox.joinToString(" ") { font.scaled(it).toString() }}] /ItalicAngle 0 /Ascent ${font.scaled(font.ascent)} /Descent ${font.scaled(font.descent)} /CapHeight ${font.scaled(font.capHeight)} /StemV 80 /FontFile2 $fontFile 0 R >>")
        stream("/Filter /FlateDecode /Length1 ${font.data.size}", deflate(font.data))
        val map = StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
        for (chunk in used.entries.chunked(100)) {
            map.append(chunk.size).append(" beginbfchar\n")
            for ((g, u) in chunk) map.append("<%04X> <%04X>\n".format(g, u))
            map.append("endbfchar\n")
        }
        map.append("endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend")
        stream("/Filter /FlateDecode", deflate(map.toString().toByteArray()))
        val xmp = """<?xpacket begin="${'\uFEFF'}" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about="" xmlns:pdfaid="http://www.aiim.org/pdfa/ns/id/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:xmp="http://ns.adobe.com/xap/1.0/" xmlns:pdf="http://ns.adobe.com/pdf/1.3/">
   <pdfaid:part>2</pdfaid:part>
   <pdfaid:conformance>B</pdfaid:conformance>
   <dc:format>application/pdf</dc:format>
   <dc:title><rdf:Alt><rdf:li xml:lang="x-default">${esc(title)}</rdf:li></rdf:Alt></dc:title>
   <xmp:CreateDate>$createdIso</xmp:CreateDate>
   <xmp:ModifyDate>$createdIso</xmp:ModifyDate>
   <xmp:CreatorTool>${esc(producer)}</xmp:CreatorTool>
   <pdf:Producer>${esc(producer)}</pdf:Producer>
  </rdf:Description>
 </rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>""".toByteArray(Charsets.UTF_8)
        stream("/Type /Metadata /Subtype /XML", xmp)
        // sRGB primaries and white point as a calibrated (device-independent) colour space.
        val resources = "<< /Font << /F1 $type0 0 R >> /ColorSpace << /CS0 [/CalRGB << /WhitePoint [0.9505 1 1.089] /Gamma [2.2 2.2 2.2] /Matrix [0.4124 0.2126 0.0193 0.3576 0.7152 0.1192 0.1805 0.0722 0.9505] >>] >> >>"
        for ((i, p) in pages.withIndex()) {
            add("<< /Type /Page /Parent $pagesObj 0 R /MediaBox [0 0 595 842] /Resources $resources /Contents ${firstPage + i * 2 + 1} 0 R >>")
            stream("/Filter /FlateDecode", deflate(p.toString().toByteArray(Charsets.ISO_8859_1)))
        }

        val file = ByteArrayOutputStream()
        file.write("%PDF-1.7\n%".toByteArray()); file.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte())); file.write('\n'.code)
        val offsets = IntArray(objects.size)
        for ((i, o) in objects.withIndex()) {
            offsets[i] = file.size()
            file.write("${i + 1} 0 obj\n".toByteArray()); file.write(o); file.write("\nendobj\n".toByteArray())
        }
        val xref = file.size()
        val id = sha256(file.toByteArray()).hex().take(32)
        val tail = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) tail.append("%010d 00000 n \n".format(o))
        tail.append("trailer\n<< /Size ${objects.size + 1} /Root $catalog 0 R /ID [<$id> <$id>] >>\nstartxref\n$xref\n%%EOF\n")
        file.write(tail.toString().toByteArray())
        out.write(file.toByteArray())
    }
}
