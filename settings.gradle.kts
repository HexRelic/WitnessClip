// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WitnessClip"
include(":core", ":app")
