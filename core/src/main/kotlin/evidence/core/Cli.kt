// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

import java.io.BufferedInputStream
import java.io.File
import kotlin.system.exitProcess

/** Stand-alone verifier: `verify <recording.flac>`. Exit code 0 = no failed check. */
fun main(args: Array<String>) {
    if (args.size != 1) { System.err.println("usage: verify <recording.flac>"); exitProcess(2) }
    val report = BufferedInputStream(File(args[0]).inputStream(), 1 shl 16).use { Verifier.verify(it) }
    report.manifest?.let {
        println("Device ID  ${shortDeviceId(it.keys.deviceId)}  (${it.keys.deviceId})")
        println("Recording  ${it.sessionId}  %.1f s  ${it.audio.sampleRate} Hz ${it.audio.bits}-bit ${it.audio.channels} ch".format(report.audioSeconds))
    }
    for (c in report.checks) println("%-5s %-12s %s".format(c.status, c.id, c.detail))
    println(if (report.failed) "RESULT: NOT VALID" else "RESULT: no failed checks")
    exitProcess(if (report.failed) 1 else 0)
}
