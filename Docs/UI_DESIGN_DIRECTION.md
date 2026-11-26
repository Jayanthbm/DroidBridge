# UI design direction

Status: **approved by the owner on 2026-11-26 and implemented the same night** as a first shell (not a
design-polished one). The decisions: launch opens the main window for v1 (menu-bar-only is deferred); the
main window and the viewer are two equal citizens with no hero tab; the clipboard lives in the menu-bar
popover *and* in the main window; the HUD stays minimal (dot + `WxH · fps`, counters move to Diagnostics);
the viewer captures input only when focused, by click-to-control (not implemented yet). Live evidence:
`Docs/design/shell-main-live.png` and `Docs/design/shell-viewer-live.png`. Sketches:
`Docs/design/mock-windows.html` (open in a browser; it needs no network). Sections 7 and 8 are kept as
written, with the answers marked; anything still open is listed there.

## 1. Where the UI is today

Both apps are still shaped like labs, because that is what they had to be:

- **macOS**: one `WindowGroup` holding a `TabView` of six tabs - Dashboard, Advertisement, Pasteboard,
  Video, Live mirror, HID. Each tab is a set of prototype controls with counters in the label text
  ("frames 2144 · 3.2 MB · keyframe requests 0"). Nothing is grouped by what the user is trying to do.
- **Android**: one long scrolling dashboard - Connection, Clipboard, Sync, Screen with `Share this
  screen`, Nearby. Functional, but the primary action sits below the fold and the page mixes pairing,
  clipboard and mirroring in one column.

The protocol work underneath is real; this is only about the surface.

## 2. The shape the product actually wants

DroidBridge is really two things with different rhythms:

1. **A management surface** - pair the phone, see that both sides are connected, pause clipboard, change
   settings. Opened occasionally, closed again.
2. **A viewer** - the phone screen, on a second display or beside the Mac's work. Left open for long
   stretches, ideally with no chrome at all.

One tabbed window forces those two rhythms into one place. The recommendation is **two windows and a
menu-bar extra**, which is also how mature macOS utilities behave (QuickTime Player, Simulator, Screen
Sharing).

## 3. Recommended structure

### Window 1 - "DroidBridge" (main window)

A `NavigationSplitView`: a 220 pt sidebar and a detail pane.

- **Sidebar**, top to bottom: the device card (phone name, `Android 17`, status dot + one line of state,
  and the pairing code when it is showing); then `Clipboard`, `Screen`, `Audio`, `Settings`; then a
  footer with the app version and a "Diagnostics" item that opens the technical pane.
- **Detail**, default = the device overview: what is paired, when it last synced, and the two primary
  actions (`Send clipboard now`, `Share this screen`).
- Status is one line of text plus a dot: `idle`, `looking for the Mac`, `connecting`, `confirm 051 647`,
  `connected`, `sharing 590x1280`, `stopped: session lost`. No separate banner for each phase.

### Window 2 - "Screen" (viewer)

Created by `Share this screen` (or by opening the phone's screen from the main window).

- The mirrored frame, aspect-fit, on black, no border, no title bar chrome beyond the standard traffic
  lights; the window keeps the phone's aspect ratio when it resizes.
- A HUD that fades in on mouse move and out after ~2 s: status dot and `590x1280 · 30 fps` on the left,
  and on the right `Pause`, `Full screen`, `Stop sharing`.
- Keyboard: `Space` pause, `Esc` leave full screen, `Cmd-W` stop sharing and close.
- The window never takes key focus on its own; it is a viewer first.

### Menu-bar extra

A status dot, `Open DroidBridge`, `Stop sharing` (enabled only while sharing), and `Quit`. Useful when
the main window is closed but the session is live - which is the normal state for a second-screen
workflow.

## 4. States to design once, then reuse

| State | Main window | Viewer |
| --- | --- | --- |
| `idle` | "Not paired" + `Find the Mac` | not open |
| `discovering` | "Looking for the Mac…" | not open |
| `confirming` | `051 647` under the device name, both sides | not open |
| `connected` | dot green, `Send clipboard now` enabled | not open |
| `sharing` | `Stop sharing`, format line under Screen | live frames |
| `degraded` | amber: `reconnecting (attempt 2)` | frozen last frame, dimmed |
| `stopped` | `stopped: <reason>` persists until the user acts | closes, or shows the reason |

## 5. Visual language (defaults to change, not a brand decision)

- System fonts (SF Pro / Roboto), 13 pt body, 11 pt captions, 8 pt spacing grid, 10 pt corners.
- SF Symbols / Material icons only; no custom icon set for now.
- Status colours: gray idle, blue connecting, green connected, amber degraded, red failed. Always with
  text, never colour alone.
- Light and dark both; the viewer is black in both.
- No custom accent colour until the owner picks one, so `systemBlue` / Material dynamic colour is the
  placeholder.

## 6. Android side

Keep one scrolling page (the app is used in bursts and thumb reach matters more than navigation), but
reorder it around the primary action:

1. **Connection** card at the top, unchanged.
2. **Screen** card second, with `Share this screen` as a filled button and the format line under it.
3. **Clipboard** card third, with `Send phone clipboard` and `Pause sync`.
4. **Nearby** last, as today (the system picker already does most of this).

Material 3 dynamic colour, one `FilledTonalButton` per card, and the existing persistent notification
keeps `Stop`.

## 7. Open questions for the owner

1. **Which window opens on launch** - **answered:** the main window, for v1. Menu-bar-only launch is a
   later change and needs an app-level bootstrap (the listener must start without a window).
2. **Centre of gravity** - **answered:** neither; the main window and the viewer are equal citizens, and
   there is no hero tab. The viewer is opened from the overview, the screen pane and the menu bar.
3. **Technical counters** - **answered:** a minimal HUD over the video (dot + `WxH · fps`); every counter
   lives in Diagnostics.
4. **Brand** - **still open.** The shell ships the system look (system font, accent, no icon work).
5. **Clipboard placement** - **answered:** both. The menu-bar popover carries the clipboard line and the
   send action; the main window has the full `Clipboard` pane.
6. **Phone navigation** - **still open.** The Android app keeps its single scrolling page for now; the
   connect flow is the first thing to fix there (the picker grant is not the session - the user still has
   to press `Connect` in the app's `Nearby` card, and nothing prompts them to).

Answers to 1, 2 and 5 decide most of the layout work; 3 and 4 are cosmetic and can wait.

## 8. What it costs in code

Small and mechanical, because the features already exist as separate views:

- macOS: **done.** The `TabView` is a `NavigationSplitView` main window; `DashboardView`,
  `PasteboardView` and `LiveMirrorView` are detail panes, and `AdvertisementView` /`VideoView` /
  `HIDRemoteView` sit behind the Diagnostics item. The viewer is **not** a second window scene - that
  made macOS launch the viewer alone and skip the main window - it is an AppKit `NSWindow` hosted by
  `ViewerWindow` (ADR-059). No protocol or transport change.
- Android: reorder the three cards and promote `Share this screen` to a filled button. No protocol
  change.

Neither is a Phase 0 prerequisite: Phase 0 is the build/CI phase, and the UI work lands with the
feature phases that own each screen.
