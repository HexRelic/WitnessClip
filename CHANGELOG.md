# Changelog

All notable changes to WitnessClip. Format based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow
[Semantic Versioning](https://semver.org/). The store changelog for each release is in
`fastlane/metadata/android/<language>/changelogs/<versionCode>.txt`.

## [0.1.1] - 2026-11-28

### Fixed
- A sudden power cut during a recording now loses at most the last five seconds instead of up
  to about thirty: the log of sealed seconds is forced to disk together with the audio.
- The verifier reports a damaged or altered file as a failed check in every case, never as a
  crash or an endless check. Evidence that unpacks to far more than its own size is refused,
  running out of memory is a failed check, long evidence is read in one pass, and intermediate
  timestamps are matched to their checkpoint once.
- The verifier refuses audio frames that ffmpeg or libFLAC would skip or decode differently,
  so a file cannot pass the check while ordinary players play something else.
- Adding the closing timestamps keeps the file's tags, pictures and other metadata.
- Hexadecimal values in a recording's proof are read as plain ASCII digits only.

### Documentation
- Privacy policy published in the repository, in English and Italian (`docs/legal/`).

## [0.1.0] - 2026-11-20

First release.

### Recording
- Lossless 48 kHz, 24-bit capture from the unprocessed microphone source, with one constant,
  declared gain; mono or stereo.
- Phone, wired, USB or Bluetooth microphone; changes of input are sealed.
- Keeps recording with the screen off; declared gaps when the system takes the microphone;
  automatic reopening after an input failure; clean stop when storage runs out.
- Mark a moment, add a note. No pause, no edit.
- Crash recovery: an interrupted recording is sealed at the next start.
- Optional location at start and stop, declared as not certified.

### Proof
- SHA-256 of every second in an RFC 6962 Merkle log, signed every 10 seconds by a hardware
  key with Android key attestation, endorsed by a per-installation device key.
- eIDAS qualified timestamps (Sectigo, ACCV, Izenpe) at start, every 5 minutes and twice at
  the end, pinned to the services on the EU trusted list.
- Self-proving FLAC file carrying its whole proof.
- Verifier in the app and on the command line.

### Export
- Save the audio file, ASiC-E evidence package with CAdES signature, split export into parts
  of 25 MB that each verify on their own.
- PDF/A-2b report in Italian or English.

### App
- Light and dark theme, Italian and English, dashboard, recording names, discreet screen,
  default save folder, optional app lock, battery guides for Samsung and OPPO/OnePlus.
- Legal pages: terms of use, privacy policy, licence, third-party software and use of AI.

### Build
- Reproducible release build, dependencies pinned by SHA-256, APK structure check against
  malformed ZIP entries, scripted signing.

[0.1.1]: https://github.com/HexRelic/WitnessClip/releases/tag/v0.1.1
[0.1.0]: https://github.com/HexRelic/WitnessClip/releases/tag/v0.1.0
