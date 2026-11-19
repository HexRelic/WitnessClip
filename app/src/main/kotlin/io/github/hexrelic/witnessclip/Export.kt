// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import evidence.core.Asic
import evidence.core.Bundle
import evidence.core.Exporter
import evidence.core.Report
import evidence.core.Status
import evidence.core.Verifier
import evidence.core.hex
import evidence.core.pdf.PdfA
import evidence.core.shortDeviceId
import evidence.core.unb64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A mark, note or interruption, read back from the sealed log. */
class Moment(val type: String, val positionMs: Long, val text: String)

fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60) }

object Export {
    const val PART_BYTES = 25L * 1024 * 1024

    fun baseName(meta: Meta) = "recording-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(meta.started))

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        file.inputStream().use { while (true) { val n = it.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().hex()
    }

    fun verify(file: File): Report = BufferedInputStream(file.inputStream(), 1 shl 16).use { Verifier.verify(it) }

    fun moments(m: evidence.core.Manifest): List<Moment> = m.entries.filter { it.k == "e" }.mapNotNull { e ->
        try {
            val o = Json.parseToJsonElement(e.j).jsonObject
            val type = o.getValue("type").jsonPrimitive.content
            if (type !in setOf("mark", "note", "gap", "resumed", "interrupted", "route", "location", "location-unavailable")) null
            else Moment(type, (o["pos"]?.jsonPrimitive?.long ?: 0) * 1000 / m.audio.sampleRate,
                if (type == "location") "${o["lat"]?.jsonPrimitive?.content}, ${o["lon"]?.jsonPrimitive?.content}" + (o["acc"]?.jsonPrimitive?.content?.takeIf { it != "-1" }?.let { " (±$it m)" } ?: "")
                else if (type == "location-unavailable") o["reason"]?.jsonPrimitive?.content ?: ""
                else o["text"]?.jsonPrimitive?.content ?: "")
        } catch (x: Exception) { null }
    }

    /** Creates a file in the folder the user chose in Settings and lets [write] fill it. */
    fun writeToFolder(ctx: Context, tree: Uri, name: String, mime: String, write: (OutputStream) -> Unit) {
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = DocumentsContract.createDocument(ctx.contentResolver, folder, mime, name) ?: throw java.io.IOException("cannot create $name")
        ctx.contentResolver.openOutputStream(doc)!!.use(write)
    }

    fun folderName(tree: String): String = try { DocumentsContract.getTreeDocumentId(Uri.parse(tree)).substringAfter(':').ifEmpty { "/" } } catch (e: Exception) { "" }

    /** Copies the self-proving .flac to a place the user picked (phone storage, SD card, USB). */
    fun saveFlac(ctx: Context, id: String, to: Uri) {
        val app = ctx.applicationContext as App
        ctx.contentResolver.openOutputStream(to)!!.use { o -> app.store.flac(id).inputStream().use { it.copyTo(o, 1 shl 16) } }
    }

    /** Builds the ASiC-E court package in the cache and returns it. */
    fun buildPackage(ctx: Context, id: String): File {
        val app = ctx.applicationContext as App
        val meta = app.store.get(id)!!
        val flac = app.store.flac(id)
        val bundle = Exporter.readBundle(flac)
        val report = verify(flac)
        val pdf = ByteArrayOutputStream().also { ReportPdf.write(meta, bundle, report, sha256(flac), flac.length(), it) }.toByteArray()
        val items = ArrayList<Asic.Item>()
        items += Asic.Item("recording.flac", "audio/flac", flac)
        items += Asic.Item("relazione.pdf", "application/pdf", bytes = pdf)
        items += Asic.Item("VERIFY.txt", "text/plain", bytes = VERIFY_TXT.toByteArray())
        items += Asic.Item("evidence/evidence.json", "application/json", bytes = bundle.manifestBytes)
        items += Asic.Item("evidence/evidence.sig", "application/octet-stream", bytes = bundle.signature)
        bundle.manifest.open?.let { items += Asic.Item("evidence/open.tsr", "application/timestamp-reply", bytes = it.token.unb64()) }
        bundle.closeTokens.forEachIndexed { i, t -> items += Asic.Item("evidence/close-${i + 1}.tsr", "application/timestamp-reply", bytes = t.token.unb64()) }
        val dir = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, baseName(meta) + ".asice")
        out.outputStream().use { Asic.write(it, items, Keys.device()) }
        return out
    }

    /** Writes verifiable parts, the report and a checksum list into a folder the user picked. */
    fun saveSplit(ctx: Context, id: String, tree: Uri): Int {
        val app = ctx.applicationContext as App
        val meta = app.store.get(id)!!
        val flac = app.store.flac(id)
        val bundle = Exporter.readBundle(flac)
        val tmp = File(ctx.cacheDir, "export").apply { deleteRecursively(); mkdirs() }
        val parts = Exporter.split(flac, PART_BYTES, tmp, baseName(meta))
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        fun put(name: String, mime: String, write: (OutputStream) -> Unit) {
            val doc = DocumentsContract.createDocument(ctx.contentResolver, folder, mime, name) ?: throw java.io.IOException("cannot create $name")
            ctx.contentResolver.openOutputStream(doc)!!.use(write)
        }
        val sums = StringBuilder()
        val whole = sha256(flac)
        sums.append("# SHA-256. The first line is the complete, unsplit recording.\n$whole  ${baseName(meta)}.flac\n")
        for (p in parts) {
            sums.append("${sha256(p)}  ${p.name}\n")
            put(p.name, "audio/flac") { o -> p.inputStream().use { it.copyTo(o, 1 shl 16) } }
        }
        put(baseName(meta) + "-relazione.pdf", "application/pdf") { ReportPdf.write(meta, bundle, verify(flac), whole, flac.length(), it) }
        put(baseName(meta) + "-SHA256SUMS.txt", "text/plain") { it.write(sums.toString().toByteArray()) }
        put("VERIFY.txt", "text/plain") { it.write(VERIFY_TXT.toByteArray()) }
        tmp.deleteRecursively()
        return parts.size
    }

    val VERIFY_TXT = """
COME VERIFICARE QUESTA REGISTRAZIONE SENZA L'APP / HOW TO VERIFY THIS RECORDING WITHOUT THE APP
==============================================================================================

IT - La prova e' contenuta nel file .flac stesso (blocco di metadati APPLICATION "AEv1", un
archivio ZIP). Chiunque puo' controllarla con strumenti liberi e standard.

EN - The proof is stored inside the .flac file itself (APPLICATION metadata block "AEv1", a
ZIP archive). Anyone can check it with free, standard tools.

1. Integrita' del file / File integrity
     sha256sum recording.flac
   Confrontare con l'impronta nella relazione PDF. / Compare with the digest in the PDF report.

2. L'audio e' un FLAC valido / The audio is valid FLAC
     flac -t recording.flac        oppure / or        ffmpeg -v error -i recording.flac -f null -

3. Estrarre la prova / Extract the proof
     metaflac --block-type=APPLICATION --list recording.flac
   oppure usare i file nella cartella evidence/ del pacchetto .asice (che e' uno ZIP).
   or use the files in the evidence/ folder of the .asice package (which is a ZIP).

4. Marche temporali qualificate (RFC 3161) / Qualified timestamps (RFC 3161)
     openssl ts -reply -in evidence/close-1.tsr -token_in -text
     openssl ts -verify -data evidence/evidence.json -in evidence/close-1.tsr -token_in -CAfile <TSA chain>
   L'emittente va confrontato con l'Elenco di fiducia UE / Check the issuer against the EU Trusted List:
     https://eidas.ec.europa.eu/efda/trust-services/browse/eidas/tls

5. Firma del pacchetto / Package signature (ASiC-E, CAdES)
     openssl cms -verify -binary -inform DER -in META-INF/signature001.p7s \
       -content META-INF/ASiCManifest001.xml -noverify
   La catena di certificati e' l'attestazione hardware Android del dispositivo.
   The certificate chain is the device's Android hardware key attestation.

6. Audio contro sigillo / Audio against the seal
   evidence.json elenca, per ogni secondo, lo SHA-256 dei campioni PCM (24 bit little-endian,
   canali alternati se stereo). / evidence.json lists, for every second, the SHA-256 of the PCM
   samples (24-bit little-endian, channels interleaved if stereo).
     ffmpeg -i recording.flac -f s24le audio.pcm
   Ogni secondo occupa 144000 byte per canale (48000 campioni) e deve avere l'impronta della
   voce "a" corrispondente. / Each second takes 144000 bytes per channel (48000 samples) and
   must hash to the matching "a" entry.

7. Verifica completa automatica / Full automatic check
   Il verificatore a riga di comando, a codice pubblico, ripete tutti i controlli:
   The public-source command-line verifier repeats every check:
     verify recording.flac

Formato e codice sorgente / Format and source code: vedi il repository del progetto / see the project repository.
""".trimStart()
}

/** The bilingual technical report (relazione) that accompanies an exported recording. */
object ReportPdf {
    /** Thin layer so the report text below reads top to bottom. */
    private class Flow(val pdf: PdfA) {
        var y: Float
            get() = pdf.y
            set(v) { pdf.y = v }
        fun next() = pdf.newPage()
        @Suppress("UNUSED_PARAMETER")
        fun text(s: String, size: Float = 10f, bold: Boolean = false, mono: Boolean = false, color: Float = 0f, after: Float = 5f) =
            pdf.text(s, size, bold, color, after)
    }

    fun write(meta: Meta, bundle: Bundle, report: Report, fileSha: String, fileSize: Long, out: OutputStream) {
        // PDF/A-2b: the archival flavour of PDF, with the font embedded so it looks the same
        // everywhere and for as long as the file exists.
        val m = bundle.manifest
        val font = PdfA::class.java.getResourceAsStream("/font/LiberationSans-Regular.ttf")!!.use { it.readBytes() }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(Date())
        val pdf = PdfA(font, "LiberationSans", "Relazione tecnica su una registrazione audio / Technical report on an audio recording", "it-IT", created, "${m.app.pkg} ${m.app.versionName}")
        val f = Flow(pdf)
        val grey = 0.4f
        for (it in listOf(true, false)) {
            val fmt = SimpleDateFormat("d MMMM yyyy, HH:mm:ss zzz", if (it) Locale.ITALIAN else Locale.ENGLISH)
            fun t(en: String, ita: String) = if (it) ita else en
            f.text(t("Technical report on an audio recording", "Relazione tecnica su una registrazione audio"), 17f, bold = true, after = 2f)
            f.text(t("Generated automatically by ${m.app.pkg} ${m.app.versionName}. English version.", "Generata automaticamente da ${m.app.pkg} ${m.app.versionName}. Versione italiana."), 9f, color = grey, after = 12f)

            f.text(t("1. The recording", "1. La registrazione"), 12f, bold = true)
            f.text(t("Start (phone clock): ", "Inizio (orologio del telefono): ") + fmt.format(Date(meta.started)))
            f.text(t("Duration: ", "Durata: ") + clock(meta.durationMs) + "  ·  ${m.audio.sampleRate} Hz, ${m.audio.bits} bit, ${if (m.audio.channels == 1) "mono" else "stereo"}, FLAC " + t("(lossless)", "(senza perdita)"))
            if (m.audio.gainDb != 0) f.text(t("Fixed input gain: +${m.audio.gainDb} dB, constant for the whole recording (no automatic volume control).", "Guadagno fisso in ingresso: +${m.audio.gainDb} dB, costante per tutta la registrazione (nessun controllo automatico del volume)."))
            f.text(t("Audio source: ", "Sorgente audio: ") + m.audio.source + "  ·  " + t("input: ", "ingresso: ") + m.routes.joinToString { r -> r.name + if (r.product.isNotEmpty()) " (${r.product})" else "" })
            val places = Export.moments(m).filter { mo -> mo.type == "location" }
            val noPlace = Export.moments(m).firstOrNull { mo -> mo.type == "location-unavailable" }
            when {
                places.isNotEmpty() -> {
                    f.text(t("Location declared by the phone (not certified):", "Posizione dichiarata dal telefono (non certificata):"), after = 0f)
                    places.forEachIndexed { i, mo ->
                        val label = if (i == 0) t("at the start", "all'inizio") else if (i == places.lastIndex) t("at the end", "alla fine") else clock(mo.positionMs)
                        f.text("   $label: ${mo.text}  ·  " + t("latitude, longitude (WGS 84)", "latitudine, longitudine (WGS 84)"), after = if (i == places.lastIndex) 5f else 0f)
                    }
                }
                noPlace != null -> f.text(t("Location: switched on, but the phone gave no position", "Posizione: attiva, ma il telefono non ha fornito alcuna posizione") + " (${noPlace.text}).")
            }
            f.text(t("File size: ", "Dimensione del file: ") + "$fileSize byte")
            f.text("SHA-256: ", bold = true, after = 0f)
            f.text(fileSha, 9f, mono = true)
            f.text(t("Sealed root (Merkle, RFC 6962): ", "Radice del sigillo (Merkle, RFC 6962): "), bold = true, after = 0f)
            f.text(m.root, 9f, mono = true, after = 12f)

            f.text(t("2. The device", "2. Il dispositivo"), 12f, bold = true)
            f.text(t("Device ID (short form): ", "ID dispositivo (forma breve): ") + shortDeviceId(m.keys.deviceId), bold = true, after = 2f)
            f.text(t("Complete Device ID (SHA-256, 64 hexadecimal characters):", "ID dispositivo completo (SHA-256, 64 caratteri esadecimali):"), after = 0f)
            f.text(m.keys.deviceId, 9f, mono = true)
            f.text("${m.device.manufacturer} ${m.device.model}  ·  Android ${m.device.android} (SDK ${m.device.sdk}), " + t("security patch ", "patch di sicurezza ") + m.device.patch)
            f.text(t(
                "The Device ID is the SHA-256 of a public key whose private half was generated inside the phone's secure hardware and cannot be extracted. It identifies one installation on one physical device and contains no personal data.",
                "L'ID dispositivo è lo SHA-256 di una chiave pubblica la cui metà privata è stata generata nell'hardware sicuro del telefono e non può esserne estratta. Identifica una installazione su un dispositivo fisico e non contiene dati personali."), after = 12f)

            f.text(t("3. Certified time", "3. Data e ora certe"), 12f, bold = true)
            f.text(t("Opening timestamp (obtained in the first moments of the recording): ", "Marca temporale di apertura (ottenuta nei primi istanti della registrazione): ") + (report.openTime?.let(fmt::format) ?: t("no opening timestamp (recorded offline); the start time is declared by the phone", "nessuna marca temporale di apertura (registrata offline); l'ora d'inizio è dichiarata dal telefono")))
            if (m.mid.isNotEmpty()) f.text(t("Intermediate timestamps taken while recording: ", "Marche temporali intermedie durante la registrazione: ") + m.mid.size)
            f.text(t("Existed unchanged at: ", "Esistente e immutata al: ") + (report.closeTime?.let(fmt::format) ?: t("not yet timestamped", "non ancora marcata temporalmente")))
            f.text(t(
                "Timestamps are RFC 3161 tokens issued by trust service providers. A qualified electronic time stamp enjoys the presumption of the accuracy of the date and time it indicates and of the integrity of the data (Regulation (EU) 910/2014, art. 41). The issuer of each token is listed in the technical annex.",
                "Le marche temporali sono token RFC 3161 emessi da prestatori di servizi fiduciari. Una validazione temporale elettronica qualificata gode della presunzione di accuratezza della data e dell'ora che indica e di integrità dei dati (Regolamento (UE) 910/2014, art. 41). L'emittente di ogni token è indicato nell'allegato tecnico."), after = 12f)

            f.text(t("4. Verification results", "4. Esito delle verifiche"), 12f, bold = true)
            f.text(t("Performed automatically when this report was generated.", "Eseguite automaticamente al momento della generazione di questa relazione."), 9f, color = grey)
            val sum = Summary(report)
            val str = Str(it)
            for ((status, title, sub) in proofRows(str, sum, m.routes.any { r -> !r.builtIn })) {
                val label = when (status) { Status.OK -> str.statusOk; Status.FAIL -> str.statusFail; else -> str.statusNote }
                f.text("$label  ·  $title", 10.5f, bold = true, after = 1f)
                f.text(sub, 10f)
            }
            f.y += 7f

            var section = 5
            val moments = Export.moments(m).filter { mo -> mo.type != "resumed" && mo.type != "location-unavailable" }
            if (moments.isNotEmpty()) {
                f.text(t("$section. Marks, notes and events", "$section. Segni, note ed eventi"), 12f, bold = true)
                for (mo in moments) {
                    val what = when (mo.type) {
                        "mark" -> t("moment marked by the user", "momento segnato dall'utente")
                        "note" -> t("note: ", "nota: ") + mo.text
                        "route" -> t("microphone changed", "cambio di microfono")
                        "location" -> t("position declared by the phone (not certified): ", "posizione dichiarata dal telefono (non certificata): ") + mo.text
                        else -> t("interruption", "interruzione")
                    }
                    f.text(clock(mo.positionMs) + "   " + what)
                }
                f.y += 7f
                section++
            }

            f.text(t("$section. Independent verification", "$section. Verifica indipendente"), 12f, bold = true)
            f.text(t(
                "Every check above can be repeated by anyone, without this app, using free standard tools (sha256sum, ffmpeg, openssl). Step-by-step instructions are in the file VERIFY.txt delivered with the recording. The file format and the source code are public.",
                "Ogni verifica può essere ripetuta da chiunque, senza questa app, con strumenti standard gratuiti (sha256sum, ffmpeg, openssl). Le istruzioni passo passo sono nel file VERIFY.txt consegnato con la registrazione. Il formato del file e il codice sorgente sono pubblici."))
            f.next()
        }
        f.text("Allegato tecnico per il consulente / Technical annex for the expert", 13f, bold = true)
        f.text("Output of the automatic verifier (English). Each line can be reproduced with the public command-line verifier.", 9f, color = grey, after = 10f)
        for (c in report.shown) {
            f.text("[${c.status}] ${c.id}", 9.5f, bold = true, after = 0f)
            f.text(c.detail, 9f, mono = true)
        }
        pdf.writeTo(out)
    }
}
