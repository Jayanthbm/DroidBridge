import Foundation

enum HIDReportID: UInt8 {
    case mouse = 1
    case keyboard = 2
    case consumer = 3
}

enum HIDNavigation: String, CaseIterable {
    case back
    case home
    case recents

    var consumerUsage: UInt16 {
        switch self {
        case .back: 0x0224
        case .home: 0x0223
        // Android has no standard consumer "Recents" usage; 0x0221 is
        // Assist/Search and opens Google. Recents is sent as Alt+Tab below.
        case .recents: 0
        }
    }

    var label: String {
        switch self {
        case .back: "Back"
        case .home: "Home"
        case .recents: "Recents"
        }
    }
}

struct KeyboardReport {
    var modifiers: UInt8 = 0
    var keys: [UInt8] = []

    var data: Data {
        var payload: [UInt8] = [modifiers, 0]
        payload.append(contentsOf: keys.prefix(6))
        payload.append(contentsOf: repeatElement(0, count: max(0, 6 - keys.count)))
        return Data(payload.prefix(8))
    }
}

struct MouseReport {
    var buttons: UInt8 = 0
    var dx: Int8 = 0
    var dy: Int8 = 0
    var wheel: Int8 = 0

    var data: Data {
        Data([buttons, UInt8(bitPattern: dx), UInt8(bitPattern: dy), UInt8(bitPattern: wheel)])
    }
}

@MainActor
final class BluetoothHIDController {
    private let service = DBBluetoothHIDService()
    private let defaults = UserDefaults.standard
    private let bindingsKey = "DroidBridge.bluetoothBindings"

    private(set) var bindings: [String: String] = [:]
    private(set) var connectedAddresses: Set<String> = []
    private(set) var isStarted = false
    private(set) var isAvailable = false
    private(set) var lastError: String?

    private var heartbeatTask: Task<Void, Never>?

    var pairingDeviceID: String?
    var onConnectionChanged: ((String, Bool) -> Void)?
    var onAddressBound: ((String, String) -> Void)?
    var onAvailabilityChanged: ((Bool, String?) -> Void)?

    var localDeviceName: String { service.localDeviceName }

    init() {
        if let saved = defaults.dictionary(forKey: bindingsKey) as? [String: String] {
            bindings = saved.mapValues { $0.uppercased() }
        }

        service.availabilityHandler = { [weak self] available, message in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.isAvailable = available
                if let message { self.lastError = message }
                self.onAvailabilityChanged?(available, message)
            }
        }
        service.connectionHandler = { [weak self] connected, address in
            Task { @MainActor [weak self] in
                self?.handleConnection(connected: connected, address: address)
            }
        }
        service.selectedDeviceHandler = { [weak self] address in
            Task { @MainActor [weak self] in
                self?.handleSelectedDevice(address: address)
            }
        }
    }

    func start(pairingDeviceID: String? = nil) {
        guard !isStarted else {
            if let pairingDeviceID { beginPairing(deviceID: pairingDeviceID) }
            return
        }
        self.pairingDeviceID = pairingDeviceID
        do {
            try service.start(withPairingMode: pairingDeviceID != nil,
                              allowedAddresses: Array(bindings.values))
            isStarted = true
            isAvailable = true
            lastError = nil
        } catch {
            isAvailable = false
            lastError = error.localizedDescription
            onAvailabilityChanged?(false, lastError)
        }
    }

    func beginPairing(deviceID: String) {
        start()
        guard isStarted else { return }
        pairingDeviceID = deviceID
        service.setPairingMode(true)
    }

    func endPairing() {
        pairingDeviceID = nil
        guard isStarted else { return }
        service.setPairingMode(false)
    }

    func shutdown() {
        heartbeatTask?.cancel()
        heartbeatTask = nil
        guard isStarted else { return }
        service.shutdown()
        isStarted = false
        isAvailable = false
        connectedAddresses.removeAll()
    }

    func address(for deviceID: String) -> String? {
        bindings[deviceID]
    }

    func deviceID(for address: String) -> String? {
        let normalized = address.uppercased()
        return bindings.first { $0.value == normalized }?.key
    }

    func isConnected(deviceID: String) -> Bool {
        guard let address = address(for: deviceID) else { return false }
        return connectedAddresses.contains(address)
    }

    @discardableResult
    func sendKeyboard(_ report: KeyboardReport, to deviceID: String) -> Bool {
        send(reportID: .keyboard, payload: report.data, to: deviceID)
    }

    @discardableResult
    func sendMouse(_ report: MouseReport, to deviceID: String) -> Bool {
        send(reportID: .mouse, payload: report.data, to: deviceID)
    }

    @discardableResult
    func sendConsumer(_ usage: UInt16, to deviceID: String) -> Bool {
        let payload = Data([UInt8(usage & 0xFF), UInt8(usage >> 8)])
        return send(reportID: .consumer, payload: payload, to: deviceID)
    }

    func sendNavigation(_ navigation: HIDNavigation, to deviceID: String) {
        if navigation == .recents {
            // Android's stock HID keyboard maps Alt+Tab to the app switcher.
            guard sendKeyboard(KeyboardReport(modifiers: 0x04, keys: [0x2B]), to: deviceID) else { return }
            Task { [weak self] in
                try? await Task.sleep(for: .milliseconds(90))
                self?.sendKeyboard(KeyboardReport(), to: deviceID)
            }
            return
        }
        guard sendConsumer(navigation.consumerUsage, to: deviceID) else { return }
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(80))
            guard let self else { return }
            self.sendConsumer(0, to: deviceID)
        }
    }

    func releaseAll(deviceID: String) {
        guard isConnected(deviceID: deviceID) else { return }
        sendKeyboard(KeyboardReport(), to: deviceID)
        sendMouse(MouseReport(), to: deviceID)
        sendConsumer(0, to: deviceID)
    }

    private func send(reportID: HIDReportID, payload: Data, to deviceID: String) -> Bool {
        guard isConnected(deviceID: deviceID), let address = address(for: deviceID) else { return false }
        return service.sendReportID(reportID.rawValue, payload: payload, toAddress: address)
    }

    private func handleConnection(connected: Bool, address: String) {
        let normalized = address.uppercased()
        if connected {
            connectedAddresses.insert(normalized)
            startHeartbeatIfNeeded()
        } else {
            connectedAddresses.remove(normalized)
            stopHeartbeatIfIdle()
        }
        guard let deviceID = deviceID(for: normalized) else { return }
        onConnectionChanged?(deviceID, connected)
    }

    /// Bluetooth HID hosts drop idle links, and bluetoothd's per-session
    /// CoreBluetooth channel routing does not survive a teardown cleanly.
    /// A zero-delta mouse report every few seconds keeps the interrupt
    /// channel warm without moving the pointer or typing anything.
    private func startHeartbeatIfNeeded() {
        guard heartbeatTask == nil, !connectedAddresses.isEmpty else { return }
        heartbeatTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(4))
                guard let self, !Task.isCancelled else { return }
                for address in self.connectedAddresses {
                    self.service.sendReportID(HIDReportID.mouse.rawValue,
                                              payload: MouseReport().data,
                                              toAddress: address)
                }
            }
        }
    }

    private func stopHeartbeatIfIdle() {
        guard connectedAddresses.isEmpty else { return }
        heartbeatTask?.cancel()
        heartbeatTask = nil
    }

    private func handleSelectedDevice(address: String) {
        let normalized = address.uppercased()
        guard let deviceID = pairingDeviceID else { return }
        bindings[deviceID] = normalized
        defaults.set(bindings, forKey: bindingsKey)
        pairingDeviceID = nil
        onAddressBound?(deviceID, normalized)
    }
}
