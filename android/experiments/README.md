# Android experiment apps

Each directory here is a **separate installable app** with its own `applicationId`, built to answer one
feasibility question on a real device. None of them is a product feature, and **none is ever depended
on by `:app`** — the shipping app must not inherit an experiment's permissions, activities or
dependencies.

| Module | Prototype | Answer it holds | Evidence document |
| --- | --- | --- | --- |
| `media-projection/` | P01 | MediaProjection consent, capture of protected vs unprotected surfaces | `Docs/prototypes/P01-media-projection.md` |
| `discovery/` | P02 | NSD advertisement/resolution and `ACCESS_LOCAL_NETWORK` on API 37 | `Docs/prototypes/P02-local-networking.md` |
| `session/` | P03, P13 | Live TLS 1.3 handshake, SPKI pinning, pairing state | `Docs/prototypes/P03-live-handshake-evidence.md`, `Docs/prototypes/P13-pinned-tls-android17.md` |
| `clipboard/` | P04 | Clipboard read access and focus rules (declares no permission) | `Docs/prototypes/` clipboard entries |
| `clipboard-receive/` | P05 | Mac-to-Android paste over a real session | `Docs/prototypes/P05-clipboard-mac-to-android.md` |
| `background-session/` | P14 | Foreground-service persistence, Doze and restart behaviour | `Docs/prototypes/P14-background-persistence.md` |
| `audio-capture/` | P12 | `AudioPlaybackCaptureConfiguration` boundaries (media vs calls), measured PCM | `Docs/prototypes/P12-audio-calls.md` |
| `tone-player/` | P12 | Deterministic 440 Hz source with selectable capture policy and usage class | `Docs/prototypes/P12-audio-calls.md` |
| `av-sync-stimulus/` | P12 | One trigger fires a white flash and a 150 ms 1 kHz beep together, so the recorded timelines measure the A/V offset | `Docs/prototypes/P12-audio-calls.md` |
| `bluetooth-hid/` | P16, P17 | Android-side HID receiver for the arrow-key delivery tests | `Docs/prototypes/P16-ble-hid.md`, `Docs/prototypes/P17-classic-hid.md` |

## Why they stay

The prototype documents cite these paths and their `applicationId`s, and the measurements were taken
from builds of them. Deleting a module would break the reproducibility of an evidence row, so they are
kept until the prototype that owns them is formally closed out in `Docs/PHASES_AND_ACCEPTANCE.md`.
Removing the built APKs or uninstalling the apps from a phone changes nothing here: both are rebuildable
with the commands below.

## Building and installing one

```bash
cd android
./gradlew :experiments:<module>:assembleDebug
adb install -r experiments/<module>/build/outputs/apk/debug/<module>-debug.apk
```

`audio-capture` and `tone-player` are a pair: the first captures, the second plays. `tone-player` takes
`--es policy allow|none`, `--es usage media|voice|game|unknown|notification` and `--ei seconds N`.

## The separation rule, verified

` :app` depends on `:core:*` and `:feature:*` only. To re-check after any build-file edit:

```bash
./gradlew :app:dependencies --configuration debugRuntimeClasspath | grep experiments   # must print nothing
```

Experiment apps may use production code (for example `background-session` reuses the shipping
`SecureSession` and `KeystorePinStore`); the dependency direction is experiment → production, never the
reverse.
