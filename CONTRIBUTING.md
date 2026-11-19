# Contributing to WitnessClip

Thank you for helping. Bug reports, tests on phones, translations, documentation and code are
all welcome.

## Contents

- [The most useful help right now](#the-most-useful-help-right-now)
- [Ground rules for code](#ground-rules-for-code)
- [Workflow](#workflow)
- [Licence of contributions](#licence-of-contributions)
- [Code of conduct](#code-of-conduct)

## The most useful help right now

1. Testing on real phones. Record for an hour with the screen locked, try a Bluetooth or
   USB microphone, stereo, airplane mode, force-stopping the app mid-recording. Report what
   happened with the phone model and Android version.
2. Independent verification. Take a recording and try to verify it *without* our code,
   following `VERIFY.txt`. Tell us where the instructions are unclear.
3. Battery guides for brands other than Samsung and OPPO/OnePlus (Xiaomi, Huawei, Honor,
   Motorola, Vivo…), with the exact menu names on a current phone.
4. Feedback from lawyers in Italy or other EU countries on
   [docs/TRIAL-GUARANTEES.md](docs/TRIAL-GUARANTEES.md).

Security problems go through [SECURITY.md](SECURITY.md), never a public issue.

## Ground rules for code

WitnessClip is evidence software. Some rules exist because an opposing expert will read the
code looking for weaknesses:

- Never weaken the seal. No pause, edit, trim or "fix" of recorded audio. Anything that
  touches what is hashed, signed or timestamped needs a test that a tampered file fails.
- Never overclaim. Text shown to users or in the report must not say more than the
  verifier actually proves.
- The verifier never throws and bounds every size before allocating. Add a hostile-file
  test for new parsing code.
- No new network destinations in the app, no telemetry, no proprietary SDKs, and no Google Play
  Services. Audio must never leave the phone unless the user exports it.
- New dependencies must be free software compatible with GPL-3.0, added to
  `gradle/verification-metadata.xml` and to [docs/DEPENDENCIES.md](docs/DEPENDENCIES.md).
  Fewer is better.
- Keep the build reproducible. Run `tools/reproduce.sh` if you touch build files.
- Changes to the evidence format (`audio-evidence/1`) must keep old files verifiable.
- User-visible text is in English and Italian (`Strings.kt`).

## Workflow

1. For anything larger than a small fix, open an issue first so we can agree on the approach.
2. Fork, create a branch, keep commits focused.
3. Before opening a pull request:

   ```bash
   ./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

   ```bash
   ./gradlew :core:test -Dlive=1
   ```

   The second command contacts the real timestamp services; run it if you touched time or
   verification code.
4. Fill in the pull request template.

## Licence of contributions

By contributing, you agree that your contribution is licensed under the
GPL-3.0-or-later, the same licence as the project, and that you have the right to submit
it. Please add a `Signed-off-by:` line to your commits (`git commit -s`) to confirm this, as
described by the [Developer Certificate of Origin](https://developercertificate.org/).

The name WitnessClip and the icon are not covered by the code licence; see
[TRADEMARK.md](TRADEMARK.md).

## Code of conduct

Be respectful and constructive. People using this app may be in difficult situations; keep
discussions free of judgement about why someone needs to record.
