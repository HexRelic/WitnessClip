# Third-party components and licences

Checked on 15 November 2026 for version 0.1.0.

Method: the list of artifacts is the fully resolved `releaseRuntimeClasspath` of the `app`
module (which includes everything `core` uses). For each one, `tools/licenses.py` downloaded
the published POM from Google Maven or Maven Central on the date above and read the licence
declared there. Every row links to the POM that was read. To repeat the check:

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```

then pass the `group:artifact:version` lines to `python3 tools/licenses.py`.

## Contents

- [Result](#result)
- [Code and assets written for this project](#code-and-assets-written-for-this-project)
- [Build-time and test-only tools (not shipped in the app)](#build-time-and-test-only-tools-not-shipped-in-the-app)
- [Runtime dependencies (shipped inside the APK)](#runtime-dependencies-shipped-inside-the-apk)

## Result

| Licence | Artifacts | Open-source (OSI) | Allows use in this app |
|---|---|---|---|
| Apache License 2.0 | 105 | yes | yes |
| Bouncy Castle Licence (MIT text) | 3 | yes | yes |

Both licences are compatible with the project's own licence, GPL-3.0-or-later (Apache-2.0
code may be included in GPLv3 works; the reverse is not true). No third-party copyleft code
(GPL, LGPL, AGPL, MPL, EPL) is shipped. No proprietary SDK, no Google Play
Services, no Firebase, no analytics or advertising library.

Obligations and how they are met:

- Apache-2.0: include the licence text and keep notices. The full text ships in the app
  (`app/src/main/assets/apache-2.0.txt`) and is shown under Settings → Third-party software.
- Bouncy Castle: reproduce the copyright and permission notice. It is shown on the same page.

## Code and assets written for this project

| Item | Origin | Licence |
|---|---|---|
| All Kotlin source in `app/` and `core/`, including the FLAC encoder and decoder, Merkle log, verifier, PDF/A writer | written for this project | GPL-3.0-or-later |
| Interface icons | drawn for this project as vector paths in `ui/Theme.kt`; no icon library | GPL-3.0-or-later |
| On-screen fonts | none bundled; the interface uses the fonts already installed on the phone | not distributed by us |
| Liberation Sans Regular 2.1.5 (`core/src/main/resources/font/`, SHA-256 `075e71be4c58beda172ecb574a799308f04c3d054c6a5b7434954d3b0f1b28d8`) | Google and Red Hat, <https://github.com/liberationfonts>; copied from the Debian/Ubuntu package `fonts-liberation`. Embedded in the PDF reports so they are valid PDF/A. | SIL Open Font License 1.1 (free; embedding in documents explicitly allowed; text shipped next to the font and in the app) |
| Sounds, images, photos | none | n/a |
| `core/src/main/resources/attestation_roots.json` | Google's public hardware-attestation root certificates, downloaded from `https://android.googleapis.com/attestation/root` | public certificates published for verification |
| `LICENSE` | GNU GPL version 3, Free Software Foundation | verbatim copying of the licence text is permitted |
| Name "WitnessClip" and icon | project owner | trademark, see `TRADEMARK.md`; not licensed under the GPL |

FLAC is an open, royalty-free format (Xiph.Org); no Xiph code is included.

## Build-time and test-only tools (not shipped in the app)

| Artifact | Version | Licence declared in POM | Source |
|---|---|---|---|
| `com.android.tools.build:gradle` | 8.13.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/8.13.0/gradle-8.13.0.pom) |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.2.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/2.2.20/kotlin-gradle-plugin-2.2.20.pom) |
| `org.jetbrains.kotlin:compose-compiler-gradle-plugin` | 2.2.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/compose-compiler-gradle-plugin/2.2.20/compose-compiler-gradle-plugin-2.2.20.pom) |
| `org.jetbrains.kotlin:kotlin-serialization` | 2.2.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-serialization/2.2.20/kotlin-serialization-2.2.20.pom) |
| `junit:junit` | 4.13.2 | [Eclipse Public License 1.0](http://www.eclipse.org/legal/epl-v10.html) | [Maven Central](https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom) |

Also not shipped: Gradle 8.14.3 and its wrapper (`gradlew`, `gradle/wrapper/`), Apache-2.0;
the Android SDK platform (`android.jar`), used only to compile against under the Android SDK
licence; OpenJDK 17, GPL-2.0 with Classpath Exception. JUnit (EPL-1.0) is used only to run tests.

## Runtime dependencies (shipped inside the APK)

| Artifact | Version | Licence declared in POM | Source |
|---|---|---|---|
| `androidx.activity:activity` | 1.10.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/activity/activity/1.10.1/activity-1.10.1.pom) |
| `androidx.activity:activity-compose` | 1.10.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/1.10.1/activity-compose-1.10.1.pom) |
| `androidx.activity:activity-ktx` | 1.10.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/activity/activity-ktx/1.10.1/activity-ktx-1.10.1.pom) |
| `androidx.annotation:annotation` | 1.9.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation/1.9.1/annotation-1.9.1.pom) |
| `androidx.annotation:annotation-experimental` | 1.4.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-experimental/1.4.1/annotation-experimental-1.4.1.pom) |
| `androidx.annotation:annotation-jvm` | 1.9.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/annotation/annotation-jvm/1.9.1/annotation-jvm-1.9.1.pom) |
| `androidx.arch.core:core-common` | 2.2.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-common/2.2.0/core-common-2.2.0.pom) |
| `androidx.arch.core:core-runtime` | 2.2.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/arch/core/core-runtime/2.2.0/core-runtime-2.2.0.pom) |
| `androidx.autofill:autofill` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/autofill/autofill/1.0.0/autofill-1.0.0.pom) |
| `androidx.collection:collection` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/collection/collection/1.5.0/collection-1.5.0.pom) |
| `androidx.collection:collection-jvm` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/collection/collection-jvm/1.5.0/collection-jvm-1.5.0.pom) |
| `androidx.collection:collection-ktx` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/collection/collection-ktx/1.5.0/collection-ktx-1.5.0.pom) |
| `androidx.compose.animation:animation` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation/1.11.4/animation-1.11.4.pom) |
| `androidx.compose.animation:animation-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-android/1.11.4/animation-android-1.11.4.pom) |
| `androidx.compose.animation:animation-core` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core/1.11.4/animation-core-1.11.4.pom) |
| `androidx.compose.animation:animation-core-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/animation/animation-core-android/1.11.4/animation-core-android-1.11.4.pom) |
| `androidx.compose:compose-bom` | 2026.06.01 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.06.01/compose-bom-2026.06.01.pom) |
| `androidx.compose.foundation:foundation` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.11.4/foundation-1.11.4.pom) |
| `androidx.compose.foundation:foundation-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.11.4/foundation-android-1.11.4.pom) |
| `androidx.compose.foundation:foundation-layout` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout/1.11.4/foundation-layout-1.11.4.pom) |
| `androidx.compose.foundation:foundation-layout-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-layout-android/1.11.4/foundation-layout-android-1.11.4.pom) |
| `androidx.compose.material3:material3` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3/1.4.0/material3-1.4.0.pom) |
| `androidx.compose.material3:material3-android` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0.pom) |
| `androidx.compose.material:material-ripple` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple/1.11.4/material-ripple-1.11.4.pom) |
| `androidx.compose.material:material-ripple-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/material/material-ripple-android/1.11.4/material-ripple-android-1.11.4.pom) |
| `androidx.compose.runtime:runtime` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime/1.11.4/runtime-1.11.4.pom) |
| `androidx.compose.runtime:runtime-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-android/1.11.4/runtime-android-1.11.4.pom) |
| `androidx.compose.runtime:runtime-annotation` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation/1.11.4/runtime-annotation-1.11.4.pom) |
| `androidx.compose.runtime:runtime-annotation-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-annotation-android/1.11.4/runtime-annotation-android-1.11.4.pom) |
| `androidx.compose.runtime:runtime-retain` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-retain/1.11.4/runtime-retain-1.11.4.pom) |
| `androidx.compose.runtime:runtime-retain-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-retain-android/1.11.4/runtime-retain-android-1.11.4.pom) |
| `androidx.compose.runtime:runtime-saveable` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable/1.11.4/runtime-saveable-1.11.4.pom) |
| `androidx.compose.runtime:runtime-saveable-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/runtime/runtime-saveable-android/1.11.4/runtime-saveable-android-1.11.4.pom) |
| `androidx.compose.ui:ui` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/1.11.4/ui-1.11.4.pom) |
| `androidx.compose.ui:ui-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-android/1.11.4/ui-android-1.11.4.pom) |
| `androidx.compose.ui:ui-geometry` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry/1.11.4/ui-geometry-1.11.4.pom) |
| `androidx.compose.ui:ui-geometry-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-geometry-android/1.11.4/ui-geometry-android-1.11.4.pom) |
| `androidx.compose.ui:ui-graphics` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics/1.11.4/ui-graphics-1.11.4.pom) |
| `androidx.compose.ui:ui-graphics-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-graphics-android/1.11.4/ui-graphics-android-1.11.4.pom) |
| `androidx.compose.ui:ui-text` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text/1.11.4/ui-text-1.11.4.pom) |
| `androidx.compose.ui:ui-text-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.11.4/ui-text-android-1.11.4.pom) |
| `androidx.compose.ui:ui-unit` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit/1.11.4/ui-unit-1.11.4.pom) |
| `androidx.compose.ui:ui-unit-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-unit-android/1.11.4/ui-unit-android-1.11.4.pom) |
| `androidx.compose.ui:ui-util` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util/1.11.4/ui-util-1.11.4.pom) |
| `androidx.compose.ui:ui-util-android` | 1.11.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-util-android/1.11.4/ui-util-android-1.11.4.pom) |
| `androidx.concurrent:concurrent-futures` | 1.1.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/concurrent/concurrent-futures/1.1.0/concurrent-futures-1.1.0.pom) |
| `androidx.core:core` | 1.17.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/core/core/1.17.0/core-1.17.0.pom) |
| `androidx.core:core-ktx` | 1.17.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/1.17.0/core-ktx-1.17.0.pom) |
| `androidx.core:core-viewtree` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/core/core-viewtree/1.0.0/core-viewtree-1.0.0.pom) |
| `androidx.customview:customview-poolingcontainer` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/customview/customview-poolingcontainer/1.0.0/customview-poolingcontainer-1.0.0.pom) |
| `androidx.documentfile:documentfile` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/documentfile/documentfile/1.0.0/documentfile-1.0.0.pom) |
| `androidx.dynamicanimation:dynamicanimation` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/dynamicanimation/dynamicanimation/1.0.0/dynamicanimation-1.0.0.pom) |
| `androidx.emoji2:emoji2` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/emoji2/emoji2/1.4.0/emoji2-1.4.0.pom) |
| `androidx.graphics:graphics-path` | 1.0.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/graphics/graphics-path/1.0.1/graphics-path-1.0.1.pom) |
| `androidx.interpolator:interpolator` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/interpolator/interpolator/1.0.0/interpolator-1.0.0.pom) |
| `androidx.legacy:legacy-support-core-utils` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/legacy/legacy-support-core-utils/1.0.0/legacy-support-core-utils-1.0.0.pom) |
| `androidx.lifecycle:lifecycle-common` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common/2.9.4/lifecycle-common-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-common-java8` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-java8/2.9.4/lifecycle-common-java8-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-common-jvm` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-common-jvm/2.9.4/lifecycle-common-jvm-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-livedata` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata/2.9.4/lifecycle-livedata-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-livedata-core` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core/2.9.4/lifecycle-livedata-core-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-livedata-core-ktx` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-livedata-core-ktx/2.9.4/lifecycle-livedata-core-ktx-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-process` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-process/2.9.4/lifecycle-process-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime/2.9.4/lifecycle-runtime-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-android` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-android/2.9.4/lifecycle-runtime-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-compose` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose/2.9.4/lifecycle-runtime-compose-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-compose-android` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-compose-android/2.9.4/lifecycle-runtime-compose-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-ktx` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx/2.9.4/lifecycle-runtime-ktx-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-runtime-ktx-android` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-runtime-ktx-android/2.9.4/lifecycle-runtime-ktx-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel/2.9.4/lifecycle-viewmodel-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-android` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-android/2.9.4/lifecycle-viewmodel-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-compose` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose/2.9.4/lifecycle-viewmodel-compose-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-compose-android` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-compose-android/2.9.4/lifecycle-viewmodel-compose-android-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-ktx` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-ktx/2.9.4/lifecycle-viewmodel-ktx-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate/2.9.4/lifecycle-viewmodel-savedstate-2.9.4.pom) |
| `androidx.lifecycle:lifecycle-viewmodel-savedstate-android` | 2.9.4 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/lifecycle/lifecycle-viewmodel-savedstate-android/2.9.4/lifecycle-viewmodel-savedstate-android-2.9.4.pom) |
| `androidx.loader:loader` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/loader/loader/1.0.0/loader-1.0.0.pom) |
| `androidx.localbroadcastmanager:localbroadcastmanager` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/localbroadcastmanager/localbroadcastmanager/1.0.0/localbroadcastmanager-1.0.0.pom) |
| `androidx.print:print` | 1.0.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/print/print/1.0.0/print-1.0.0.pom) |
| `androidx.profileinstaller:profileinstaller` | 1.4.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/profileinstaller/profileinstaller/1.4.0/profileinstaller-1.4.0.pom) |
| `androidx.savedstate:savedstate` | 1.3.2 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate/1.3.2/savedstate-1.3.2.pom) |
| `androidx.savedstate:savedstate-android` | 1.3.2 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-android/1.3.2/savedstate-android-1.3.2.pom) |
| `androidx.savedstate:savedstate-compose` | 1.3.2 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose/1.3.2/savedstate-compose-1.3.2.pom) |
| `androidx.savedstate:savedstate-compose-android` | 1.3.2 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-compose-android/1.3.2/savedstate-compose-android-1.3.2.pom) |
| `androidx.savedstate:savedstate-ktx` | 1.3.2 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/savedstate/savedstate-ktx/1.3.2/savedstate-ktx-1.3.2.pom) |
| `androidx.startup:startup-runtime` | 1.1.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/startup/startup-runtime/1.1.1/startup-runtime-1.1.1.pom) |
| `androidx.tracing:tracing` | 1.2.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/tracing/tracing/1.2.0/tracing-1.2.0.pom) |
| `androidx.transition:transition` | 1.6.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/transition/transition/1.6.0/transition-1.6.0.pom) |
| `androidx.versionedparcelable:versionedparcelable` | 1.1.1 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/versionedparcelable/versionedparcelable/1.1.1/versionedparcelable-1.1.1.pom) |
| `androidx.window:window` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/window/window/1.5.0/window-1.5.0.pom) |
| `androidx.window:window-core` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/window/window-core/1.5.0/window-core-1.5.0.pom) |
| `androidx.window:window-core-android` | 1.5.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Google Maven](https://dl.google.com/dl/android/maven2/androidx/window/window-core-android/1.5.0/window-core-android-1.5.0.pom) |
| `com.google.guava:listenablefuture` | 1.0 | [The Apache Software License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/com/google/guava/listenablefuture/1.0/listenablefuture-1.0.pom) |
| `org.bouncycastle:bcpkix-jdk18on` | 1.79 | [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) | [Maven Central](https://repo1.maven.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.79/bcpkix-jdk18on-1.79.pom) |
| `org.bouncycastle:bcprov-jdk18on` | 1.79 | [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) | [Maven Central](https://repo1.maven.org/maven2/org/bouncycastle/bcprov-jdk18on/1.79/bcprov-jdk18on-1.79.pom) |
| `org.bouncycastle:bcutil-jdk18on` | 1.79 | [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) | [Maven Central](https://repo1.maven.org/maven2/org/bouncycastle/bcutil-jdk18on/1.79/bcutil-jdk18on-1.79.pom) |
| `org.jetbrains:annotations` | 23.0.0 | [The Apache Software License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/annotations/23.0.0/annotations-23.0.0.pom) |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.20 | [Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/2.2.20/kotlin-stdlib-2.2.20.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-android/1.10.2/kotlinx-coroutines-android-1.10.2.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-bom` | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-bom/1.10.2/kotlinx-coroutines-bom-1.10.2.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/1.10.2/kotlinx-coroutines-core-1.10.2.pom) |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm` | 1.10.2 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core-jvm/1.10.2/kotlinx-coroutines-core-jvm-1.10.2.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-bom` | 1.9.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-bom/1.9.0/kotlinx-serialization-bom-1.9.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core` | 1.9.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core/1.9.0/kotlinx-serialization-core-1.9.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-core-jvm` | 1.9.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-core-jvm/1.9.0/kotlinx-serialization-core-jvm-1.9.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.9.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/1.9.0/kotlinx-serialization-json-1.9.0.pom) |
| `org.jetbrains.kotlinx:kotlinx-serialization-json-jvm` | 1.9.0 | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json-jvm/1.9.0/kotlinx-serialization-json-jvm-1.9.0.pom) |
| `org.jspecify:jspecify` | 1.0.0 | [The Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.txt) | [Maven Central](https://repo1.maven.org/maven2/org/jspecify/jspecify/1.0.0/jspecify-1.0.0.pom) |
