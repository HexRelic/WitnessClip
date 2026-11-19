// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.hexrelic.witnessclip"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.hexrelic.witnessclip"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // No code shrinking or obfuscation: the shipped code must match the public source
            // class for class, and the build must be reproducible by anyone.
            isMinifyEnabled = false
            isShrinkResources = false
            // No embedded version-control data, which would differ between checkouts.
            vcsInfo.include = false
            // Built unsigned. The signature is added in a separate step so that anyone can
            // rebuild the exact same bytes and compare them with the published APK.
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/versions/**", "META-INF/*.kotlin_module")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)

    testImplementation(libs.junit)
}

/**
 * Structural check of every APK we produce ("GhostMap", Bitdefender Labs 2026): Android maps
 * an APK entry at its declared uncompressed size when the entry is neither stored nor
 * deflated, without checking that the file is that large. A system service reading such an
 * entry crashes, and the phone can end up in a boot loop that only a factory reset clears.
 * The standard toolchain never writes such entries; this task proves it for each build and
 * fails the build otherwise. It checks, for every entry: compression method is stored (0) or
 * deflated (8), central and local headers agree, the data lies inside the file, a stored
 * entry has equal sizes, and the entry really unpacks to its declared size and CRC.
 */
fun checkApk(apk: File): Int {
    fun fail(why: String): Nothing = throw GradleException("APK structure check failed for ${apk.name}: $why")
    RandomAccessFile(apk, "r").use { f ->
        fun u16(p: Long): Int { f.seek(p); return f.read() or (f.read() shl 8) }
        fun u32(p: Long): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
        var eocd = f.length() - 22
        while (eocd >= 0 && u32(eocd) != 0x06054b50L) eocd--
        if (eocd < 0) fail("no end-of-central-directory record")
        val count = u16(eocd + 10)
        val cdOffset = u32(eocd + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) fail("ZIP64 is not expected")
        ZipFile(apk).use { zip ->
            if (zip.size() != count) fail("entry count mismatch")
            var p = cdOffset
            val buf = ByteArray(1 shl 16)
            repeat(count) {
                if (u32(p) != 0x02014b50L) fail("bad central directory entry")
                val method = u16(p + 10); val crc = u32(p + 16); val csize = u32(p + 20); val usize = u32(p + 24)
                val nameLen = u16(p + 28); val extraLen = u16(p + 30); val commentLen = u16(p + 32); val local = u32(p + 42)
                val nameBytes = ByteArray(nameLen); f.seek(p + 46); f.readFully(nameBytes)
                val name = String(nameBytes, Charsets.UTF_8)
                if (method != 0 && method != 8) fail("$name uses compression method $method")
                if (u32(local) != 0x04034b50L) fail("$name: bad local header")
                if (u16(local + 8) != method) fail("$name: local and central compression method differ")
                val data = local + 30 + u16(local + 26) + u16(local + 28)
                if (data + csize > cdOffset) fail("$name: data runs past the end of the file")
                if (method == 0 && csize != usize) fail("$name: stored entry with different sizes")
                val entry = zip.getEntry(name) ?: fail("$name: not readable")
                val check = CRC32(); var total = 0L
                zip.getInputStream(entry).use { s -> while (true) { val n = s.read(buf); if (n < 0) break; check.update(buf, 0, n); total += n } }
                if (total != usize) fail("$name: declares $usize bytes but holds $total")
                if (check.value != crc) fail("$name: CRC mismatch")
                p += 46 + nameLen + extraLen + commentLen
            }
            if (p > eocd) fail("central directory overruns")
        }
        return count
    }
}

val checkApkStructure = tasks.register("checkApkStructure") {
    group = "verification"
    description = "Fails if any built APK has a ZIP entry Android could mis-map."
    val dir = layout.buildDirectory.dir("outputs/apk")
    doLast {
        // -Papk=/path/to/file.apk checks one given APK instead (for example a downloaded release)
        val one = providers.gradleProperty("apk").orNull
        val apks = if (one != null) listOf(File(one)) else dir.get().asFile.walkTopDown().filter { it.extension == "apk" }.toList()
        for (apk in apks) println("APK structure OK: ${apk.name}, ${checkApk(apk)} entries, all stored or deflated with consistent sizes")
    }
}
tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("package") && it.name != "packageDebugResources" && it.name != "packageReleaseResources" }
    .configureEach { finalizedBy(checkApkStructure) }
