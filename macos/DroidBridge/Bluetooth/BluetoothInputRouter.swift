@preconcurrency import AppKit

private struct UncheckedEvent: @unchecked Sendable {
    let value: NSEvent
}

/// Translates focused mirror-window keyboard and pointer events into HIDP
/// reports. A local event monitor is enough: input is captured only when the
/// mirror window is key, so no Accessibility permission is required.
@MainActor
final class BluetoothInputRouter {
    private let hid: BluetoothHIDController
    var focusedDeviceID: () -> String?
    var phoneWidth: (String) -> Int

    private var monitor: Any?
    private var pressedKeys: Set<UInt8> = []
    private var modifiers: UInt8 = 0
    private var leftButtonDown = false
    private var middleButtonDown = false
    private var rightBackDown = false
    private var residualX: CGFloat = 0
    private var residualY: CGFloat = 0
    private var lastDeviceID: String?

    init(
        hid: BluetoothHIDController,
        focusedDeviceID: @escaping () -> String?,
        phoneWidth: @escaping (String) -> Int
    ) {
        self.hid = hid
        self.focusedDeviceID = focusedDeviceID
        self.phoneWidth = phoneWidth
    }

    func install() {
        guard monitor == nil else { return }
        let mask: NSEvent.EventTypeMask = [
            .keyDown, .keyUp, .flagsChanged,
            .mouseMoved, .leftMouseDown, .leftMouseUp, .leftMouseDragged,
            .rightMouseDown, .rightMouseUp, .rightMouseDragged,
            .otherMouseDown, .otherMouseUp, .otherMouseDragged,
            .scrollWheel,
        ]
        monitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            guard let self else { return event }
            let box = UncheckedEvent(value: event)
            let consumed = MainActor.assumeIsolated { self.handle(box.value) == nil }
            return consumed ? nil : event
        }
    }

    func uninstall() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }

    func release(deviceID: String? = nil) {
        guard let target = deviceID ?? lastDeviceID else { return }
        pressedKeys.removeAll()
        modifiers = 0
        leftButtonDown = false
        middleButtonDown = false
        rightBackDown = false
        residualX = 0
        residualY = 0
        hid.releaseAll(deviceID: target)
        if lastDeviceID == target { lastDeviceID = nil }
    }

    private func handle(_ event: NSEvent) -> NSEvent? {
        guard let deviceID = focusedDeviceID(), hid.isConnected(deviceID: deviceID) else {
            return event
        }
        switch event.type {
        case .keyDown, .keyUp, .flagsChanged:
            return handleKeyboard(event, deviceID: deviceID)
        case .scrollWheel:
            guard isOverStage(event) else { return event }
            lastDeviceID = deviceID
            sendMouse(deviceID: deviceID, wheel: Int8(clamping: Int(event.scrollingDeltaY.rounded())))
            return nil
        case .mouseMoved, .leftMouseDragged, .otherMouseDragged:
            guard isOverStage(event) || leftButtonDown || middleButtonDown else { return event }
            lastDeviceID = deviceID
            sendPointerDelta(event, deviceID: deviceID)
            return nil
        case .leftMouseDown:
            guard isOverStage(event) else { return event }
            lastDeviceID = deviceID
            leftButtonDown = true
            sendPointerDelta(event, deviceID: deviceID)
            return nil
        case .leftMouseUp:
            guard leftButtonDown else { return event }
            leftButtonDown = false
            sendPointerDelta(event, deviceID: deviceID)
            return nil
        case .otherMouseDown:
            guard isOverStage(event) else { return event }
            lastDeviceID = deviceID
            middleButtonDown = true
            sendPointerDelta(event, deviceID: deviceID)
            return nil
        case .otherMouseUp:
            guard middleButtonDown else { return event }
            middleButtonDown = false
            sendPointerDelta(event, deviceID: deviceID)
            return nil
        case .rightMouseDown:
            guard isOverStage(event) else { return event }
            lastDeviceID = deviceID
            rightBackDown = true
            hid.sendNavigation(.back, to: deviceID)
            return nil
        case .rightMouseUp:
            guard rightBackDown else { return event }
            rightBackDown = false
            hid.sendConsumer(0, to: deviceID)
            return nil
        default:
            return event
        }
    }

    private func handleKeyboard(_ event: NSEvent, deviceID: String) -> NSEvent? {
        lastDeviceID = deviceID

        if event.type == .flagsChanged {
            modifiers = MacKeyMap.modifiers(from: event.modifierFlags)
            if event.keyCode == 57 {
                tapCapsLock(deviceID: deviceID)
            } else {
                sendKeyboard(deviceID: deviceID)
            }
            return nil
        }

        guard event.keyCode != 57 else {
            modifiers = MacKeyMap.modifiers(from: event.modifierFlags)
            sendKeyboard(deviceID: deviceID)
            return nil
        }
        guard !MacKeyMap.modifierKeyCodes.contains(event.keyCode) else { return nil }
        guard let usage = MacKeyMap.usages[event.keyCode] else { return event }

        if event.type == .keyDown {
            guard !event.isARepeat else { return nil }
            pressedKeys.insert(usage)
        } else {
            pressedKeys.remove(usage)
        }
        modifiers = MacKeyMap.modifiers(from: event.modifierFlags)
        sendKeyboard(deviceID: deviceID)
        return nil
    }

    private func sendKeyboard(deviceID: String) {
        _ = hid.sendKeyboard(
            KeyboardReport(modifiers: modifiers, keys: Array(pressedKeys).sorted()),
            to: deviceID
        )
    }

    private func tapCapsLock(deviceID: String) {
        modifiers = MacKeyMap.modifiers(from: NSEvent.modifierFlags)
        var keys = pressedKeys
        keys.insert(0x39)
        _ = hid.sendKeyboard(KeyboardReport(modifiers: modifiers, keys: Array(keys).sorted()), to: deviceID)
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(70))
            guard let self else { return }
            var remaining = self.pressedKeys
            remaining.remove(0x39)
            self.modifiers = MacKeyMap.modifiers(from: NSEvent.modifierFlags)
            _ = self.hid.sendKeyboard(
                KeyboardReport(modifiers: self.modifiers, keys: Array(remaining).sorted()),
                to: deviceID
            )
        }
    }

    private func sendPointerDelta(_ event: NSEvent, deviceID: String) {
        // Use the displayed phone surface, not the whole window: the video is
        // letterboxed inside the window, so window width overstates it and
        // makes the pointer travel at roughly half speed.
        let hitView = event.window?.contentView?.hitTest(event.locationInWindow)
        let stageWidth = max(hitView?.bounds.width ?? event.window?.contentView?.bounds.width ?? 400, 1)
        let width = max(phoneWidth(deviceID), 1)
        let scale = min(max(CGFloat(width) / stageWidth, 1.0), 6.0)
        residualX += event.deltaX * scale
        residualY += event.deltaY * scale

        let dx = Int8(clamping: Int(residualX.rounded()))
        let dy = Int8(clamping: Int(residualY.rounded()))
        if dx != 0 { residualX -= CGFloat(dx) }
        if dy != 0 { residualY -= CGFloat(dy) }
        var buttons: UInt8 = 0
        if leftButtonDown { buttons |= 0x01 }
        if middleButtonDown { buttons |= 0x04 }
        _ = hid.sendMouse(MouseReport(buttons: buttons, dx: dx, dy: dy), to: deviceID)
    }

    private func sendMouse(deviceID: String, wheel: Int8) {
        var buttons: UInt8 = 0
        if leftButtonDown { buttons |= 0x01 }
        if middleButtonDown { buttons |= 0x04 }
        _ = hid.sendMouse(MouseReport(buttons: buttons, wheel: wheel), to: deviceID)
    }

    private func isOverStage(_ event: NSEvent) -> Bool {
        guard let window = event.window, let content = window.contentView else { return false }
        let point = content.convert(event.locationInWindow, from: nil)
        guard let hit = content.hitTest(point) else { return false }
        return hit.identifier?.rawValue == "DroidBridgeVideoStage"
    }
}
