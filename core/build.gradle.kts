// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("evidence.core.CliKt")
}

tasks.test {
    // -Dlive=1 also runs the test that contacts the real timestamp authorities
    systemProperty("live", System.getProperty("live") ?: "")
    testLogging.showStandardStreams = true
}

dependencies {
    api(libs.kotlinx.serialization.json)
    api(libs.bcprov)
    api(libs.bcpkix)
    testImplementation(libs.junit)
}
