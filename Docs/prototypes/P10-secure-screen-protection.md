# P10 — Secure screen and screenshare-protection behaviour

**Status: in progress (2026-11-12).** Synthetic `FLAG_SECURE` protection in the mirrored stream has been exercised and observed to behave correctly (black/blank when secure, restored when cleared). A non-invasive banking-app observation (Slice) also showed protected output. The notification-shade, OTP-notification, password-field and lock-screen cases have now been exercised, with their objective (notification/ui/log/storage) evidence recorded below; the *mirrored appearance* for the password field and both OTP variants is an owner visual check that is still outstanding.

> This report records evidence only. No attempt is made to bypass `FLAG_SECURE`, DRM, banking-app protections or protected surfaces (prohibited by AGENTS.md / security policy).

| Field | Value |
|---|---|
| Prototype | P10 — secure screen/protection in end-to-end mirrored output |
| Depends on | P01/P08 (P01 complete; P08 live mirroring working, P08 acceptance not fully closed) |
| Android device | Samsung Galaxy S24 Ultra, **SM-S928B** (model reported in session; Android 17 / API 37, One UI 9.0 per prior project baseline) |
| Mac | MacBook Air (M1, 2020), macOS 27.0.1, Xcode 27.2; rebuilt Debug `DroidBridge.app` (`--p08-mirror`) with live PTS/timebase fix (`8937f3d`) |
| Mirroring experiment app | `com.droidbridge.experiment.mirroring` debug build including synthetic FLAG_SECURE toggle (`899cc87`) |
| Commits (relevant) | `92bb313` (Android mirroring start off main thread), `accae04`/`8937f3d` (Mac live mirror + PTS rebase), `899cc87` (P10 FLAG_SECURE toggle) |
| ADB/debug state at log capture (2026-11-12) | `adb devices -l` showed `192.168.31.106:5555 device ... transport_id:2` (Wi-Fi ADB) only — **USB connected: no** per adb listing at capture time; **USB debugging: not determined from this host**; **wireless debugging/ADB over Wi-Fi: yes** (device reachable at `192.168.31.106:5555`). Earlier same-day sessions included USB/USB-debugging; record the authoritative state per test run. The sections added later in the day (11:18–11:26) ran in the same state: **USB not connected**, wireless ADB on `192.168.31.106:5555`, no USB debugging possible without a cable. |

## Evidence

### 1) Synthetic `FLAG_SECURE` window (own experiment app) — observed correct

**Goal:** with mirroring active, toggling `WindowManager.LayoutParams.FLAG_SECURE` on the mirroring experiment window should cause the mirrored view to show black/blank/unavailable for that protected window, and clearing it should restore the live image. No protection bypass.

**Implementation (scoped to experiment):** `MirroringActivity` sets/clears `FLAG_SECURE` on its window via a UI toggle and logs `P10 FLAG_SECURE test: ON/OFF` (`899cc87`).

**Session/log evidence (S24, `adb -s 192.168.31.106:5555 logcat -d -s P08Mirroring`, 2026-11-12):**
- Fresh pairing after clearing stale Mac pin: forgot pin for `DroidBridge-P08-Mac` (09:51:32.721), discover/resolve `192.168.31.109:59490`, handshake `TLSv1.3` `authenticated=false` code `609218` (09:51:36.827), pairing confirmed and pin persisted (09:51:45.354), `peerPin=3b3d878a9b77bf4436d139b36a032a07ac3c5bdcd569fd77971e1384f7578fb7`.
- Mirroring started: consent requested/granted (09:51:53.003 / 09:51:56.156), service started (09:51:56.432), pipeline `590x1280` from content `1080x2340` encoder `c2.qti.avc.encoder`, `mirror.format` sent (09:51:56.517). Session remained active with `dropped=0`, `stopReason=active` (sampled frames 186→339, 09:52:01–09:52:26).
- **09:51:59.219** `P10 FLAG_SECURE test: ON (this window should appear black/blank in the mirror)`
- **09:52:01.307** `P10 FLAG_SECURE test: OFF`

**User-observed Mac result (2026-11-12):** when the toggle was turned **ON**, the Mac mirror showed a **black screen**; when turned **OFF**, the screen/image was visible again. This matches the expected protected-content behavior (FLAG_SECURE honoured in capture/mirror; no bypass).

**Result:** **Pass (synthetic FLAG_SECURE toggle, this run).** Protected window content was not shown in the mirror while secure; normal mirroring restored when cleared.

### 2) Banking app (Slice) — non-invasive observation, protected output observed

**Setup:** live mirroring active to Mac; user opened the Slice banking app on the S24 (non-invasive observation only — no interaction, no bypass attempt).

**User-observed Mac result (2026-11-12):** Mac mirror showed a **black screen** while Slice content was in the foreground.

**Result:** **Consistent with expected protected behavior** (protected/secure surface withheld from capture/mirror). Detailed session timing/log correlation for this specific observation was not captured in the log excerpt above; treat as a user-reported non-invasive observation pending a fully correlated evidence run if needed.

### 3) Notification shade while mirroring — stream keeps running

**Setup:** live session (`590x1280` from `1080x2340`), then `cmd statusbar expand-notifications`.

**Lock, same session set:** locking the device revokes the projection outright (`KEYCODE_SLEEP`,
`isKeyguardShowing=true` → `system onStop`, Mac `MIRROR_STOP`), so a lock screen leaves nothing to
mirror — recorded in full in the [P08 report](P08-live-mirroring.md#5-session-stops-when-mediaprojection-stops-u10).

**Recorded:** `dumpsys window` reported `mCurrentFocus=Window{… NotificationShade}` with the session
still `state=mirroring … frames=1764 dropped=0 stopReason=active` (`durationMs=145187`). The shade is
ordinary system UI, so the capture continues; the mirror shows it. This is the expected result — the
shade is not protected surface, and P10 only requires that protected content stay hidden.

### 4) Synthetic OTP notifications, with and without `setPublicVersion()` — posted, verdict pending

Both controls ran against the live session and are confirmed at the notification-service level with
`dumpsys notification --noredact`:

- **With public version** (`id=102`): the record carries
  `publicVersion=Notification(channel=p10-test … flags=0 …)`; the public version's content is
  `P10 notification` / `Synthetic notification (no code shown)`, while the private version holds
  `OTP-TEST-482913`.
- **Without public version** (`id=101`): the record has no `publicVersion` field at all.
- App log: `P10 synthetic OTP notification posted publicVersion=true` (11:23:49) and
  `… publicVersion=false` (11:23:59).

What the *mirror* displays for each is a visual observation, and this tooling has no way to capture
the Mac's rendered video (screen capture is not permitted for the agent process), so the visual
verdict for both notifications is **owner-observed and still outstanding**.

### 5) Password field — executed, verdict pending

The password test screen is a real `EditText` with `password="true"` (`uiautomator` dump), the field
was focused and `throwaway-1234` typed through the input system while the session was live, and the
stream stayed `state=mirroring … dropped=0`. Whether the mirrored output masks the characters is a
visual check on the Mac and is **still outstanding** for the same reason as above.

### 6) No protected frames cached in app storage

`run-as com.droidbridge.experiment.mirroring` after the runs: `files/` contains only
`profileInstalled` (24 bytes) and `cache/` is empty. No frame, screenshot or buffer file exists in
the app's sandbox, so nothing from the capture path is persisted by this app. (This covers the app's
own storage; it does not audit other processes or the system's own buffers.)

## Not yet tested (P10 remaining)

- **Visual verdicts** for the password field and both OTP notification variants — the states were
exercised and are captured at the notification/UI level, but the mirrored appearance must be observed
on the Mac and recorded by the owner.
- Additional banking/DRM apps by **non-invasive observation only** (no interaction, no bypass).
- Correlated evidence runs for each case with USB/USB-debugging/wireless-debugging state recorded per
  run (this session: USB not connected, wireless ADB on, `192.168.31.106:5555`).

## Running the cases

Mirroring must be live (Mac `P08 Mirror` listening, phone connected and capturing) before each case.
All four steps below were executed on 2026-11-12 (see sections 3–5); they stay here as the
reproduction procedure, and steps 1–3 still need the mirrored appearance recorded by eye:

1. **Password field** — tap *P10: Open password-field test screen*, focus the field, type throwaway
   text, watch the Mac. Back to the controls with *Back to mirroring controls*.
2. **OTP notification, no public version** — tap *P10: Post OTP notification (no public version)* and
   read the Mac (shade open and closed).
3. **OTP notification, with public version** — tap *P10: Post OTP notification (with public version)*
   and compare. The public version carries no code, so if the mirror shows a redacted form that is
   the expected result; if it shows the code that is a finding to record.
4. **Lock screen / shade** — lock the device and pull the shade down mid-session; record whether the
   stream stops (P01 showed lock fires `onStop()`), goes black, or shows unprotected content.

Record the Mac observation, `adb logcat -s P08Mirroring P08Encoder` around each step, and the
USB/debugging state for the run.

## Limitations

- Mac-side visual outcome for the synthetic toggle is user-confirmed; not independently screenshot-captured by this tooling.
- ADB state recorded here reflects the host view at capture time (Wi-Fi ADB reachable); per-run USB/debugging state must still be recorded explicitly for each acceptance-relevant test.
- Banking-app observation is non-invasive and user-reported; no protected content was inspected, extracted, or bypassed.

## Current assessment

**In progress, close to done.** Protected surfaces are withheld as required in every case that has been
observed so far (synthetic `FLAG_SECURE`, the Slice banking app), the lock-screen case cannot leak
because locking stops the capture, and the shade/notification/password cases are exercised with their
objective evidence recorded. What keeps P10 open is one class of evidence: the mirrored *appearance*
for the password field and the two OTP notification variants has to be seen on the Mac, and this
tooling cannot capture the rendered video.
