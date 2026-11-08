# DroidBridge shared protocol module

Platform-independent protocol logic that both apps must agree on byte for byte. It is a plain
Kotlin/JVM library with no Android or Apple dependencies, so it builds and tests anywhere a JDK
exists — including a Linux CI or cloud agent, which is why the framing rules live here rather
than inside the Android app.

## What is here now

H.264 media framing for the mirroring channel (ADR-027, `RESEARCH_AND_FEASIBILITY.md` R-29–R-31,
`NETWORK_PROTOCOL.md` §7):

| Type | Responsibility |
| --- | --- |
| `AnnexB` | Splits MediaCodec's Annex-B output into NAL units: both start-code lengths, trailing-zero padding, and rejection of empty or unframed input. |
| `H264ParameterSets` | Turns `csd-0`/`csd-1` into the bare SPS and PPS that `CMVideoFormatDescriptionCreateFromH264ParameterSets` requires, and refuses start-coded or multi-NAL buffers. |
| `AnnexBToAvcc` | Converts one access unit into `[4-byte big-endian length][NAL]` and reports whether it carries an IDR slice. |
| `AccessUnit` | A converted frame: AVCC payload, producer `ptsUs`, keyframe flag. |
| `MirrorFormat` | The `mirror.format` payload, including its deterministic JSON encoding with Base64 parameter sets. |

The conversion runs on the **producer** (Android) so that a framing mistake fails next to the code
that made it, and so the wire format has exactly one definition.

## What is deliberately not here

- No Android APIs, no `MediaCodec`, no `MediaProjection`. Capture and encoding need the target
  device and cannot be verified in this module.
- No Apple APIs. The receiving side (format description, `CMSampleBuffer`, display layer) is Swift
  and lives in `macos/`; its correctness is proven by `P07`, not here.
- No TLS, discovery or session logic yet.

## Running the tests

```bash
cd shared/protocol
./gradlew test
```

Requires a JDK 21 toolchain. The Gradle wrapper pins the Gradle version, so no global Gradle
install is needed. The Android app can later consume this as a Gradle module instead of a copy.

## Test vectors

`src/test/kotlin/.../TestVectors.kt` holds the fixed NAL units and access units used by the tests,
and the same vectors are what the macOS side should be tested against once `P07` exists.
