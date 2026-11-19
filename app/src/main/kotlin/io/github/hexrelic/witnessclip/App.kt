// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import evidence.core.J
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.concurrent.Executors

class App : Application() {
    lateinit var store: Store
    lateinit var prefs: Prefs
    /** One worker for sealing, timestamping and recovery, so they never overlap. */
    val work = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        store = Store(this)
        work.execute { Finisher.recoverAll(this); Finisher.anchorPending(this) }
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { work.execute { Finisher.anchorPending(this@App) } }
        })
    }

    companion object { lateinit var instance: App }
}

/** User choices. Backed by SharedPreferences, observable from Compose. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
    var theme by mutableStateOf(sp.getString("theme", "system")!!); private set
    var lang by mutableStateOf(sp.getString("lang", "system")!!); private set
    /** "" = phone microphone, otherwise "type:product name" of an attached input. */
    var mic by mutableStateOf(sp.getString("mic", "")!!); private set
    var keepScreenOn by mutableStateOf(sp.getBoolean("screen", true)); private set
    var waveform by mutableStateOf(sp.getBoolean("wave", true)); private set
    var onboarded by mutableStateOf(sp.getBoolean("onboarded", false)); private set
    var stereo by mutableStateOf(sp.getBoolean("stereo", false)); private set
    var location by mutableStateOf(sp.getBoolean("location", false)); private set
    var discreet by mutableStateOf(sp.getBoolean("discreet", false)); private set
    var appLock by mutableStateOf(sp.getBoolean("lock", false)); private set
    /** Folder the user chose for saved files (a document-tree URI), or "" to ask every time. */
    var saveFolder by mutableStateOf(sp.getString("folder", "")!!); private set
    var batteryGuideDone by mutableStateOf(sp.getBoolean("oem", false)); private set

    fun theme(v: String) { theme = v; sp.edit().putString("theme", v).apply() }
    fun lang(v: String) { lang = v; sp.edit().putString("lang", v).apply() }
    fun mic(v: String) { mic = v; sp.edit().putString("mic", v).apply() }
    fun keepScreenOn(v: Boolean) { keepScreenOn = v; sp.edit().putBoolean("screen", v).apply() }
    fun waveform(v: Boolean) { waveform = v; sp.edit().putBoolean("wave", v).apply() }
    fun stereo(v: Boolean) { stereo = v; sp.edit().putBoolean("stereo", v).apply() }
    fun location(v: Boolean) { location = v; sp.edit().putBoolean("location", v).apply() }
    fun discreet(v: Boolean) { discreet = v; sp.edit().putBoolean("discreet", v).apply() }
    fun appLock(v: Boolean) { appLock = v; sp.edit().putBoolean("lock", v).apply() }
    fun saveFolder(v: String) { saveFolder = v; sp.edit().putString("folder", v).apply() }
    fun batteryGuideDone() { batteryGuideDone = true; sp.edit().putBoolean("oem", true).apply() }
    fun onboarded() { onboarded = true; sp.edit().putBoolean("onboarded", true).apply() }
}

/** What the lists and dashboard need, kept next to each recording. Not part of the seal. */
@Serializable
data class Meta(
    val id: String,
    val started: Long,
    /** "recording", "pending" (sealed on device, waiting for an independent timestamp) or "sealed". */
    val state: String,
    val durationMs: Long = 0,
    val size: Long = 0,
    val marks: Int = 0,
    val notes: Int = 0,
    val mic: String = "",
    val builtInMic: Boolean = true,
    /** One loudness value (0..255) per second, base64, for drawing the waveform. */
    val peaks: String = "",
    val openTime: Long = 0,
    val closeTime: Long = 0,
    val endReason: String = "user",
    val keyLevel: String = "",
    /** Name the user gave the recording. A label for the list only; it is not part of the seal. */
    val title: String = "",
    val channels: Int = 1,
)

class Store(ctx: Context) {
    private val root = File(ctx.filesDir, "sessions").apply { mkdirs() }
    val items = MutableStateFlow<List<Meta>>(emptyList())

    init { reload() }

    fun dir(id: String) = File(root, id)
    fun flac(id: String) = File(dir(id), "recording.flac")
    fun get(id: String) = items.value.firstOrNull { it.id == id }

    fun reload() {
        items.value = (root.listFiles() ?: emptyArray()).mapNotNull { d ->
            try { J.decodeFromString(Meta.serializer(), File(d, "meta.json").readText()) } catch (e: Exception) { null }
        }.sortedByDescending { it.started }
    }

    @Synchronized
    fun save(m: Meta) {
        val d = dir(m.id).apply { mkdirs() }
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(J.encodeToString(m))
        tmp.renameTo(File(d, "meta.json"))
        items.value = (items.value.filter { it.id != m.id } + m).sortedByDescending { it.started }
    }

    @Synchronized
    fun delete(id: String) {
        dir(id).deleteRecursively()
        items.value = items.value.filter { it.id != id }
    }

    /** Folders that hold a sealed file but no readable list entry. */
    fun orphans(): List<String> {
        val known = items.value.map { it.id }.toSet()
        return (root.listFiles() ?: emptyArray()).filter { it.isDirectory && it.name !in known && File(it, "recording.flac").exists() }.map { it.name }
    }

    fun usedBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
