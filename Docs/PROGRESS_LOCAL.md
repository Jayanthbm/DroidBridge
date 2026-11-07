# Local Development Progress

**Status:** Documentation pack prepared; repository not yet created. Research-first, research commit, and one-phase-at-a-time workflow with owner checkpoints defined.  
**Last updated:** 2026-11-07

## Device and environment
- Android device: Samsung Galaxy S24 Ultra.
- Region: India.
- Android: 17 (owner-reported).
- One UI: 9 (owner-reported).
- Mac model/macOS/Xcode: To be recorded when development begins.
- USB debugging: Development-only allowance; final release must not require it.
- Bluetooth: Optional.

## Completed
- [x] Product concept and constraints documented.
- [x] Two-native-app direction documented.
- [x] Wi-Fi-first / optional Bluetooth decision recorded.
- [x] Research baseline added.
- [x] Prototype plan added.
- [x] Security and permission policies added.
- [x] Agent workflow added.

## Not started
- [ ] Create Git repository.
- [ ] Create Android project shell.
- [ ] Create macOS project shell.
- [ ] Capture exact device build/API details.
- [ ] Run P00 environment baseline.
- [ ] Run P01 MediaProjection prototype.
- [ ] Run P02 Android 17 LAN permission prototype.
- [ ] Run P03 pairing/transport prototype.
- [ ] Run clipboard prototypes.
- [ ] Run video receiver/stream prototypes.
- [ ] Run remote input/HID experiments.

## Current blockers
- Full remote input without Accessibility/ADB/root is not proven.
- Android 17 LAN permission path must be tested on target firmware.
- Background clipboard behaviour must be measured.
- Mac-as-Bluetooth-HID-peripheral capability is not proven.

## Mandatory workflow from repository creation
1. Create the repository and commit the documentation pack.
2. Complete the research stage before production implementation; commit all research and evidence to Git.
3. Present the research report and proceed to Phase 0 unless a specific blocker requires owner input.
4. Execute one build phase only, commit and report it, then wait for owner input before the next phase.

## Next task
Create the repository from this documentation pack and use `Docs/AI_AGENT_RESUME_START_PROMPT.md`. The agent must inspect the actual repo and begin with the research gate, not jump into implementation.
