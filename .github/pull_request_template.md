## What and why

<!-- One or two sentences. Link the issue: Fixes #… -->

## Checklist

- [ ] `./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` passes
- [ ] Touches time or verification code: `./gradlew :core:test -Dlive=1` passes
- [ ] Touches what is hashed, signed or timestamped: a test shows a tampered file still fails
- [ ] Touches parsing in the verifier: a hostile-file test is included
- [ ] User-visible text in English **and** Italian
- [ ] No new network destination, telemetry or proprietary dependency
- [ ] New dependency: added to `gradle/verification-metadata.xml` and `docs/DEPENDENCIES.md`
- [ ] Touches build files: `tools/reproduce.sh` still reports REPRODUCIBLE
- [ ] Tested on a phone (model, Android version): …
- [ ] `CHANGELOG.md` updated under *Unreleased*
- [ ] Commits signed off (`git commit -s`), contribution licensed GPL-3.0-or-later
