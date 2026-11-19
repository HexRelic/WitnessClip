// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import evidence.core.Report
import evidence.core.Status
import evidence.core.Tsa
import evidence.core.Verifier
import evidence.core.shortDeviceId
import io.github.hexrelic.witnessclip.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.PushbackInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.zip.ZipInputStream

fun sizeText(bytes: Long) = if (bytes >= 1 shl 30) "%.1f GB".format(bytes / 1073741824.0) else "%.0f MB".format(bytes / 1048576.0)

/** Collapses the verifier's checks into the four plain-language statements the app shows. */
class Summary(private val report: Report) {
    private fun worst(vararg ids: String): Status = ids.mapNotNull { report.status(it) }.let { l ->
        when { Status.FAIL in l -> Status.FAIL; Status.WARN in l -> Status.WARN; l.isEmpty() -> Status.WARN; else -> Status.OK }
    }
    val audio = worst("bundle", "signature", "log", "audio")
    val time = worst("time-open", "time-close", "time-issuer")
    val unlistedIssuer = report.status("time-issuer") != null
    val timeClosed = report.closeTime != null
    val timeOpened = report.openTime != null
    val continuity = worst("continuity")
    val device = worst("device", "attestation")
    /** Hardware attestation positively confirmed an unmodified phone and the official app. */
    val attested = report.status("attestation") == Status.OK
    val failed = report.failed
    val perfect = !failed && listOf(audio, time, continuity, device).all { it == Status.OK }
}

/** The four plain-language statements (status, title, explanation) shown in the app and printed in the report. */
fun proofRows(s: Str, sum: Summary, externalMic: Boolean): List<Triple<Status, String, String>> {
    fun row(id: String, status: Status, override: String?) = s.check(id).let { (title, sub) -> Triple(status, title, override ?: sub) }
    val deviceKey = if (sum.attested) "device-attested" else "device"
    return listOf(
        row("audio", sum.audio, if (sum.audio == Status.FAIL) s.audioFail else null),
        row("time", sum.time, when { sum.time == Status.FAIL -> s.timeFail; sum.time == Status.OK -> null; sum.unlistedIssuer -> s.timeUnlisted; !sum.timeClosed -> s.timePendingSub; !sum.timeOpened -> s.timeOpenMissing; else -> null }),
        row("continuity", sum.continuity, if (sum.continuity == Status.WARN) s.continuityWarn else null),
        row(deviceKey, sum.device, when { sum.device == Status.FAIL -> s.deviceFail; externalMic -> s.check(deviceKey).second + " " + s.externalMic; else -> null }),
    )
}

@Composable
private fun StatusDot(status: Status) {
    val (bg, icon) = when (status) { Status.OK -> P.seal to Icons.check; Status.FAIL -> P.rec to Icons.cross; else -> P.warn to Icons.warn }
    Box(Modifier.size(26.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { Ic(icon, 15.dp, if (status == Status.OK) P.onSeal else Color.White, stroke = 3f) }
}

@Composable
private fun ProofRows(sum: Summary, externalMic: Boolean) {
    val s = S
    Card(padding = 0.dp) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            val rows = proofRows(s, sum, externalMic)
            rows.forEachIndexed { i, (status, title, sub) ->
                Row(Modifier.padding(vertical = 12.dp)) {
                    StatusDot(status)
                    Spacer(Modifier.width(12.dp))
                    Column { T(title, size = 14.5.sp, weight = FontWeight.SemiBold); T(sub, size = 13.sp, color = P.muted, lineHeight = 18.sp) }
                }
                if (i < rows.size - 1) Divider()
            }
        }
    }
}

@Composable
private fun VerdictCard(sum: Summary?, pending: Boolean, content: @Composable ColumnScope.() -> Unit = {}) {
    val s = S
    val accent = if (sum?.failed == true) P.rec else P.seal
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(if (sum?.failed == true) P.rec.copy(alpha = 0.15f) else P.sealDim, P.surface))).border(1.dp, accent, shape).padding(18.dp)) {
        when {
            sum == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), color = P.seal, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); T(s.verifying, size = 15.sp, weight = FontWeight.SemiBold)
            }
            sum.failed -> { Pill(s.levelFail, PillKind.FAIL); T(s.verdictFail, Modifier.padding(top = 8.dp), size = 20.sp, weight = FontWeight.Bold) }
            else -> {
                Pill(when { pending -> s.levelPending; sum.perfect && sum.attested -> s.levelHigh; sum.perfect -> s.levelSealed; else -> s.levelMid }, if (pending) PillKind.WAIT else PillKind.OK)
                T(s.verdictOk, Modifier.padding(top = 8.dp, bottom = 4.dp), size = 20.sp, weight = FontWeight.Bold)
                T(s.verdictOkSub, size = 13.sp, color = P.muted, lineHeight = 18.sp)
            }
        }
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val s = S
    val scope = rememberCoroutineScope()
    val items by app.store.items.collectAsStateWithLifecycle()
    val meta = items.firstOrNull { it.id == id }
    if (meta == null) { LaunchedEffect(Unit) { onBack() }; return }
    val file = remember(id) { app.store.flac(id) }

    val report by produceState<Report?>(null, id, meta.state) { value = null; value = withContext(Dispatchers.IO) { try { Export.verify(file) } catch (e: Exception) { null } } }
    val sum = report?.let { Summary(it) }
    val moments = remember(report) { report?.manifest?.let { Export.moments(it) } ?: emptyList() }
    var exportSheet by remember { mutableStateOf(false) }
    var technical by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val folder = app.prefs.saveFolder

    fun io(block: () -> Unit, done: String = s.saved) {
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { try { block(); true } catch (e: Exception) { android.util.Log.e("Detail", "export", e); false } }
            busy = false
            ctx.toast(if (ok) done else s.failed)
        }
    }
    val saveFlac = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/flac")) { uri -> if (uri != null) io({ Export.saveFlac(ctx, id, uri) }) }
    val savePackage = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.etsi.asic-e+zip")) { uri ->
        if (uri != null) io({ val f = Export.buildPackage(ctx, id); ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } } })
    }
    val saveSplit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) io({ Export.saveSplit(ctx, id, uri) }) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.back, onClick = onBack)
            T(meta.title.ifEmpty { SimpleDateFormat("EEEE d MMMM, HH:mm", s.locale).format(Date(meta.started)) }, Modifier.weight(1f).padding(start = 4.dp), size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
            IconButton(Icons.edit, tint = P.muted) { renaming = true }
            IconButton(Icons.trash, tint = P.muted) { confirmDelete = true }
        }
        VerdictCard(sum, meta.state == "pending")
        Spacer(Modifier.height(12.dp))
        Player(meta, file, moments)

        if (sum != null) {
            SectionTitle(s.proves)
            ProofRows(sum, !meta.builtInMic)
            T(s.honest, Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp), size = 11.5.sp, color = P.muted, lineHeight = 17.sp)
        }

        SectionTitle(s.details)
        Card(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                KeyValue(s.recorded, SimpleDateFormat("d MMM yyyy, HH:mm:ss", s.locale).format(Date(meta.started)))
                Divider(); KeyValue(s.duration, clock(meta.durationMs))
                Divider(); KeyValue(s.microphone, if (meta.builtInMic) s.micPhone else meta.mic)
                Divider(); KeyValue(s.channelsLabel, if (meta.channels == 2) "Stereo" else "Mono")
                Divider(); KeyValue(s.fileSize, sizeText(meta.size))
                val places = moments.filter { it.type == "location" }
                if (places.isNotEmpty() || moments.any { it.type == "location-unavailable" }) {
                    Divider()
                    Column(Modifier.padding(vertical = 12.dp)) {
                        T(s.position, size = 14.sp)
                        T(if (places.isEmpty()) s.positionNone else places.map { it.text }.distinct().joinToString("\n"), Modifier.padding(top = 4.dp), size = 13.sp, color = P.muted)
                    }
                }
                Divider()
                Column(Modifier.padding(vertical = 12.dp)) {
                    T(s.deviceId, size = 14.sp)
                    T(report?.manifest?.let { shortDeviceId(it.keys.deviceId) } ?: "…", Modifier.padding(top = 4.dp), size = 12.5.sp, color = P.muted, mono = true)
                }
            }
        }

        val shownMoments = moments.filter { it.type in setOf("mark", "note", "gap", "interrupted") }
        if (shownMoments.isNotEmpty()) {
            SectionTitle(s.notesMarks)
            Card(padding = 0.dp) {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    shownMoments.forEachIndexed { i, mo ->
                        Row(Modifier.padding(vertical = 11.dp)) {
                            T(clock(mo.positionMs), Modifier.width(52.dp), size = 13.sp, color = P.muted, mono = true)
                            Ic(when (mo.type) { "note" -> Icons.note; "mark" -> Icons.flag; else -> Icons.warn }, 18.dp, if (mo.type == "note") P.seal else P.warn)
                            Spacer(Modifier.width(10.dp))
                            T(when (mo.type) { "note" -> mo.text; "mark" -> s.markedMoment; else -> s.continuityWarn }, size = 14.sp, color = if (mo.type == "note") P.text else P.muted)
                        }
                        if (i < shownMoments.size - 1) Divider()
                    }
                }
            }
        }

        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton(s.saveToPhone, Modifier.weight(1f), Icons.download, enabled = !busy) {
                val name = Export.baseName(meta) + ".flac"
                if (folder.isEmpty()) saveFlac.launch(name)
                else io({ Export.writeToFolder(ctx, Uri.parse(folder), name, "audio/flac") { o -> file.inputStream().use { it.copyTo(o, 1 shl 16) } } }, s.savedIn(Export.folderName(folder)))
            }
            PrimaryButton(s.forCourt, Modifier.weight(1f), Icons.share, ghost = true, enabled = !busy) { exportSheet = true }
        }
        if (report != null) PrimaryButton(s.technical, Modifier.fillMaxWidth().padding(top = 10.dp), ghost = true) { technical = true }
    }

    if (busy) Box(Modifier.fillMaxSize().background(P.bg.copy(alpha = 0.85f)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator(color = P.seal); T(s.preparing, Modifier.padding(top = 16.dp), weight = FontWeight.SemiBold) }
    }

    if (exportSheet) {
        var split by remember { mutableStateOf(false) }
        ModalBottomSheet(onDismissRequest = { exportSheet = false }, containerColor = P.surface) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                T(s.exportTitle, size = 20.sp, weight = FontWeight.Bold)
                T(s.exportSub, Modifier.padding(top = 4.dp), size = 13.sp, color = P.muted, lineHeight = 18.sp)
                OptionRow(Icons.box, s.exFull, s.exFullSub + " · " + sizeText(meta.size), !split) { split = false }
                OptionRow(Icons.split, s.exSplit, s.exSplitSub, split) { split = true }
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) { Ic(Icons.check, 18.dp, P.seal); Spacer(Modifier.width(8.dp)); T(s.exPdf, size = 13.sp, color = P.muted) }
                Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton(s.saveToPhone, Modifier.weight(1f), Icons.download) {
                        exportSheet = false
                        val name = Export.baseName(meta) + ".asice"
                        when {
                            folder.isEmpty() -> if (split) saveSplit.launch(null) else savePackage.launch(name)
                            split -> io({ Export.saveSplit(ctx, id, Uri.parse(folder)) }, s.savedIn(Export.folderName(folder)))
                            else -> io({ val f = Export.buildPackage(ctx, id); Export.writeToFolder(ctx, Uri.parse(folder), name, "application/vnd.etsi.asic-e+zip") { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } } }, s.savedIn(Export.folderName(folder)))
                        }
                    }
                    if (!split) PrimaryButton(s.share, Modifier.weight(1f), Icons.share, ghost = true) {
                        exportSheet = false
                        busy = true
                        scope.launch {
                            val f = withContext(Dispatchers.IO) { try { Export.buildPackage(ctx, id) } catch (e: Exception) { null } }
                            busy = false
                            if (f == null) ctx.toast(s.failed) else {
                                val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/vnd.etsi.asic-e+zip").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                            }
                        }
                    }
                }
            }
        }
    }
    if (technical && report != null) InfoDialog(s.technical, report!!.shown.joinToString("\n\n") { "[${it.status}] ${it.id}\n${it.detail}" }, s.close) { technical = false }
    if (renaming) {
        var text by remember { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renaming = false }, containerColor = P.surface,
            title = { T(s.rename, size = 19.sp, weight = FontWeight.Bold) },
            text = { Column {
                OutlinedTextField(text, { text = it.take(80) }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { T(s.renameHint, color = P.muted) }, shape = RoundedCornerShape(14.dp))
                T(s.renameSub, Modifier.padding(top = 10.dp), size = 12.sp, color = P.muted, lineHeight = 17.sp)
            } },
            confirmButton = { TextButton(onClick = { app.store.save(meta.copy(title = text.trim())); renaming = false }) { T(s.save, color = P.seal, weight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { T(s.cancel, color = P.muted) } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = P.surface,
        title = { T(s.delete, size = 19.sp, weight = FontWeight.Bold) }, text = { T(s.deleteAsk) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Keys.delete(Keys.sessionAlias(id)); app.store.delete(id) }) { T(s.delete, color = P.rec, weight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { T(s.cancel, color = P.muted) } },
    )
}

@Composable
private fun KeyValue(k: String, v: String, mono: Boolean = false) = Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    T(k, size = 14.sp); Spacer(Modifier.width(12.dp)); T(v, size = 14.sp, color = P.muted, mono = mono, align = TextAlign.End, maxLines = 1)
}

/** Read-only playback. There is deliberately nothing here that can change the audio. */
@Composable
private fun Player(meta: Meta, file: java.io.File, moments: List<Moment>) {
    val player = remember(file) { try { MediaPlayer().apply { setDataSource(file.path); prepare() } } catch (e: Exception) { null } }
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableIntStateOf(0) }
    val total = (player?.duration ?: meta.durationMs.toInt()).coerceAtLeast(1)
    DisposableEffect(player) {
        player?.setOnCompletionListener { playing = false; pos = 0; it.seekTo(0) }
        onDispose { player?.release() }
    }
    LaunchedEffect(playing) { while (playing) { pos = player?.currentPosition ?: 0; delay(100) } }

    val peaks = remember(meta.peaks) { try { Base64.decode(meta.peaks, Base64.DEFAULT) } catch (e: Exception) { ByteArray(0) } }
    val bars = 56
    val heights = remember(peaks) {
        FloatArray(bars) { b ->
            if (peaks.isEmpty()) 0.2f else {
                val from = b * peaks.size / bars
                val to = maxOf(from + 1, (b + 1) * peaks.size / bars).coerceAtMost(peaks.size)
                var mx = 0; for (i in from until to) mx = maxOf(mx, peaks[i].toInt() and 0xff)
                // perceptual scale so quiet speech is still visible
                (0.12f + 0.88f * kotlin.math.sqrt(mx / 255f)).coerceIn(0.12f, 1f)
            }
        }
    }
    val seal = P.seal; val muted = P.muted; val warn = P.warn
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).clip(CircleShape).background(P.text).clickable(enabled = player != null) {
                if (playing) player?.pause() else player?.start(); playing = !playing
            }, contentAlignment = Alignment.Center) { Ic(if (playing) Icons.pause else Icons.play, tint = P.bg, filled = !playing, stroke = 2.5f) }
            Spacer(Modifier.width(12.dp))
            Canvas(Modifier.weight(1f).height(60.dp).pointerInput(player) {
                detectTapGestures { o -> val to = (o.x / size.width * total).toInt().coerceIn(0, total); player?.seekTo(to); pos = to }
            }) {
                val step = size.width / bars
                val top = 10.dp.toPx()
                val h = size.height - top
                val played = pos.toFloat() / total * bars
                for (b in 0 until bars) {
                    val bh = heights[b] * h
                    drawRoundRect(if (b < played) seal else muted, Offset(b * step + step * 0.15f, top + (h - bh) / 2), Size(step * 0.7f, bh), CornerRadius(step * 0.3f), alpha = if (b < played) 1f else 0.45f)
                }
                for (mo in moments) if (mo.type == "mark" || mo.type == "note")
                    drawCircle(warn, 3.5.dp.toPx(), Offset((mo.positionMs.toFloat() / total * size.width).coerceIn(4f, size.width - 4f), 4.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(clock(pos.toLong()), size = 13.sp, color = P.muted)
            if (meta.marks + meta.notes > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(P.warn)); Spacer(Modifier.width(6.dp)); T(S.markLegend, size = 13.sp, color = P.muted)
            }
            T(clock(meta.durationMs), size = 13.sp, color = P.muted)
        }
    }
}

@Composable
fun VerifyScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val s = S
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<Report?>(null) }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            busy = true; report = null
            name = uri.lastPathSegment?.substringAfterLast('/') ?: ""
            scope.launch {
                report = withContext(Dispatchers.IO) {
                    try {
                        ctx.contentResolver.openInputStream(uri)!!.use { raw ->
                            val input = PushbackInputStream(BufferedInputStream(raw, 1 shl 16), 2)
                            val magic = ByteArray(2); val n = input.read(magic); if (n > 0) input.unread(magic, 0, n)
                            if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
                                // a .asice / .zip package: check the recording inside it
                                val zip = ZipInputStream(input)
                                var r: Report? = null
                                while (true) { val e = zip.nextEntry ?: break; if (e.name.endsWith(".flac")) { r = Verifier.verify(zip); break } }
                                r ?: Verifier.verify(ByteArray(0).inputStream())
                            } else Verifier.verify(input)
                        }
                    } catch (e: Exception) { Verifier.verify(ByteArray(0).inputStream()) }
                }
                busy = false
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(Icons.back, onClick = onBack)
            T(s.verifyTitle, Modifier.padding(start = 4.dp), size = 17.sp, weight = FontWeight.SemiBold)
        }
        T(s.verifyIntro, size = 13.sp, color = P.muted, lineHeight = 19.sp)
        val shape = RoundedCornerShape(22.dp)
        Column(Modifier.padding(top = 12.dp).fillMaxWidth().clip(shape).border(2.dp, P.line, shape).clickable(enabled = !busy) { pick.launch(arrayOf("*/*")) }.padding(vertical = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) CircularProgressIndicator(color = P.seal) else Ic(Icons.upload, 36.dp, P.seal)
            T(if (busy) s.verifying else s.chooseFile, Modifier.padding(top = 10.dp), size = 15.sp, weight = FontWeight.SemiBold)
            T(".flac · .asice", Modifier.padding(top = 4.dp), size = 13.sp, color = P.muted)
        }
        report?.let { r ->
            val sum = Summary(r)
            Spacer(Modifier.height(16.dp))
            VerdictCard(sum, pending = !sum.failed && !sum.timeClosed) {
                val m = r.manifest
                if (m != null) T("$name · ${clock((r.audioSeconds * 1000).toLong())} · ${s.fromDevice} ${shortDeviceId(m.keys.deviceId)}", Modifier.padding(top = 8.dp), size = 12.5.sp, color = P.muted)
            }
            Spacer(Modifier.height(12.dp))
            ProofRows(sum, r.manifest?.routes?.any { !it.builtIn } == true)
            SectionTitle(s.technical)
            Card { for (c in r.shown) T("[${c.status}] ${c.id}: ${c.detail}", Modifier.padding(vertical = 4.dp), size = 12.sp, color = P.muted, lineHeight = 17.sp) }
        }
        T(s.verifyFoot, Modifier.padding(top = 18.dp), size = 11.5.sp, color = P.muted, lineHeight = 17.sp)
    }
}

@Composable
fun DashboardScreen(onOpen: (String) -> Unit) {
    val app = App.instance
    val s = S
    val all by app.store.items.collectAsStateWithLifecycle()
    val items = all.filter { it.state != "recording" }
    val sealed = items.count { it.state == "sealed" }
    val totalMin = items.sumOf { it.durationMs } / 60000
    val used = remember(items) { items.sumOf { it.size } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        T(s.dashboard, Modifier.padding(top = 14.dp, bottom = 14.dp), size = 28.sp, weight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(s.recordings, "${items.size}", Modifier.weight(1f)); Tile(s.totalTime, if (totalMin >= 60) "${totalMin / 60}h ${totalMin % 60}m" else "${totalMin}m", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile(s.sealed, "$sealed / ${items.size}", Modifier.weight(1f)); Tile(s.spaceUsed, sizeText(used), Modifier.weight(1f))
        }

        // minutes per day, last 7 days
        val days = remember(items) {
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val today = cal.timeInMillis
            (6 downTo 0).map { back ->
                val from = today - back * 86400000L
                Pair(from, items.filter { it.started >= from && it.started < from + 86400000L }.sumOf { it.durationMs } / 60000f)
            }
        }
        val maxMin = (days.maxOfOrNull { it.second } ?: 0f).coerceAtLeast(1f)
        Card(Modifier.padding(top = 10.dp)) {
            Row { T(s.last7, Modifier.weight(1f), size = 15.sp, weight = FontWeight.SemiBold); T("%.0f min".format(days.sumOf { it.second.toDouble() }), size = 13.sp, color = P.muted) }
            Row(Modifier.fillMaxWidth().height(128.dp).padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
                days.forEachIndexed { i, (day, min) ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        if (min >= 0.5f) T("%.0f".format(min), size = 11.sp, color = P.muted)
                        Spacer(Modifier.height(4.dp))
                        Box(Modifier.fillMaxWidth().height((4 + 70 * min / maxMin).dp).clip(RoundedCornerShape(8.dp, 8.dp, 4.dp, 4.dp)).background(P.seal.copy(alpha = if (i == 6) 1f else 0.35f)))
                        Spacer(Modifier.height(6.dp))
                        T(SimpleDateFormat("EEEEE", s.locale).format(Date(day)).uppercase(), size = 11.sp, color = P.muted)
                    }
                }
            }
        }

        Card(Modifier.padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val frac = if (items.isEmpty()) 1f else sealed.toFloat() / items.size
                val seal = P.seal; val warn = P.warn
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        val w = 13.dp.toPx()
                        val inset = Offset(w / 2, w / 2); val sz = Size(size.width - w, size.height - w)
                        drawArc(warn, -90f, 360f, false, inset, sz, style = Stroke(w))
                        drawArc(seal, -90f, 360f * frac, false, inset, sz, style = Stroke(w))
                    }
                    T("%.0f%%".format(frac * 100), size = 15.sp, weight = FontWeight.Bold)
                }
                Spacer(Modifier.width(18.dp))
                Column {
                    T(s.protection, Modifier.padding(bottom = 8.dp), size = 15.sp, weight = FontWeight.SemiBold)
                    Legend(P.seal, s.fullySealed(sealed)); Spacer(Modifier.height(4.dp)); Legend(P.warn, s.waitingNet(items.size - sealed))
                }
            }
        }

        SectionTitle(s.allRecordings)
        if (items.isEmpty()) T(s.empty, Modifier.padding(vertical = 12.dp), color = P.muted)
        for (m in items) RecordingRow(m) { onOpen(m.id) }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Legend(color: Color, text: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(9.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp)); T(text, size = 13.sp)
}

@Composable
private fun Tile(label: String, value: String, modifier: Modifier) = Column(modifier.clip(RoundedCornerShape(20.dp)).background(P.surface).border(1.dp, P.line, RoundedCornerShape(20.dp)).padding(14.dp)) {
    T(label, size = 13.sp, color = P.muted)
    T(value, Modifier.padding(top = 6.dp), size = 25.sp, weight = FontWeight.Bold, maxLines = 1)
}

@Composable
private fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) =
    Row(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(P.surface2).padding(3.dp)) {
        for ((value, label) in options) {
            val on = value == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) P.surface else Color.Transparent).clickable { onSelect(value) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                T(label, size = 13.sp, color = if (on) P.text else P.muted, weight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }

@Composable
private fun SettingRow(title: String, sub: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { T(title, size = 14.5.sp, weight = FontWeight.SemiBold); if (sub != null) T(sub, size = 13.sp, color = P.muted, lineHeight = 18.sp) }
        Spacer(Modifier.width(12.dp))
        trailing()
    }

@Composable
private fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) = Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = P.seal, checkedThumbColor = P.onSeal, uncheckedTrackColor = P.surface2, uncheckedThumbColor = P.muted, uncheckedBorderColor = P.line))

@Composable
fun SettingsScreen(activity: MainActivity, onDoc: (Doc) -> Unit, onMic: () -> Unit) {
    val app = App.instance
    val prefs = app.prefs
    val ctx = LocalContext.current
    val s = S
    var info by remember { mutableStateOf<Pair<String, String>?>(null) }
    val deviceId by produceState("") { value = withContext(Dispatchers.IO) { try { shortDeviceId(Keys.deviceId()) } catch (e: Exception) { "—" } } }
    val unrestricted = remember(activity.resumed.intValue) { batteryUnrestricted(ctx) }
    val guide = remember(s.italian) { batteryGuide(s) }
    var showGuide by remember { mutableStateOf(false) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) prefs.location(true) else ctx.toast(s.locationDenied)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Keep access to the chosen folder across restarts.
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            prefs.saveFolder(uri.toString())
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        T(s.settings, Modifier.padding(top = 14.dp, bottom = 14.dp), size = 28.sp, weight = FontWeight.Bold)
        val shape = RoundedCornerShape(22.dp)
        Column(Modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(P.sealDim, P.surface))).border(1.dp, P.seal, shape).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ic(Icons.key, 18.dp, P.seal); Spacer(Modifier.width(10.dp))
                T(s.yourDeviceId, Modifier.weight(1f), size = 14.5.sp, weight = FontWeight.SemiBold)
                IconButton(Icons.copy, size = 34.dp) {
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Device ID", deviceId)); ctx.toast(s.copied)
                }
            }
            T(deviceId.ifEmpty { "…" }, Modifier.padding(vertical = 8.dp), size = 15.sp, weight = FontWeight.Bold, mono = true, lineHeight = 22.sp)
            T(s.deviceIdSub, size = 11.5.sp, color = P.muted, lineHeight = 17.sp)
        }

        SectionTitle(s.appearance)
        Card {
            T(s.theme, size = 14.5.sp, weight = FontWeight.SemiBold)
            Segmented(listOf("system" to s.system, "light" to s.light, "dark" to s.dark), prefs.theme) { prefs.theme(it) }
            T(s.language, Modifier.padding(top = 16.dp), size = 14.5.sp, weight = FontWeight.SemiBold)
            Segmented(listOf("system" to s.system, "en" to "English", "it" to "Italiano"), prefs.lang) { prefs.lang(it) }
        }

        SectionTitle(s.recording)
        Card(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(s.uninterrupted, if (unrestricted) s.uninterruptedOn else s.uninterruptedOff, if (unrestricted) null else ({ askBatteryExemption(ctx) })) {
                    if (unrestricted) Pill(s.active, PillKind.OK) else Ic(Icons.warn, tint = P.warn)
                }
                Divider()
                SettingRow(s.microphone, micLabel(s, prefs.mic), onMic) { Ic(Icons.chevron, tint = P.muted) }
                Divider()
                SettingRow(s.liveWave, s.liveWaveSub) { Toggle(prefs.waveform) { prefs.waveform(it) } }
                Divider()
                SettingRow(s.keepOn, s.keepOnSub) { Toggle(prefs.keepScreenOn) { prefs.keepScreenOn(it) } }
                Divider()
                SettingRow(s.discreet, s.discreetSub) { Toggle(prefs.discreet) { prefs.discreet(it) } }
                Divider()
                SettingRow(s.stereo, s.stereoSub) { Toggle(prefs.stereo) { prefs.stereo(it) } }
                Divider()
                SettingRow(s.location, s.locationSub) {
                    Toggle(prefs.location) { on ->
                        if (!on) prefs.location(false)
                        else if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) prefs.location(true)
                        else askLocation.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
                    }
                }
                Divider()
                if (guide != null) { SettingRow(s.batteryGuide, guide.first, { showGuide = true }) { Ic(Icons.chevron, tint = P.muted) }; Divider() }
                SettingRow(s.quality, s.qualitySub) { Ic(Icons.lock, 18.dp, P.muted) }
            }
        }

        SectionTitle(s.security)
        Card(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(s.appLock, s.appLockSub) {
                    Toggle(prefs.appLock) { on ->
                        if (on && !ctx.getSystemService(android.app.KeyguardManager::class.java).isDeviceSecure) ctx.toast(s.appLockNeedsPin) else prefs.appLock(on)
                    }
                }
                Divider()
                SettingRow(s.saveFolder, if (prefs.saveFolder.isEmpty()) s.askEveryTime else Export.folderName(prefs.saveFolder), { pickFolder.launch(null) }) {
                    if (prefs.saveFolder.isEmpty()) Ic(Icons.folder, tint = P.muted) else IconButton(Icons.cross, tint = P.muted, size = 34.dp) { prefs.saveFolder("") }
                }
                Divider()
                SettingRow(s.certTime, s.certTimeSub, { info = s.certTime to (s.certTimeBody + "\n" + Tsa.DEFAULTS.joinToString("\n") { "• ${it.name}" }) }) { Ic(Icons.chevron, tint = P.muted) }
            }
        }

        SectionTitle(s.about)
        Card(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                SettingRow(s.howSeal, onClick = { info = s.howSeal to s.howSealBody }) { Ic(Icons.chevron, tint = P.muted) }
            }
        }

        SectionTitle(s.legal)
        Card(padding = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                val docs = listOf(Doc.TERMS, Doc.PRIVACY, Doc.LICENCE, Doc.THIRD_PARTY, Doc.AI_USE)
                docs.forEachIndexed { i, d ->
                    SettingRow(Legal.title(d, s.italian), onClick = { onDoc(d) }) { Ic(Icons.chevron, tint = P.muted) }
                    if (i < docs.size - 1) Divider()
                }
            }
        }
        T("${s.appName} ${BuildConfig.VERSION_NAME}", Modifier.fillMaxWidth().padding(vertical = 20.dp), size = 12.sp, color = P.muted, align = TextAlign.Center)
    }
    info?.let { (title, body) -> InfoDialog(title, body, s.close) { info = null } }
    if (showGuide && guide != null) BatteryGuideDialog(guide.first, guide.second) { showGuide = false }
}
