// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hexrelic.witnessclip.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    /** Bumped on every resume so screens re-read system state (battery exemption, microphones). */
    val resumed = mutableIntStateOf(0)
    /** True while the app lock is engaged and the content must stay hidden. */
    val locked = mutableStateOf(false)
    private val confirmCredential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { if (it.resultCode == RESULT_OK) unlocked() }

    private fun unlocked() { unlockedInProcess = true; locked.value = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Rotating the phone recreates this screen; that alone must not ask for the PIN again.
        locked.value = App.instance.prefs.appLock && !unlockedInProcess
        setContent { AppRoot(this) }
    }

    override fun onResume() { super.onResume(); resumed.intValue++ }

    override fun onStop() { super.onStop(); stoppedAt = SystemClock.elapsedRealtime() }

    override fun onStart() {
        super.onStart()
        // Lock again after the app has been out of sight for a while. The short grace period
        // covers the system file picker and the unlock prompt themselves.
        if (App.instance.prefs.appLock && stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt > 20_000) { unlockedInProcess = false; locked.value = true }
    }

    companion object {
        /** Kept for the life of the process, so they survive the screen being recreated. */
        private var stoppedAt = 0L
        private var unlockedInProcess = false
    }

    /** Asks for fingerprint, face or the phone's own PIN, pattern or password. */
    fun unlock() {
        val title = Str.current(this).unlockTitle
        // The phone's own PIN was removed after the lock was switched on: there is nothing left
        // to ask for, and refusing would shut the owner out of their recordings for good.
        if (!getSystemService(KeyguardManager::class.java).isDeviceSecure) { unlocked(); return }
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricPrompt.Builder(this).setTitle(title)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
                .authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) { unlocked() }
                })
        } else {
            @Suppress("DEPRECATION")
            val intent = getSystemService(KeyguardManager::class.java).createConfirmDeviceCredentialIntent(title, null)
            if (intent != null) confirmCredential.launch(intent) else unlocked()
        }
    }
}

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

fun batteryUnrestricted(ctx: Context) = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

@SuppressLint("BatteryLife")
fun askBatteryExemption(ctx: Context) {
    try { ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
    catch (e: Exception) { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
}

@Composable
fun AppRoot(activity: MainActivity) {
    val app = App.instance
    val dark = when (app.prefs.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val palette = if (dark) DarkPalette else LightPalette
    val str = remember(app.prefs.lang, activity.resumed.intValue) { Str.current(app) }
    val view = LocalView.current
    val lockOn = app.prefs.appLock
    SideEffect {
        WindowCompat.getInsetsController(activity.window, view).run { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
        // With the app lock on, the content is also kept out of screenshots and the recent-apps preview.
        if (lockOn) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    val scheme = if (dark) darkColorScheme(primary = palette.seal, onPrimary = palette.onSeal, background = palette.bg, surface = palette.surface,
        onSurface = palette.text, onBackground = palette.text, surfaceVariant = palette.surface2, onSurfaceVariant = palette.muted, outline = palette.line, error = palette.rec,
        surfaceContainerHigh = palette.surface, surfaceContainer = palette.surface, surfaceContainerLow = palette.surface)
    else lightColorScheme(primary = palette.seal, onPrimary = palette.onSeal, background = palette.bg, surface = palette.surface,
        onSurface = palette.text, onBackground = palette.text, surfaceVariant = palette.surface2, onSurfaceVariant = palette.muted, outline = palette.line, error = palette.rec,
        surfaceContainerHigh = palette.surface, surfaceContainer = palette.surface, surfaceContainerLow = palette.surface)
    CompositionLocalProvider(LocalPalette provides palette, LocalStr provides str) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().background(palette.bg)) { if (activity.locked.value) LockScreen(activity) else Root(activity) }
        }
    }
}

@Composable
private fun LockScreen(activity: MainActivity) {
    LaunchedEffect(Unit) { activity.unlock() }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(CircleShape).background(P.sealDim), contentAlignment = Alignment.Center) { Ic(Icons.lock, 38.dp, P.seal) }
        T(S.appName, Modifier.padding(top = 22.dp), size = 20.sp, weight = FontWeight.Bold)
        T(S.lockedSub, Modifier.padding(top = 8.dp, bottom = 28.dp), size = 14.sp, color = P.muted, align = TextAlign.Center)
        PrimaryButton(S.unlock, Modifier.fillMaxWidth(), Icons.lock) { activity.unlock() }
    }
}

/** Extra battery steps for brands whose phones stop background apps on their own. Null for other brands. */
fun batteryGuide(s: Str): Pair<String, String>? = when (Build.MANUFACTURER.lowercase()) {
    "samsung" -> "Samsung" to s.guideSamsung
    "oppo", "oneplus", "realme" -> (if (Build.MANUFACTURER.equals("oneplus", true)) "OnePlus" else "OPPO") to s.guideOppo
    else -> null
}

@Composable
fun BatteryGuideDialog(brand: String, body: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = onClose, containerColor = P.surface,
        title = { T(S.batteryGuideCard(brand), size = 19.sp, weight = FontWeight.Bold) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { T(body, size = 14.sp, lineHeight = 21.sp); T(S.guideFoot, Modifier.padding(top = 14.dp), size = 12.sp, color = P.muted) } },
        confirmButton = { TextButton(onClick = { App.instance.prefs.batteryGuideDone(); onClose() }) { T(S.done, color = P.seal, weight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = { try { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) } catch (_: Exception) {} }) { T(S.openAppSettings, color = P.text) } },
    )
}

private sealed interface Dest {
    data object Tabs : Dest
    data class Detail(val id: String) : Dest
    data object Verify : Dest
    data class Legal(val doc: Doc) : Dest
}

@Composable
private fun Root(activity: MainActivity) {
    val app = App.instance
    val phase by RecState.phase.collectAsStateWithLifecycle()
    val finished by RecState.finishedId.collectAsStateWithLifecycle()
    val error by RecState.error.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var dest by remember { mutableStateOf<Dest>(Dest.Tabs) }
    var micSheet by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(finished) { finished?.let { dest = Dest.Detail(it); RecState.finishedId.value = null } }
    LaunchedEffect(error) { error?.let { ctx.toast(it); RecState.error.value = null } }


    val screen = if (phase != Phase.IDLE) "rec" else when (val d = dest) { Dest.Tabs -> "tab$tab"; is Dest.Detail -> "detail${d.id}"; Dest.Verify -> "verify"; is Dest.Legal -> "legal${d.doc.name}" }
    AnimatedContent(screen, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) }, label = "screen") { key ->
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            when {
                // Full-screen pages sit in a Box so their overlays (sealing, busy, discreet) can cover them.
                key == "rec" -> Box(Modifier.fillMaxSize()) { RecordingScreen() }
                key.startsWith("legal") -> { BackHandler { dest = Dest.Tabs }; Box(Modifier.fillMaxSize()) { DocScreen(Doc.valueOf(key.removePrefix("legal"))) { dest = Dest.Tabs } } }
                key == "verify" -> { BackHandler { dest = Dest.Tabs }; Box(Modifier.fillMaxSize()) { VerifyScreen { dest = Dest.Tabs } } }
                key.startsWith("detail") -> {
                    BackHandler { dest = Dest.Tabs }
                    Box(Modifier.fillMaxSize()) { DetailScreen(key.removePrefix("detail")) { dest = Dest.Tabs } }
                }
                else -> {
                    val t = key.removePrefix("tab").toInt()
                    if (t != 0) BackHandler { tab = 0 }
                    Box(Modifier.weight(1f)) {
                        when (t) {
                            0 -> HomeScreen(activity, { dest = Dest.Detail(it) }, { tab = 1 }, { dest = Dest.Verify }, { micSheet = true })
                            1 -> DashboardScreen { dest = Dest.Detail(it) }
                            else -> SettingsScreen(activity, { dest = Dest.Legal(it) }) { micSheet = true }
                        }
                    }
                    BottomNav(t) { tab = it }
                }
            }
        }
    }
    if (micSheet) MicSheet(activity) { micSheet = false }
}

@Composable
private fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    Column {
        Divider()
        Row(Modifier.fillMaxWidth().background(P.surface).padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceAround) {
            listOf(Icons.mic to S.navRecord, Icons.chart to S.dashboard, Icons.gear to S.settings).forEachIndexed { i, (icon, label) ->
                val on = i == tab
                Column(Modifier.width(104.dp).clip(RoundedCornerShape(16.dp)).clickable { onTab(i) }.padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(60.dp, 30.dp).clip(CircleShape).background(if (on) P.sealDim else Color.Transparent), contentAlignment = Alignment.Center) {
                        Ic(icon, tint = if (on) P.seal else P.muted)
                    }
                    Spacer(Modifier.height(4.dp))
                    T(label, size = 11.5.sp, color = if (on) P.text else P.muted, weight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun InfoDialog(title: String, body: String, button: String, onClose: () -> Unit) = AlertDialog(
    onDismissRequest = onClose, containerColor = P.surface, titleContentColor = P.text, textContentColor = P.text,
    title = { T(title, size = 19.sp, weight = FontWeight.Bold) },
    text = { Column(Modifier.verticalScroll(rememberScrollState())) { T(body, size = 14.sp, lineHeight = 21.sp) } },
    confirmButton = { TextButton(onClick = onClose) { T(button, color = P.seal, weight = FontWeight.SemiBold) } },
)

fun micLabel(s: Str, key: String) = if (key.isEmpty()) s.micPhone else key.substringAfter(':').ifEmpty { s.micTitle }

@Composable
fun RecordingRow(m: Meta, onClick: () -> Unit) {
    val sealed = m.state == "sealed"
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(if (sealed) P.sealDim else P.warnDim), contentAlignment = Alignment.Center) {
            Ic(if (sealed) Icons.shield else Icons.clock, tint = if (sealed) P.seal else P.warn)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val date = SimpleDateFormat("EEE d MMM, HH:mm", S.locale).format(Date(m.started))
            T(m.title.ifEmpty { date }, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T((if (m.title.isEmpty()) "" else "$date · ") + clock(m.durationMs) + if (!sealed) " · ${S.pendingLong}" else "", size = 13.sp, color = P.muted, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Pill(if (sealed) S.sealed else S.pending, if (sealed) PillKind.OK else PillKind.WAIT)
    }
}

@Composable
private fun HomeScreen(activity: MainActivity, onOpen: (String) -> Unit, onSeeAll: () -> Unit, onVerify: () -> Unit, onMic: () -> Unit) {
    val app = App.instance
    val ctx = LocalContext.current
    val s = S
    val items by app.store.items.collectAsStateWithLifecycle()
    val tick = activity.resumed.intValue
    val unrestricted = remember(tick) { batteryUnrestricted(ctx) }
    val online by produceState(Finisher.online(app), tick) { while (true) { value = Finisher.online(app); delay(3000) } }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording(ctx, s)
        else ctx.toast(s.needMic)
    }
    fun record() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) startRecording(ctx, s) else permission.launch(wanted.toTypedArray())
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0B1F3A))) {
                Image(painterResource(R.drawable.ic_foreground), null, Modifier.fillMaxSize().scale(1.5f))
            }
            Spacer(Modifier.width(12.dp))
            T(s.appName, Modifier.weight(1f), size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
            IconButton(Icons.shield, onClick = onVerify)
        }

        Column(Modifier.fillMaxWidth().padding(top = 26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearOutSlowInEasing)), label = "p")
            Box(Modifier.size(170.dp), contentAlignment = Alignment.Center) {
                val rec = P.rec
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(rec, radius = size.minDimension / 2 * (0.76f + 0.24f * pulse), alpha = 0.45f * (1 - pulse), style = Stroke(2.dp.toPx()))
                }
                Box(
                    Modifier.size(132.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Color(0xFFFF7A87), rec), center = Offset(120f, 100f), radius = 380f))
                        .clickable { record() }, contentAlignment = Alignment.Center,
                ) { Ic(Icons.mic, 50.dp, Color.White) }
            }
            Spacer(Modifier.height(14.dp))
            T(s.tap, size = 19.sp, weight = FontWeight.SemiBold)
            T(s.tapSub, Modifier.padding(top = 6.dp), size = 13.sp, color = P.muted, align = TextAlign.Center)
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val key = app.prefs.mic
                Pill(micLabel(s, key), icon = if (key.isEmpty()) Icons.phone else Icons.headset, onClick = onMic, trailing = Icons.down)
                if (online) Pill(s.timeReady, PillKind.OK, Icons.check) else Pill(s.timeOffline, PillKind.WAIT, Icons.clock)
            }
        }

        if (!unrestricted) {
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.warnDim).border(1.dp, P.warn, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    T(s.setupTitle, size = 14.sp, weight = FontWeight.SemiBold)
                    T(s.setupSub, size = 13.sp)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(P.warn).clickable { askBatteryExemption(ctx) }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    T(s.allow, size = 13.sp, color = if (P.dark) Color(0xFF1A1200) else Color.White, weight = FontWeight.Bold)
                }
            }
        }

        val guide = remember { batteryGuide(s) }
        var showGuide by remember { mutableStateOf(false) }
        if (unrestricted && guide != null && !app.prefs.batteryGuideDone) {
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(P.warnDim).border(1.dp, P.warn, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) { T(s.batteryGuideCard(guide.first), size = 14.sp, weight = FontWeight.SemiBold); T(s.batteryGuideCardSub, size = 13.sp) }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(P.warn).clickable { showGuide = true }.padding(horizontal = 14.dp, vertical = 9.dp)) {
                    T(s.show, size = 13.sp, color = if (P.dark) Color(0xFF1A1200) else Color.White, weight = FontWeight.Bold)
                }
            }
        }
        if (showGuide && guide != null) BatteryGuideDialog(guide.first, batteryGuide(s)!!.second) { showGuide = false }

        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            T(s.recent.uppercase(), Modifier.weight(1f), size = 12.5.sp, color = P.muted, weight = FontWeight.SemiBold)
            if (items.size > 3) Pill(s.seeAll, onClick = onSeeAll)
        }
        Spacer(Modifier.height(6.dp))
        val shown = items.filter { it.state != "recording" }.take(3)
        if (shown.isEmpty()) T(s.empty, Modifier.padding(vertical = 18.dp), color = P.muted, size = 14.sp)
        for (m in shown) RecordingRow(m) { onOpen(m.id) }
        Spacer(Modifier.height(16.dp))
    }
}

private fun startRecording(ctx: Context, s: Str) {
    if (StatFs(ctx.filesDir.path).availableBytes < 200L * 1024 * 1024) { ctx.toast(s.lowSpace); return }
    RecorderService.start(ctx)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingScreen() {
    val app = App.instance
    val ctx = LocalContext.current
    val s = S
    val phase by RecState.phase.collectAsStateWithLifecycle()
    val seconds by RecState.seconds.collectAsStateWithLifecycle()
    val levels by RecState.levels.collectAsStateWithLifecycle()
    val marks by RecState.marks.collectAsStateWithLifecycle()
    val anchored by RecState.anchored.collectAsStateWithLifecycle()
    val openTime by RecState.openTime.collectAsStateWithLifecycle()
    val silent by RecState.silent.collectAsStateWithLifecycle()
    val mic by RecState.mic.collectAsStateWithLifecycle()
    val startedAt by RecState.startedAt.collectAsStateWithLifecycle()
    var elapsed by remember { mutableLongStateOf(0L) }
    var noteSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    BackHandler { /* a recording only ends with a deliberate hold on Stop */ }
    // Discreet screen: black, minimum brightness, controls appear for a while on a double tap.
    var revealed by remember { mutableStateOf(!app.prefs.discreet) }
    val hidden = app.prefs.discreet && !revealed && phase == Phase.RECORDING && !noteSheet
    LaunchedEffect(revealed) { if (revealed && app.prefs.discreet) { delay(15_000); revealed = false } }
    val window = (ctx as? android.app.Activity)?.window
    DisposableEffect(hidden) {
        window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = if (hidden) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } }
        onDispose { window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
    LaunchedEffect(startedAt) { while (true) { elapsed = SystemClock.elapsedRealtime() - startedAt; delay(200) } }
    val view = LocalView.current
    DisposableEffect(app.prefs.keepScreenOn) { view.keepScreenOn = app.prefs.keepScreenOn; onDispose { view.keepScreenOn = false } }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        Row(Modifier.height(60.dp), verticalAlignment = Alignment.CenterVertically) {
            val blink by rememberInfiniteTransition(label = "b").animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(10.dp).clip(CircleShape).background(P.rec.copy(alpha = blink)))
            Spacer(Modifier.width(12.dp))
            T(s.recording, Modifier.weight(1f), size = 17.sp, weight = FontWeight.SemiBold)
            Pill(s.lossless)
        }
        T(clock(elapsed), Modifier.fillMaxWidth().padding(top = 10.dp), size = 64.sp, weight = FontWeight.Light, align = TextAlign.Center)

        val seal = P.seal
        val rec = P.rec
        Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 10.dp)) {
            if (!app.prefs.waveform) return@Canvas
            val n = levels.size
            val step = size.width / n
            for (i in 0 until n) {
                val h = (0.04f + levels[i].coerceIn(0f, 1f) * 0.96f) * size.height
                drawRoundRect(
                    if (i > n - 6) rec else seal, Offset(i * step + step * 0.2f, (size.height - h) / 2), Size(step * 0.6f, h),
                    CornerRadius(step * 0.3f), alpha = 0.35f + 0.65f * i / n,
                )
            }
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(P.sealDim), contentAlignment = Alignment.Center) { Ic(Icons.lock, tint = P.seal) }
                Spacer(Modifier.width(12.dp))
                Column {
                    T(s.sealing, size = 15.sp, weight = FontWeight.SemiBold)
                    T(s.secondsLocked(seconds), size = 13.sp, color = P.muted)
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(20) { i -> Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(P.seal.copy(alpha = if (i <= seconds % 20) 1f else 0.25f))) }
            }
        }

        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (anchored) {
                null -> Pill(s.startChecking)
                true -> Pill(s.startCertified(SimpleDateFormat("HH:mm:ss", s.locale).format(Date(openTime))), PillKind.OK)
                false -> Pill(s.startOffline, PillKind.WAIT)
            }
            Pill(if (mic.isEmpty()) s.micPhone else mic, icon = if (mic.isEmpty()) Icons.phone else Icons.headset)
            if (silent) Pill(s.micSilenced, PillKind.FAIL, Icons.warn) else Pill(s.noGaps)
            Pill(s.keepsRunning, icon = Icons.lock)
        }
        T(s.noPause, Modifier.fillMaxWidth().padding(top = 12.dp), size = 11.5.sp, color = P.muted, align = TextAlign.Center, lineHeight = 17.sp)

        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.CenterVertically) {
            SideButton(Icons.flag, s.mark + if (marks > 0) " · $marks" else "") {
                RecState.engine?.mark(); ctx.toast(s.markedAt(clock(elapsed)))
            }
            val progress = remember { Animatable(0f) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(104.dp).pointerInput(Unit) {
                        detectTapGestures(onPress = {
                            val job = scope.launch {
                                progress.animateTo(1f, tween(900, easing = LinearEasing))
                                thread { RecState.engine?.stop() }
                            }
                            tryAwaitRelease()
                            job.cancel()
                            progress.snapTo(0f)
                        })
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(rec, -90f, 360f * progress.value, false, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round), topLeft = Offset(3.dp.toPx(), 3.dp.toPx()), size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()))
                    }
                    Box(Modifier.size(84.dp).clip(CircleShape).background(P.rec), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(28.dp).clip(RoundedCornerShape(7.dp)).background(Color.White))
                    }
                }
                T(s.holdToStop, size = 12.sp)
            }
            SideButton(Icons.note, s.addNote) { noteSheet = true }
        }
    }

    if (noteSheet) {
        var text by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { noteSheet = false }, containerColor = P.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                T(s.noteTitle, size = 20.sp, weight = FontWeight.Bold)
                T(s.noteSub, Modifier.padding(top = 4.dp), size = 13.sp, color = P.muted, lineHeight = 19.sp)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 14.dp).height(120.dp), placeholder = { T(s.noteHint, color = P.muted) }, shape = RoundedCornerShape(16.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(P.rec)); Spacer(Modifier.width(8.dp)); T(s.stillRecording, size = 13.sp, color = P.muted)
                }
                PrimaryButton(s.saveNote, Modifier.fillMaxWidth().padding(top = 14.dp), enabled = text.isNotBlank()) {
                    RecState.engine?.note(text); noteSheet = false; ctx.toast(s.notedAt(clock(elapsed)))
                }
            }
        }
    }

    if (hidden) {
        Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures(onDoubleTap = { revealed = true }) }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(clock(elapsed), size = 22.sp, color = Color.White.copy(alpha = 0.22f), weight = FontWeight.Light)
                T(s.discreetHint, Modifier.padding(top = 6.dp), size = 11.sp, color = Color.White.copy(alpha = 0.14f))
            }
        }
    }

    if (phase == Phase.SEALING) {
        Box(Modifier.fillMaxSize().background(P.bg.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = P.seal)
                T(s.sealingNow, Modifier.padding(top = 18.dp), size = 16.sp, weight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SideButton(icon: String, label: String, onClick: () -> Unit) =
    Column(Modifier.width(96.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(P.surface2), contentAlignment = Alignment.Center) { Ic(icon) }
        Spacer(Modifier.height(6.dp))
        T(label, size = 12.sp, align = TextAlign.Center, maxLines = 1)
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MicSheet(activity: MainActivity, onClose: () -> Unit) {
    val app = App.instance
    val s = S
    val options = remember(activity.resumed.intValue) { Mics.options(activity) }
    ModalBottomSheet(onDismissRequest = onClose, containerColor = P.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            T(s.micTitle, size = 20.sp, weight = FontWeight.Bold)
            T(s.micSub, Modifier.padding(top = 4.dp), size = 13.sp, color = P.muted)
            for (o in options) {
                val selected = app.prefs.mic == o.key
                OptionRow(if (o.builtIn) Icons.phone else Icons.headset, if (o.builtIn) s.micPhone else o.product.ifEmpty { Mics.typeName(o.type) },
                    when { o.builtIn -> s.micPhoneSub; Mics.isBluetooth(o.type) -> s.micBluetoothSub; else -> s.micConnected }, selected) { app.prefs.mic(o.key); onClose() }
            }
            T(s.micWarn, Modifier.padding(top = 14.dp), size = 11.5.sp, color = P.muted, lineHeight = 17.sp)
        }
    }
}

@Composable
fun OptionRow(icon: String, title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.padding(top = 10.dp).fillMaxWidth().clip(shape).background(if (selected) P.sealDim else Color.Transparent)
            .border(1.dp, if (selected) P.seal else P.line, shape).clickable(onClick = onClick).padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(P.surface2), contentAlignment = Alignment.Center) { Ic(icon, tint = P.seal) }
        Spacer(Modifier.width(14.dp))
        Column { T(title, size = 15.sp, weight = FontWeight.SemiBold); T(sub, size = 13.sp, color = P.muted) }
    }
}
