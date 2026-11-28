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
    var bluetoothAddress: String? = nil
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
    let id: UUID
    let sessionID: UUID?
    let deviceID: String
    let deviceName: String
    let phoneModel: String
    let androidVersion: String
    let batteryLevel: Int
    let code: String

    init(
        sessionID: UUID? = nil,
        deviceID: String,
        deviceName: String,
        phoneModel: String = "",
        androidVersion: String = "",
        batteryLevel: Int = 0,
        code: String
    ) {
        self.id = sessionID ?? UUID()
        self.sessionID = sessionID
        self.deviceID = deviceID
        self.deviceName = deviceName
        self.phoneModel = phoneModel
        self.androidVersion = androidVersion
        self.batteryLevel = batteryLevel
        self.code = code
    }
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
    var isNetworkReady = false
    var isWaitingForPhone = false
    var isConfirmingPairing = false
    var networkError: String?
    var bluetoothPairingStatus: LinkState = .idle

    var isDemoMode: Bool { runsDemo }
    var macBluetoothName: String { bluetoothHID.localDeviceName }

    @ObservationIgnored private var toastTask: Task<Void, Never>?
    @ObservationIgnored private var lanServer: LanServer?
    @ObservationIgnored private var streams: [String: MirrorStream] = [:]
    @ObservationIgnored private let runsDemo: Bool
    @ObservationIgnored private let bluetoothHID = BluetoothHIDController()
    @ObservationIgnored private lazy var inputRouter = BluetoothInputRouter(
        hid: bluetoothHID,
        focusedDeviceID: { [weak self] in self?.focusedDeviceID },
        phoneWidth: { [weak self] deviceID in self?.streams[deviceID]?.width ?? 1080 }
    )

    init() {
        let arguments = ProcessInfo.processInfo.arguments
        #if DEBUG
        runsDemo = arguments.contains("--demo-hub")
            || arguments.contains("--demo-settings")
            || arguments.contains("--demo-pairing")
            || arguments.contains("--demo-error")
            || arguments.contains("--demo-mirror")

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
        #else
        runsDemo = false
        #endif

        if !runsDemo {
            startNetwork()
        }
        configureBluetooth()
    }

    // MARK: Network

    private func startNetwork() {
        do {
            let server = try LanServer()
            server.eventHandler = { [weak self] event in
                Task { @MainActor [weak self] in
                    self?.handle(event)
                }
            }
            lanServer = server
            server.start()
        } catch {
            networkError = error.localizedDescription
            showToast("LAN server failed: \(error.localizedDescription)", symbol: "wifi.exclamationmark")
        }
    }

    private func configureBluetooth() {
        bluetoothHID.onConnectionChanged = { [weak self] deviceID, connected in
            Task { @MainActor [weak self] in
                self?.handleHIDConnection(deviceID: deviceID, connected: connected)
            }
        }
        bluetoothHID.onAddressBound = { [weak self] deviceID, address in
            Task { @MainActor [weak self] in
                self?.handleHIDBinding(deviceID: deviceID, address: address)
            }
        }
        bluetoothHID.onAvailabilityChanged = { [weak self] available, message in
            Task { @MainActor [weak self] in
                guard let self else { return }
                if !available {
                    self.bluetoothPairingStatus = .disconnected
                    if let message { self.showToast(message, symbol: "keyboard.badge.exclamationmark") }
                }
            }
        }
        if !runsDemo { inputRouter.install() }
        NotificationCenter.default.addObserver(
            forName: NSApplication.willTerminateNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            // Termination will not wait for an async hop; unpublish the HID
            // service synchronously so bluetoothd releases the L2CAP PSMs.
            MainActor.assumeIsolated {
                self?.inputRouter.uninstall()
                self?.bluetoothHID.shutdown()
            }
        }
    }

    private func handleHIDConnection(deviceID: String, connected: Bool) {
        guard let index = devices.firstIndex(where: { $0.id == deviceID }) else { return }
        let name = devices[index].name
        if connected {
            devices[index].bluetooth = .connected
            devices[index].bluetoothAddress = bluetoothHID.address(for: deviceID)
            bluetoothPairingStatus = .connected
            showToast("Keyboard & mouse connected to \(name)", symbol: "keyboard.fill")
        } else {
            devices[index].bluetooth = .reconnecting
            inputRouter.release(deviceID: deviceID)
            showToast("Bluetooth HID disconnected from \(name)", symbol: "keyboard.badge.exclamationmark")
        }
    }

    private func handleHIDBinding(deviceID: String, address: String) {
        if let index = devices.firstIndex(where: { $0.id == deviceID }) {
            devices[index].bluetoothAddress = address
            devices[index].bluetooth = .connected
            bluetoothPairingStatus = .connected
            showToast("\(devices[index].name) bonded for keyboard & mouse", symbol: "checkmark.seal.fill")
        } else {
            bluetoothPairingStatus = .connected
        }
    }

    func mirrorStream(for deviceID: String) -> MirrorStream {
        if let stream = streams[deviceID] { return stream }
        let stream = MirrorStream { [weak self] in
            self?.lanServer?.requestKeyframe(deviceID: deviceID)
        }
        streams[deviceID] = stream
        return stream
    }

    private func handle(_ event: LanServerEvent) {
        switch event {
        case .ready(let port):
            isNetworkReady = true
            networkError = nil
            print("DroidBridge LAN ready on port \(port)")

        case .pairing(let request):
            let formatted = SHA256Fingerprint.displayDigits(request.code)
            let session = PairingSession(
                sessionID: request.id,
                deviceID: request.deviceID,
                deviceName: request.name,
                phoneModel: request.model,
                androidVersion: request.osVersion,
                batteryLevel: request.battery,
                code: formatted
            )
            pairing = session
            isWaitingForPhone = false
            isConfirmingPairing = false
            isPairingPresented = true
            bluetoothPairingStatus = .reconnecting
            bluetoothHID.beginPairing(deviceID: request.deviceID)

        case .pairingEnded(let sessionID):
            guard pairing?.sessionID == sessionID else { return }
            pairing = nil
            isWaitingForPhone = false
            isConfirmingPairing = false
            isPairingPresented = false
            bluetoothHID.endPairing()
            bluetoothPairingStatus = .idle

        case .connected(let peer):
            var device = PhoneDevice(
                id: peer.deviceID,
                name: peer.name,
                modelName: peer.model,
                androidVersion: peer.osVersion,
                batteryLevel: peer.battery > 0 ? peer.battery : 100,
                wiFi: .connected,
                bluetooth: bluetoothHID.isConnected(deviceID: peer.deviceID) ? .connected : .idle,
                bluetoothAddress: bluetoothHID.address(for: peer.deviceID)
            )
            if let index = devices.firstIndex(where: { $0.id == peer.deviceID }) {
                device.settings = devices[index].settings
                if devices[index].bluetooth == .connected && device.bluetooth != .connected {
                    device.bluetooth = .reconnecting
                }
                devices[index] = device
            } else {
                devices.append(device)
            }
            selectedDeviceID = peer.deviceID
            if pairing?.sessionID == peer.sessionID {
                pairing = nil
                isPairingPresented = false
                bluetoothHID.endPairing()
            }
            isWaitingForPhone = false
            isConfirmingPairing = false
            showToast("\(peer.name) connected over Wi-Fi", symbol: "wifi")

        case .video(let deviceID, let payload):
            if let index = devices.firstIndex(where: { $0.id == deviceID }) {
                devices[index].wiFi = .connected
            }
            mirrorStream(for: deviceID).enqueue(payload)

        case .mirrorStopped(let deviceID):
            streams[deviceID]?.markStopped()
            inputRouter.release(deviceID: deviceID)
            if focusedDeviceID == deviceID {
                focusedDeviceID = nil
            }
            if let name = device(withID: deviceID)?.name {
                showToast("Screen mirroring stopped on \(name)", symbol: "stop.circle")
            }

        case .disconnected(let deviceID, let reason):
            guard let index = devices.firstIndex(where: { $0.id == deviceID }) else { return }
            devices[index].wiFi = .reconnecting
            streams[deviceID]?.markStopped()
            inputRouter.release(deviceID: deviceID)
            if focusedDeviceID == deviceID {
                focusedDeviceID = nil
            }
            showToast("Wi-Fi dropped: \(reason)", symbol: "wifi.exclamationmark")

        case .failed(let message):
            networkError = message
            isNetworkReady = false
            showToast("LAN server error: \(message)", symbol: "wifi.exclamationmark")
        }
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
        if runsDemo {
            beginDemoPairing()
            return
        }
        isWaitingForPhone = true
        isConfirmingPairing = false
        pairing = nil
        isPairingPresented = true
    }

    private func beginDemoPairing() {
        let pairedIDs = Set(devices.map(\.id))
        let candidate = Self.catalogue.first { !pairedIDs.contains($0.id) }
            ?? Self.catalogue[0]
        let digits = String(format: "%06d", Int.random(in: 0...999_999))
        let formatted = "\(digits.prefix(3)) \(digits.suffix(3))"
        pairing = PairingSession(
            deviceID: candidate.id,
            deviceName: candidate.name,
            phoneModel: candidate.modelName,
            androidVersion: candidate.androidVersion,
            batteryLevel: candidate.batteryLevel,
            code: formatted
        )
        isWaitingForPhone = false
        isConfirmingPairing = false
        isPairingPresented = true
    }

    func cancelPairing() {
        if let sessionID = pairing?.sessionID {
            lanServer?.cancelPairing(sessionID: sessionID)
        }
        bluetoothHID.endPairing()
        bluetoothPairingStatus = .idle
        isPairingPresented = false
        pairing = nil
        isWaitingForPhone = false
        isConfirmingPairing = false
    }

    func confirmPairing() {
        guard let pairing else { return }
        if let sessionID = pairing.sessionID {
            isConfirmingPairing = true
            lanServer?.confirmPairing(sessionID: sessionID)
            return
        }

        var newDevice = Self.catalogue.first { $0.id == pairing.deviceID } ?? Self.catalogue[0]
        newDevice.wiFi = .connected
        newDevice.bluetooth = .connected

        if let index = devices.firstIndex(where: { $0.id == newDevice.id }) {
            devices[index] = newDevice
        } else {
            devices.append(newDevice)
        }
        selectedDeviceID = newDevice.id
        self.pairing = nil
        isPairingPresented = false
        showToast("\(newDevice.name) paired over Wi-Fi and Bluetooth", symbol: "checkmark.seal.fill")
    }

    // MARK: Session actions

    func startMirroring(deviceID: String) {
        guard let device = device(withID: deviceID) else { return }
        bluetoothHID.start()
        if focusedDeviceID != deviceID { inputRouter.release() }
        focusedDeviceID = deviceID
        showToast("Mirroring session opened for \(device.name)", symbol: "play.rectangle.fill")
    }

    func stopMirroring(deviceID: String) {
        lanServer?.stopMirroring(deviceID: deviceID)
        inputRouter.release(deviceID: deviceID)
        if focusedDeviceID == deviceID {
            focusedDeviceID = nil
        }
    }

    func focus(deviceID: String) {
        guard focusedDeviceID != deviceID else { return }
        inputRouter.release()
        focusedDeviceID = deviceID
    }

    func clearFocus(deviceID: String) {
        guard focusedDeviceID == deviceID else { return }
        inputRouter.release(deviceID: deviceID)
        focusedDeviceID = nil
    }

    func beginBluetoothSetup(deviceID: String) {
        guard let device = device(withID: deviceID) else { return }
        bluetoothPairingStatus = .reconnecting
        bluetoothHID.beginPairing(deviceID: deviceID)
        showToast("Pair \(macBluetoothName) in Android Bluetooth Settings", symbol: "keyboard")
        if device.bluetoothAddress != nil {
            update(deviceID) { $0.bluetooth = .reconnecting }
        }
    }

    func sendNavigation(_ navigation: HIDNavigation, to deviceID: String) {
        guard bluetoothHID.isConnected(deviceID: deviceID) else {
            showToast("Pair Bluetooth HID to use phone navigation", symbol: "keyboard.badge.exclamationmark")
            return
        }
        bluetoothHID.sendNavigation(navigation, to: deviceID)
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
        if runsDemo {
            update(deviceID) {
                $0.wiFi = .connected
                $0.bluetooth = .connected
            }
            showToast("Reconnected securely", symbol: "wifi")
            return
        }
        guard let device = device(withID: deviceID) else { return }
        bluetoothHID.start()
        if device.bluetoothAddress != nil {
            update(deviceID) { $0.bluetooth = .reconnecting }
        }
        showToast("Waiting for \(device.name) to reconnect", symbol: "arrow.clockwise")
    }

    func disconnect(deviceID: String) {
        lanServer?.disconnect(deviceID: deviceID)
        inputRouter.release(deviceID: deviceID)
        if bluetoothHID.pairingDeviceID == deviceID {
            bluetoothHID.endPairing()
            bluetoothPairingStatus = .idle
        }
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
