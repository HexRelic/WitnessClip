# Security policy

WitnessClip produces evidence for courts. A flaw that lets someone forge, alter or back-date a
recording without detection harms real people in real proceedings, so security reports get
priority over everything else.

## Contents

- [Reporting a vulnerability](#reporting-a-vulnerability)
- [What counts most](#what-counts-most)
- [Out of scope](#out-of-scope)
- [Supported versions](#supported-versions)
- [Checking that your copy is genuine](#checking-that-your-copy-is-genuine)

## Reporting a vulnerability

**Please do not open a public issue.**

Report privately, by either:

- GitHub's private reporting: the **Security** tab of this repository → **Report a vulnerability**
- Email: [roundish-old-comic@duck.com](mailto:roundish-old-comic@duck.com)

Please include:

- what an attacker can achieve, in one sentence;
- the steps or a file that shows it (a crafted `.flac` is ideal for verifier issues);
- the app or verifier version, and the phone and Android version if relevant.

You will get an acknowledgement within **7 days** and an assessment within **30 days**. We
will agree a disclosure date with you, normally no later than 90 days after the report, and
credit you in the release notes unless you prefer otherwise.

There is no paid bug bounty.

## What counts most

| Severity | Examples |
|---|---|
| **Critical** | The verifier reports a modified, spliced, re-ordered or back-dated recording as valid · a timestamp not from a pinned qualified service is accepted · a recording can be signed in another session's or device's name |
| **High** | A crafted file crashes or hangs the verifier or the app · a sealed recording can be lost or silently corrupted · audio, notes or location leave the phone without the user exporting them |
| **Medium** | The app lock can be bypassed · a recording stops without the user and without a declared reason · an APK we publish fails the structure check |
| **Low** | Information shown to the user is misleading but the proof itself is sound |

## Out of scope

- Attacks needing a rooted phone, an unlocked bootloader or physical access to an unlocked
  phone.
- Whether what is said in a recording is true, or sound played into the microphone.
- Problems in the timestamp services themselves; report those to the service operator.
- Modified versions of the app not built by this project.

## Supported versions

Only the latest release receives security fixes. Recordings made with older versions stay
verifiable: the verifier keeps reading every published format version.

## Checking that your copy is genuine

Compare the signing certificate of your APK with the one published in
[docs/REPRODUCIBLE.md](docs/REPRODUCIBLE.md), or rebuild it from source and compare.
