# Reproducible builds and releases

Anyone can rebuild WitnessClip from source and get exactly the same bytes as the published
APK. For an evidence app this matters: a court expert can confirm that the app on a phone is
the app whose source they read. It is also what lets F-Droid publish the developer-signed APK.

## Contents

- [Why the build is deterministic](#why-the-build-is-deterministic)
- [Rebuilding the app](#rebuilding-the-app)
- [Comparing with the published, signed APK](#comparing-with-the-published-signed-apk)
- [Signing certificate](#signing-certificate)
- [Making a release](#making-a-release)
- [Adding or updating a dependency](#adding-or-updating-a-dependency)

## Why the build is deterministic

- The Gradle version is fixed and verified by checksum (`gradle/wrapper/gradle-wrapper.properties`).
- Every dependency has an exact version (`gradle/libs.versions.toml`), and the SHA-256 of every
  downloaded artifact and plugin is checked on every build (`gradle/verification-metadata.xml`).
- No code shrinking, obfuscation or resource shrinking.
- No version-control data, dependency metadata, build date or machine path ends up in the APK.
- No native code, and nothing is generated from the network at build time.
- The release APK is built unsigned. Signing is a separate step done on a copy.
- Every APK is checked entry by entry for malformed ZIP structure (`checkApkStructure`).

Two clean builds in different folders produce identical files, and so do builds on different
machines. On 18 November 2026, a pre-release build of 0.1.0 was made with
`tools/reproduce.sh` on one machine and from a fresh copy of the source on a second, separate
Linux system with its own JDK 17 and Android SDK. All builds gave the same unsigned APK.

## Rebuilding the app

You need JDK 17 and the Android SDK with platform 36 and build-tools 36.0.0. Point
`ANDROID_HOME` at the SDK, or create a `local.properties` file with `sdk.dir=...`. The first
build downloads Gradle and the dependencies, all checked against their recorded hashes.

Check out the tag of the release you want to compare, then:

```bash
./gradlew --no-daemon --no-build-cache clean :app:assembleRelease
```

```bash
sha256sum app/build/outputs/apk/release/app-release-unsigned.apk
```

The hash should match the `-unsigned.apk` line in the `SHA256SUMS.txt` attached to the
release:

| Version | Unsigned APK (SHA-256) |
|---|---|
| 0.1.1 | `1742a16f26b17655d4b8d388ecc3eac7883a9e83ea520104ed02e9b601221acd` |
| 0.1.0 | `c2e6c06b4eb1efa029dd4fb836761e8f1860164ef116b7d00220c3e78ab11958` |

To confirm that builds are reproducible on your own machine:

```bash
tools/reproduce.sh
```

It copies the source into two folders with different paths, builds both from scratch and
compares them.

## Comparing with the published, signed APK

A signed APK differs from the unsigned build only by its signature block.
[apksigcopier](https://github.com/obfusk/apksigcopier), the tool F-Droid uses, copies the
signature from the published APK onto your build and checks that the result is valid:

```bash
apksigcopier compare WitnessClip-0.1.1.apk --unsigned app/build/outputs/apk/release/app-release-unsigned.apk
```

If it succeeds, the published APK was built from this source and nothing else.

To see which certificate signed an APK:

```bash
apksigner verify --print-certs WitnessClip-0.1.1.apk
```

## Signing certificate

SHA-256 of the release signing certificate: `5888f7dd3fdd8fedb70c0b6bfb7a2e8d7b000d431c0732e687587afa91147eed`

The same value is pinned in the verifier (`core/src/main/kotlin/evidence/core/Release.kt`),
appears in the hardware attestation of every recording made with the official app, and is the
`AllowedAPKSigningKeys` value in the F-Droid metadata.

## Making a release

The signing key decides who can publish updates under the app's name. It is created once and
kept offline, and its password is typed at a prompt, never stored in the project.

Create the key once, somewhere outside the project folder:

```bash
tools/new-signing-key.sh /safe/place/witnessclip-release.p12
```

Keep at least two offline copies. If the key is lost, existing users can't be updated; if it
leaks, someone else can publish as WitnessClip.

For each release:

1. Raise `versionCode` and set `versionName` in `app/build.gradle.kts`, and add the store
   changelog in `fastlane/metadata/android/*/changelogs/<versionCode>.txt`.
2. Re-check the timestamp services against the trusted list (`python3 tools/tsl_check.py`)
   and, if dependencies changed, the licences ([DEPENDENCIES.md](DEPENDENCIES.md)).
3. Build, sign and verify:

   ```bash
   tools/sign-release.sh /safe/place/witnessclip-release.p12
   ```

   The script runs the tests, builds from clean, signs a copy (APK signature schemes v2 and
   v3), verifies it, checks the APK structure and writes the signed APK, the unsigned APK and
   `SHA256SUMS.txt` to `release/<version>/`.

   For the first release, the script prints the certificate fingerprint. Put it in
   `Release.kt` and in the section above, then run the script again, since `Release.kt` is
   part of the app.
4. Run `tools/reproduce.sh` and check that its hash matches the unsigned line in
   `SHA256SUMS.txt`.
5. Tag the source as `v<versionName>`, push the tag, and attach the signed APK and
   `SHA256SUMS.txt` to the GitHub release.

`release/` and keystore files are listed in `.gitignore`.

## Adding or updating a dependency

```bash
./gradlew --write-verification-metadata sha256 :app:assembleRelease :app:assembleDebug :core:test :app:testDebugUnitTest :app:lintDebug
```

Review the changes to `gradle/verification-metadata.xml` and re-run the licence check
described in [DEPENDENCIES.md](DEPENDENCIES.md).
