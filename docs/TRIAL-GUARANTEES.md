# What a WitnessClip recording proves

Version 0.1.0, November 2026.

This is for the people who will look hard at a recording: the lawyer who files it, a
consultant appointed by the court, or the other side's audio expert. It describes the
technical properties of the file and how to check each one. It is not legal advice, and
whether a recording is admitted, and how much weight it gets, is up to the court.

For how the app is built, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Contents

- [Summary](#summary)
- [1. Integrity](#1-integrity)
- [2. Existence at a certified time](#2-existence-at-a-certified-time)
- [3. Not made earlier](#3-not-made-earlier)
- [4. Recorded in real time](#4-recorded-in-real-time)
- [5. Continuity](#5-continuity)
- [6. Same device](#6-same-device)
- [7. Genuine phone and official app](#7-genuine-phone-and-official-app)
- [Strength by situation](#strength-by-situation)
- [Checking a recording independently](#checking-a-recording-independently)

## Summary

A WitnessClip recording is an ordinary FLAC audio file with its proof stored inside it.
Anyone holding the file can check the following, without the app and without contacting us:

1. **Integrity.** Not a single sample has changed since the recording was sealed.
2. **Existence at a certified time.** The whole recording existed, unchanged, at a moment
   certified by an EU qualified timestamp service.
3. **Not made earlier.** If the phone was online at the start, the key that signed the
   recording did not exist before the opening timestamp.
4. **Recorded in real time.** The amount of audio matches the time that passed between the
   certified moments, so there was no window to prepare or edit material.
5. **Continuity.** Every interruption is recorded in the seal. The app has no pause and no
   edit function.
6. **Same device.** Recordings made by the same installation carry the same Device ID,
   backed by a key that cannot leave the phone.
7. **Genuine phone and official app**, on phones that provide full hardware attestation.

Each of these is explained below, with what it rests on and how to check it.

## 1. Integrity

While recording, the audio is cut into one-second slices. Each slice is hashed with SHA-256
and the hash is added to a Merkle tree built as in RFC 6962, the structure used by
Certificate Transparency. Every ten seconds a key held in the phone's secure hardware signs
the current state of the tree, and at the end the complete list of hashes is signed.

Changing, removing, adding or reordering even one sample changes a hash, and with it the
root that was signed and timestamped. No practical way is known to produce different audio
with the same SHA-256 hash.

To check it, decode the audio, hash each second and compare with the list in the file. The
verifier reports the exact second where a difference starts.

The hashes are computed on the decoded samples, not on the bytes of the file. That is why the
proof can travel inside the audio file, and why any copy either verifies completely or fails.

## 2. Existence at a certified time

When the recording is stopped, the SHA-256 of the signed manifest is sent to two qualified
timestamp services, from different providers when possible. Each one returns an RFC 3161
token, signed by the service, stating that this hash existed at that time. Only the hash is
sent. The audio never leaves the phone.

The services are Sectigo (Europe) SL, ACCV and Izenpe. All three are listed as qualified
timestamp services with status "granted" on Spain's national trusted list, and the app only
accepts tokens signed by the exact keys of those services. Under Regulation (EU) 910/2014
(eIDAS), art. 41(2), a qualified timestamp is presumed accurate as to date and time and as to
the integrity of the data it covers, in every Member State.

From that moment the recording is fixed. A later change would be detected and could not be
timestamped again with the earlier date.

If the phone was offline, the tokens are requested automatically the next time it connects.
The certified time is then that later moment, and the report says so.

To check it, use `openssl ts -verify` or any qualified signature validator on the token and
the manifest.

## 3. Not made earlier

At the start, if the phone is online, the app gets a qualified timestamp over a fresh random
number. It then asks the phone's secure hardware to create the recording key, passing a hash
of that timestamp as the attestation challenge. The hardware writes the challenge into the
certificate it issues for the key.

The certificate therefore shows that the key was created after the timestamp, and every
signature in the recording was made after that moment. The recording cannot be back-dated
to before its opening timestamp.

This relies on hardware attestation (section 7). On a phone without it, the start time is
whatever the phone's clock said, and only section 2 fixes the recording in time.

## 4. Recorded in real time

Three kinds of certified moments surround the audio: the opening timestamp, a timestamp of
the signed state every five minutes while recording, and the closing timestamps. The
verifier compares the amount of audio between any two of them with the time that passed.

An hour of audio sits between certified moments an hour apart, and each five-minute block
was already sealed when its timestamp was issued. Swapping or inserting material would have
had to happen live, inside the phone, while the conversation was going on.

This needs a connection during the recording. Offline, only the closing timestamp exists and
the argument rests on sections 1, 5 and 7.

## 5. Continuity

The app has no pause, trim or edit function. The only controls are start, stop, mark a moment
and add a note. Marks and notes are sealed with their position and never touch the audio.

Each one-second entry carries the audio hardware's clock and the wall clock, and the verifier
compares the number of samples with the time that passed on the device.

Anything that interrupts the microphone is sealed as an event at its exact position: the
system taking the microphone (during a phone call, for instance), an input failure, a change
of microphone, storage running out, the app being killed.

A removed stretch would show up as a mismatch between samples and clock, or would break the
hash tree. A real interruption is visible and dated in the file rather than hidden.

If the app is killed or the phone dies, the recording ends there. What was captured is
recovered and sealed at the next start, marked as interrupted: everything up to the last
second if the app was killed, and all but at most the last five seconds after a sudden loss of
power. The time between the interruption and the next start is not covered.

## 6. Same device

Each installation creates one key inside the secure hardware. The hash of its public key is
the Device ID shown in Settings and in every report, and this key signs an endorsement of
each recording's own key.

Two recordings with the same Device ID were made by the same installation on the same phone.
The key cannot be copied to another device and is not included in backups, so reinstalling
the app or changing phone gives a new Device ID.

The Device ID identifies a phone and an installation, not a person.

## 7. Genuine phone and official app

For a key held in secure hardware, Android phones can issue a certificate chain ending in a
Google root. It states that the key is in secure hardware, that the phone booted verified
software with a locked bootloader, and which app, by package name and signing certificate,
created the key.

When this is confirmed, the recording was made on an unmodified phone by the official app,
not by an altered copy and not in an emulator.

Not every phone provides a complete attestation, and some fail to for reasons unrelated to
tampering. The app and the report mention this point only when it is positively confirmed.
When it is not, the other sections still hold. The raw certificates are always in the file
for an expert to examine.

The release signing certificate is published with each release and is the value that appears
in the attestation. Because the app builds reproducibly from public source
([REPRODUCIBLE.md](REPRODUCIBLE.md)), an expert can confirm that the app which made a recording
is exactly the published source code.

## Strength by situation

| Situation | Integrity | Fixed in time | Not made earlier | Real time | Continuity |
|---|---|---|---|---|---|
| Online throughout, attestation confirmed | yes | from the stop | yes | every 5 min | yes |
| Online throughout, attestation not confirmed | yes | from the stop | weaker, rests on the phone | every 5 min | yes |
| Online only at start and stop | yes | from the stop | yes | start to stop | yes |
| Offline, sealed later | yes | from the later sealing | no | no | yes |
| Interrupted (app killed, battery) | up to the last second written | from sealing at next start | as above | up to the last intermediate timestamp | ends at the interruption, which is declared |

## Checking a recording independently

Everything needed is inside the file. Nothing depends on the author being available.

- The file is the one produced: `sha256sum`, compared with the value in the report.
- The audio matches its seal: decode with `ffmpeg`, hash each second with `sha256sum`.
- The timestamps: `openssl ts -verify`, and the EU Trusted List to confirm the issuers.
- Signatures and certificate chains: `openssl cms -verify` and `openssl x509`.
- All of the above in one run: `./gradlew :core:run --args="recording.flac"` with the
  open-source verifier.
- That the app and verifier do what this document says: read the source, rebuild the app and
  compare it with the published one.

Step-by-step commands are in the `VERIFY.txt` inside every evidence package, and the file
format is documented, so an expert can write their own verifier.

For filing, the app exports an ASiC-E package (the EU standard container for signed files)
containing the audio, a PDF/A report and a CAdES signature. A lawyer can add their own
qualified signature on top. Long recordings can be split into parts of 25 MB, and each part
verifies on its own against the same seal.
