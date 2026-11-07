# Test Plan

## 1. Test environments

Primary:
- Samsung Galaxy S24 Ultra, India region, Android 17 / One UI 9 (owner-reported).
- Owner's Mac, Apple Silicon if confirmed.
- Same trusted Wi-Fi network.

Secondary:
- Android emulator for UI and protocol unit tests only.
- Additional Android devices later for compatibility.

## 2. Development vs release test modes

Development:
- USB/ADB may be used for install, logs, profiling and test automation.
- Record when debugging is enabled.

Release acceptance:
- USB disconnected.
- USB debugging disabled.
- Wireless debugging disabled.
- Same Wi-Fi only.
- No Accessibility enabled for DroidBridge.
- No root.

## 3. Pairing and network tests

- first discovery.
- correct pairing code.
- incorrect pairing code.
- unknown peer.
- revoke peer.
- Wi-Fi disconnect/reconnect.
- device IP change.
- router reboot.
- guest network/client isolation.
- LAN permission denied/revoked.
- app force-stop/relaunch.
- Mac sleep/wake.
- duplicate service names.
- no internet connection but same LAN available.

## 4. Clipboard tests

Mac → Android:
- plain text.
- multiline.
- emoji and non-Latin scripts.
- long text.
- empty clipboard.
- rapid successive copies.
- pause/resume.
- app foreground/background.
- Android clipboard system UI.
- no echo loop.

Android → Mac:
- manual send with app foreground.
- app background.
- app process killed.
- screen locked.
- no clipboard content.
- duplicate content.
- sensitive flag.
- synthetic OTP/password strings.
- no clipboard content in logs.

## 5. Mirroring tests

- consent granted/denied.
- fresh consent for new sessions.
- home screen.
- app switching.
- portrait/landscape.
- resolution changes.
- notification shade.
- screen lock.
- system projection stop chip.
- phone disconnect.
- Mac disconnect.
- foreground service notification.
- 30-minute session.
- repeated start/stop.
- low Wi-Fi signal.
- protected surfaces and FLAG_SECURE.

## 6. Security tests

- unknown peer connection.
- wrong certificate/pin.
- replayed messages.
- malformed messages.
- oversized clipboard payload.
- invalid video frame lengths.
- unauthenticated port scan on LAN.
- no internet egress.
- no content in logs.
- revoke pairing.
- app uninstall/reinstall behaviour.
- Keychain/Keystore storage review.

## 7. Performance metrics

Record:
- pairing time.
- discovery time.
- clipboard delivery latency.
- screen startup latency.
- end-to-end video latency.
- frame rate.
- bitrate.
- CPU and memory.
- phone temperature/battery drain.
- Mac CPU/GPU.
- reconnect time.

Targets are provisional and must be set after baseline measurements. Do not claim performance targets passed without measured data.

## 8. Release acceptance

- both apps build from clean checkout.
- all core tests pass.
- no prohibited permission/service.
- release acceptance completed without USB/debugging.
- same Wi-Fi works.
- Bluetooth disabled still works.
- secure screens respected.
- no cloud dependency.
- known remote-control limitations documented.
