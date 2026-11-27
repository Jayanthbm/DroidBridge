import SwiftUI
import Observation

// MARK: - Domain Models

enum LinkState: String, Codable, Hashable {
    case connected
    case reconnecting
    case disconnected
    case idle

    var isConnected: Bool { self == .connected }

    var label: String {
        switch self {
        case .connected: "Connected"
        case .reconnecting: "Reconnecting"
        case .disconnected: "Disconnected"
        case .idle: "Idle"
        }
    }
}

enum StreamQuality: String, CaseIterable, Identifiable, Codable {
    case adaptive
    case fullHD60
    case fullHD30
    case hd60

    var id: String { rawValue }

    var label: String {
        switch self {
        case .adaptive: "Adaptive"
        case .fullHD60: "1080p / 60"
        case .fullHD30: "1080p / 30"
        case .hd60: "720p / 60"
        }
    }

    var detail: String {
        switch self {
        case .adaptive: "Adjusts to network conditions"
        case .fullHD60: "Sharpest motion · recommended"
        case .fullHD30: "Lower bandwidth"
        case .hd60: "Best for weak Wi-Fi"
        }
    }
}

struct DeviceSettings: Hashable, Codable {
    var streamQuality: StreamQuality = .fullHD60
    var audioEnabled: Bool = true
    var autoSyncClipboard: Bool = true
    var autoReconnect: Bool = true
}

struct PhoneDevice: Identifiable, Hashable {
    let id: String
    var name: String
    var modelName: String
    var androidVersion: String
    var batteryLevel: Int
    var wiFi: LinkState
    var bluetooth: LinkState
    var settings: DeviceSettings = DeviceSettings()

    var connectionSummary: String {
        switch (wiFi, bluetooth) {
        case (.reconnecting, _): "Reconnecting Wi-Fi…"
        case (.disconnected, _): "Offline"
        case (.connected, .connected): "Wi-Fi + BT Connected"
        case (.connected, .idle): "Wi-Fi Ready · BT Idle"
        case (.connected, .reconnecting): "Wi-Fi Connected · BT Reconnecting"
        case (.connected, .disconnected): "Wi-Fi Connected · BT Off"
        default: "Wi-Fi \(wiFi.label) · BT \(bluetooth.label)"
        }
    }

    var needsAttention: Bool {
        wiFi == .reconnecting || wiFi == .disconnected || bluetooth == .reconnecting || bluetooth == .disconnected
    }
}

struct PairingSession: Identifiable, Hashable {
    let id = UUID()
    let deviceID: String
    let deviceName: String
    let code: String
}

struct ToastMessage: Identifiable, Equatable {
    let id = UUID()
    let message: String
    let symbol: String
}

// MARK: - App Model

@MainActor
@Observable
final class AppModel {
    var devices: [PhoneDevice] = []
    var selectedDeviceID: String?
    var isPairingPresented = false
    var pairing: PairingSession?
    var focusedDeviceID: String?
    var toast: ToastMessage?

    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored private var pairingsCompleted = 0

    init() {
        #if DEBUG
        let arguments = ProcessInfo.processInfo.arguments
        if arguments.contains("--demo-hub") || arguments.contains("--demo-settings") {
            devices = Self.catalogue.map { device in
                var connected = device
                connected.wiFi = .connected
                connected.bluetooth = .connected
                return connected
            }
            selectedDeviceID = devices.first?.id
        } else if arguments.contains("--demo-pairing") {
            beginPairing()
        } else if arguments.contains("--demo-error") {
            var device = Self.catalogue[0]
            device.wiFi = .reconnecting
            device.bluetooth = .connected
            devices = [device]
            selectedDeviceID = device.id
        }
        #endif
    }

    var selectedDevice: PhoneDevice? {
        guard let selectedDeviceID else { return nil }
        return devices.first { $0.id == selectedDeviceID }
    }

    func device(withID id: String) -> PhoneDevice? {
        devices.first { $0.id == id }
    }

    // MARK: Pairing

    func beginPairing() {
        let pairedIDs = Set(devices.map(\.id))
        let candidate = Self.catalogue.first { !pairedIDs.contains($0.id) }
            ?? Self.catalogue[0]
        let digits = String(format: "%06d", Int.random(in: 0...999_999))
        let formatted = "\(digits.prefix(3)) \(digits.suffix(3))"
        pairing = PairingSession(
            deviceID: candidate.id,
            deviceName: candidate.name,
            code: formatted
        )
        isPairingPresented = true
    }

    func cancelPairing() {
        isPairingPresented = false
        pairing = nil
    }

    func confirmPairing() {
        guard let pairing else { return }
        var newDevice = Self.catalogue.first { $0.id == pairing.deviceID } ?? Self.catalogue[0]
        newDevice.wiFi = .connected
        newDevice.bluetooth = .connected

        if let index = devices.firstIndex(where: { $0.id == newDevice.id }) {
            devices[index] = newDevice
        } else {
            devices.append(newDevice)
        }
        pairingsCompleted += 1
        selectedDeviceID = newDevice.id
        self.pairing = nil
        isPairingPresented = false
        showToast("\(newDevice.name) paired over Wi-Fi and Bluetooth", symbol: "checkmark.seal.fill")
    }

    // MARK: Session actions

    func startMirroring(deviceID: String) {
        guard let device = device(withID: deviceID) else { return }
        focusedDeviceID = deviceID
        showToast("Mirroring session opened for \(device.name)", symbol: "play.rectangle.fill")
    }

    func stopMirroring(deviceID: String) {
        if focusedDeviceID == deviceID {
            focusedDeviceID = nil
        }
    }

    func focus(deviceID: String) {
        focusedDeviceID = deviceID
    }

    func setStreamQuality(deviceID: String, quality: StreamQuality) {
        update(deviceID) { $0.settings.streamQuality = quality }
    }

    func setAudio(deviceID: String, enabled: Bool) {
        update(deviceID) { $0.settings.audioEnabled = enabled }
        showToast(enabled ? "Playback audio on" : "Playback audio muted", symbol: enabled ? "speaker.wave.2.fill" : "speaker.slash.fill")
    }

    func setAutoSync(deviceID: String, enabled: Bool) {
        update(deviceID) { $0.settings.autoSyncClipboard = enabled }
    }

    func setAutoReconnect(deviceID: String, enabled: Bool) {
        update(deviceID) { $0.settings.autoReconnect = enabled }
    }

    func syncClipboard(deviceID: String) {
        guard let device = device(withID: deviceID) else { return }
        showToast("Clipboard synced with \(device.name)", symbol: "doc.on.clipboard.fill")
    }

    func simulateWiFiDrop(deviceID: String) {
        update(deviceID) { $0.wiFi = .reconnecting }
        showToast("Simulated Wi-Fi drop", symbol: "wifi.exclamationmark")
    }

    func simulateBluetoothDrop(deviceID: String) {
        update(deviceID) { $0.bluetooth = .reconnecting }
        showToast("Simulated Bluetooth HID drop", symbol: "keyboard.badge.exclamationmark")
    }

    func reconnect(deviceID: String) {
        update(deviceID) {
            $0.wiFi = .connected
            $0.bluetooth = .connected
        }
        showToast("Reconnected securely", symbol: "wifi")
    }

    func disconnect(deviceID: String) {
        guard let index = devices.firstIndex(where: { $0.id == deviceID }) else { return }
        let removed = devices.remove(at: index)
        if selectedDeviceID == deviceID {
            selectedDeviceID = devices.first?.id
        }
        if focusedDeviceID == deviceID {
            focusedDeviceID = nil
        }
        showToast("Disconnected from \(removed.name)", symbol: "xmark.circle.fill")
    }

    func resetDemo() {
        devices = []
        selectedDeviceID = nil
        focusedDeviceID = nil
        pairing = nil
        isPairingPresented = false
        showToast("Demo data reset", symbol: "arrow.counterclockwise")
    }

    // MARK: Helpers

    func showToast(_ message: String, symbol: String = "checkmark.circle.fill") {
        toast = ToastMessage(message: message, symbol: symbol)
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(2.4))
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    private func update(_ deviceID: String, _ mutation: (inout PhoneDevice) -> Void) {
        guard let index = devices.firstIndex(where: { $0.id == deviceID }) else { return }
        mutation(&devices[index])
    }

    // MARK: Mock catalogue

    private static let catalogue: [PhoneDevice] = [
        PhoneDevice(
            id: "galaxy-s24-ultra",
            name: "Galaxy S24 Ultra",
            modelName: "SM-S928B",
            androidVersion: "Android 15",
            batteryLevel: 84,
            wiFi: .connected,
            bluetooth: .connected
        ),
        PhoneDevice(
            id: "oneplus-nord",
            name: "OnePlus Nord",
            modelName: "AC2001",
            androidVersion: "Android 12",
            batteryLevel: 68,
            wiFi: .connected,
            bluetooth: .connected
        ),
        PhoneDevice(
            id: "pixel-9-pro",
            name: "Pixel 9 Pro",
            modelName: "GR83Y",
            androidVersion: "Android 16",
            batteryLevel: 91,
            wiFi: .connected,
            bluetooth: .idle
        )
    ]
}
