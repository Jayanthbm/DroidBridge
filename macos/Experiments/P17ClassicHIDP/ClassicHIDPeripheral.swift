// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
//
// P17 — macOS Bluetooth Classic HID device (HIDP) experiment.
//
// Independently authored from the Bluetooth HID Profile 1.1 transaction/state machine and the
// public IOBluetooth API surface. Public API only: SDP publication, device connection, L2CAP
// channel open and channel writes. No Accessibility, no private API, no reflection.
import Foundation
import IOBluetooth
import os

@MainActor
final class ClassicHIDPeripheral: NSObject, ObservableObject {
    /// L2CAP protocol/service multiplexer for the HID control channel.
    private static let controlPSM: UInt16 = 0x0011
    /// L2CAP protocol/service multiplexer for the HID interrupt channel.
    private static let interruptPSM: UInt16 = 0x0013

    /// HIDP transaction header prefixes (high nibble of the first byte).
    private enum Transaction {
        static let handshake: UInt8 = 0x00
        static let control: UInt8 = 0x10
        static let getReport: UInt8 = 0x40
        static let setReport: UInt8 = 0x50
        static let getProtocol: UInt8 = 0x60
        static let setProtocol: UInt8 = 0x70
        static let getIdle: UInt8 = 0x80
        static let setIdle: UInt8 = 0x90
        static let data: UInt8 = 0xA0
    }

    /// HIDP handshake result codes.
    private enum Handshake {
        static let successful: UInt8 = 0x00
        static let notReady: UInt8 = 0x01
        static let errUnsupportedRequest: UInt8 = 0x03
        static let errInvalidParameter: UInt8 = 0x04
    }

    /// HID_CONTROL operation codes.
    private enum ControlOperation {
        static let virtualCableUnplug: UInt8 = 0x05
    }

    /// HIDP report types carried in the low byte of GET_REPORT/SET_REPORT/DATA parameters.
    private enum ReportType: UInt8 {
        case input = 1
        case output = 2
        case feature = 3
    }

    struct Peer: Identifiable, Hashable {
        let id: String
        let name: String
        let isConnected: Bool
    }

    @Published private(set) var isSDPPublished = false
    @Published private(set) var connectedAddress: String?
    @Published private(set) var isReady = false
    @Published private(set) var protocolMode: UInt8 = 0x01
    @Published private(set) var idleRate: UInt8 = 0
    @Published private(set) var reportsSent = 0
    @Published private(set) var pairedDevices: [Peer] = []
    @Published private(set) var lastError: String?
    @Published private(set) var activity: [String] = []

    private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "", category: "P17ClassicHIDP")
    private var serviceRecord: IOBluetoothSDPServiceRecord?
    private var device: IOBluetoothDevice?
    private var controlChannel: IOBluetoothL2CAPChannel?
    private var interruptChannel: IOBluetoothL2CAPChannel?
    private var lastReports: [UInt8: Data] = [
        P17Identity.ReportID.keyboard.rawValue: P17Identity.neutralReport
    ]
    private var terminationHold = false
    private var harnessRan = false

    override init() {
        super.init()
        refreshPairedDevices()
    }

    // MARK: - Service publication

    /// Publishes the HID SDP record so hosts can discover this device as a HID peripheral.
    func publishService() {
        guard serviceRecord == nil else { return }
        guard let record = IOBluetoothSDPServiceRecord.publishedServiceRecord(with: P17SDPRecord.hidDevice()) else {
            lastError = "SDP publication failed: publishedServiceRecord returned nil"
            note("SDP publication failed")
            return
        }
        serviceRecord = record
        isSDPPublished = true
        var handle: BluetoothSDPServiceRecordHandle = 0
        _ = record.getHandle(&handle)
        note("SDP published (handle \(handle))")
    }

    func unpublishService() {
        serviceRecord?.remove()
        serviceRecord = nil
        isSDPPublished = false
    }

    // MARK: - Peers

    func refreshPairedDevices() {
        let devices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
        pairedDevices = devices.compactMap { peer in
            guard let address = peer.addressString else { return nil }
            let name = peer.name ?? address
            return Peer(id: normalize(address), name: name, isConnected: peer.isConnected())
        }
        .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
    }

    // MARK: - Connection

    /// Publishes the SDP record and opens the ACL plus both HID L2CAP channels to `address`.
    ///
    /// macOS `bluetoothd` always acts as the HID host for inbound links, so this peripheral must
    /// connect outbound. Android accepts device-initiated HID connections.
    func connect(to address: String) {
        lastError = nil
        publishService()
        let normalized = normalize(address)
        guard let stored = IOBluetoothDevice(addressString: dashForm(normalized)) else {
            lastError = "Peer \(normalized) is not paired with this Mac"
            note("connect: unknown peer \(normalized)")
            return
        }
        disconnect(keepError: true)
        device = stored
        holdAgainstTermination()

        let shown = stored.addressString ?? normalized
        note("opening ACL to \(shown)")

        // Present a HID keyboard peripheral class; Android inspects the peer CoD when deciding
        // whether to accept a device-initiated HID connection. The change is temporary.
        if let host = IOBluetoothHostController.default() {
            let codResult = host.setClassOfDevice(
                P17Identity.classOfDevice,
                forTimeInterval: P17Identity.classOfDeviceDuration
            )
            note(codResult == kIOReturnSuccess
                ? "class of device -> 0x\(String(P17Identity.classOfDevice, radix: 16))"
                : "class of device change failed (\(Self.ioCode(codResult)))")
        }

        if !stored.isConnected() {
            let aclResult = stored.openConnection()
            guard aclResult == kIOReturnSuccess else {
                lastError = "openConnection failed (\(Self.ioCode(aclResult)))"
                note(lastError ?? "")
                teardown()
                return
            }
        }

        var control: IOBluetoothL2CAPChannel?
        let controlResult = stored.openL2CAPChannelSync(
            &control,
            withPSM: BluetoothL2CAPPSM(Self.controlPSM),
            delegate: self
        )
        guard controlResult == kIOReturnSuccess, let control else {
            lastError = "L2CAP control channel (0x0011) failed (\(Self.ioCode(controlResult)))"
            note(lastError ?? "")
            teardown()
            return
        }
        note("control channel open")

        var interrupt: IOBluetoothL2CAPChannel?
        let interruptResult = stored.openL2CAPChannelSync(
            &interrupt,
            withPSM: BluetoothL2CAPPSM(Self.interruptPSM),
            delegate: self
        )
        guard interruptResult == kIOReturnSuccess, let interrupt else {
            lastError = "L2CAP interrupt channel (0x0013) failed (\(Self.ioCode(interruptResult)))"
            note(lastError ?? "")
            control.close()
            teardown()
            return
        }
        note("interrupt channel open")

        controlChannel = control
        interruptChannel = interrupt
        connectedAddress = normalized
        isReady = true
        protocolMode = 0x01
        idleRate = 0
        reportsSent = 0
        refreshPairedDevices()
    }

    func disconnect(keepError: Bool = false) {
        controlChannel?.close()
        interruptChannel?.close()
        controlChannel = nil
        interruptChannel = nil
        if let device {
            device.closeConnection()
        }
        device = nil
        connectedAddress = nil
        isReady = false
        protocolMode = 0x01
        idleRate = 0
        if !keepError { lastError = nil }
        releaseTerminationHold()
        refreshPairedDevices()
    }

    func stop() {
        disconnect()
        unpublishService()
        note("stopped")
    }

    // MARK: - Automation harness

    /// Runs the launch-time harness requested on the command line.
    ///
    /// The experiment stays manually operated by default. These flags exist so an automated
    /// run can publish, connect and emit one arrow without UI scripting. Reports still travel
    /// only over the HID channels created by this app.
    ///
    ///   --harness-publish                  publish the HID SDP record
    ///   --harness-connect <address>        publish, then connect to the paired peer
    ///   --harness-send <UP|DOWN|LEFT|RIGHT> send one press/release once channels are ready
    func runHarnessIfRequested() {
        guard !harnessRan else { return }
        harnessRan = true
        let arguments = ProcessInfo.processInfo.arguments
        let wantsPublish = arguments.contains("--harness-publish")
        let connectionIndex = arguments.firstIndex(of: "--harness-connect")
        guard wantsPublish || connectionIndex != nil || arguments.contains("--harness-send") else { return }

        publishService()
        if let connectionIndex, arguments.indices.contains(connectionIndex + 1) {
            connect(to: arguments[connectionIndex + 1])
        }

        if let sendIndex = arguments.firstIndex(of: "--harness-send"),
           arguments.indices.contains(sendIndex + 1) {
            sendHarnessArrow(named: arguments[sendIndex + 1].uppercased())
        }
    }

    private func sendHarnessArrow(named name: String) {
        let usage: UInt8?
        switch name {
        case "UP": usage = 0x52
        case "DOWN": usage = 0x51
        case "LEFT": usage = 0x50
        case "RIGHT": usage = 0x4F
        default: usage = nil
        }
        guard let usage else {
            lastError = "Unknown harness arrow '\(name)'"
            return
        }
        Task { @MainActor in
            // Give the host time to finish HID discovery before the first report.
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard self.isReady else {
                self.lastError = "Harness send skipped: channels not ready"
                self.note("harness send skipped (not ready)")
                return
            }
            self.sendArrow(usage)
            self.note("harness sent \(name)")
        }
    }

    // MARK: - Reports

    /// Sends one arrow press followed by a release.
    func sendArrow(_ usage: UInt8) {
        guard isReady else {
            lastError = "Not connected: no HID channels"
            return
        }
        var pressed = P17Identity.neutralReport
        pressed[2] = usage
        sendInputReport(P17Identity.ReportID.keyboard.rawValue, payload: pressed)
        sendInputReport(P17Identity.ReportID.keyboard.rawValue, payload: P17Identity.neutralReport)
    }

    private func sendInputReport(_ reportID: UInt8, payload: Data) {
        guard let interruptChannel else {
            lastError = "Interrupt channel is closed"
            return
        }
        lastReports[reportID] = payload

        var buffer = Data([Transaction.data | 0x01]) // DATA + input report
        // Report Protocol prefixes the report ID; Boot Protocol uses the fixed 8-byte layout.
        if protocolMode == 0x01 {
            buffer.append(reportID)
        }
        buffer.append(payload)

        let byteCount = buffer.count
        let status: IOReturn = buffer.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return kIOReturnBadArgument }
            return interruptChannel.writeAsync(base, length: UInt16(byteCount), refcon: nil)
        }
        if status == kIOReturnSuccess {
            reportsSent += 1
        } else {
            lastError = "Interrupt write failed (\(Self.ioCode(status)))"
            note(lastError ?? "")
        }
    }

    // MARK: - HIDP control channel

    private func handleHostMessage(_ bytes: Data, on channel: IOBluetoothL2CAPChannel) {
        guard let header = bytes.first else { return }
        let parameter = header & 0x0F

        switch header & 0xF0 {
        case Transaction.handshake:
            break // host handshake; nothing to answer

        case Transaction.control:
            if parameter == ControlOperation.virtualCableUnplug {
                note("host requested virtual cable unplug")
                disconnect()
            } else {
                sendHandshake(Handshake.successful, on: channel)
            }

        case Transaction.getReport:
            respondWithReport(bytes: bytes, on: channel)

        case Transaction.setReport:
            applyOutputReport(bytes: bytes)
            sendHandshake(Handshake.successful, on: channel)

        case Transaction.getProtocol:
            sendData([protocolMode], on: channel)

        case Transaction.setProtocol:
            guard parameter <= 0x01 else {
                sendHandshake(Handshake.errInvalidParameter, on: channel)
                return
            }
            protocolMode = parameter
            note("protocol mode -> \(parameter == 0 ? "boot" : "report")")
            sendHandshake(Handshake.successful, on: channel)

        case Transaction.getIdle:
            sendData([idleRate], on: channel)

        case Transaction.setIdle:
            idleRate = bytes.count >= 2 ? bytes[1] : parameter
            sendHandshake(Handshake.successful, on: channel)

        case Transaction.data:
            // Host-initiated output/feature data.
            applyOutputReport(bytes: bytes)

        default:
            sendHandshake(Handshake.errUnsupportedRequest, on: channel)
        }
    }

    /// Answers GET_REPORT with the cached report for the requested type and identifier.
    private func respondWithReport(bytes: Data, on channel: IOBluetoothL2CAPChannel) {
        guard bytes.count >= 2 else {
            sendHandshake(Handshake.errInvalidParameter, on: channel)
            return
        }
        let type = bytes[1] >> 4
        let reportID = bytes[1] & 0x0F

        guard let reportType = ReportType(rawValue: type) else {
            sendHandshake(Handshake.errInvalidParameter, on: channel)
            return
        }
        switch reportType {
        case .input:
            guard let payload = lastReports[reportID] else {
                sendHandshake(Handshake.errInvalidParameter, on: channel)
                return
            }
            sendData([(type << 4) | reportID] + [UInt8](payload), on: channel)
        case .output:
            sendData([(type << 4) | reportID], on: channel)
        case .feature:
            sendHandshake(Handshake.errUnsupportedRequest, on: channel)
        }
    }

    /// Applies an output report (keyboard LEDs) received from the host.
    private func applyOutputReport(bytes: Data) {
        guard bytes.count >= 2 else { return }
        let type = bytes[1] >> 4
        guard type == ReportType.output.rawValue, bytes.count >= 3 else { return }
        let reportID = bytes[1] & 0x0F
        let value = bytes[2]
        lastReports[reportID] = Data([value])
        note("output report \(reportID) = 0x\(String(value, radix: 16))")
    }

    private func sendHandshake(_ result: UInt8, on channel: IOBluetoothL2CAPChannel) {
        write(Data([Transaction.handshake | result]), on: channel)
    }

    private func sendData(_ payload: [UInt8], on channel: IOBluetoothL2CAPChannel) {
        write(Data([Transaction.data]) + Data(payload), on: channel)
    }

    private func write(_ data: Data, on channel: IOBluetoothL2CAPChannel) {
        var buffer = data
        let byteCount = buffer.count
        let status: IOReturn = buffer.withUnsafeMutableBytes { raw in
            guard let base = raw.baseAddress else { return kIOReturnBadArgument }
            return channel.writeAsync(base, length: UInt16(byteCount), refcon: nil)
        }
        if status != kIOReturnSuccess {
            lastError = "Control write failed (\(Self.ioCode(status)))"
        }
    }

    // MARK: - Session lifetime

    /// macOS may terminate an idle, unseen app, which silently drops the HID link.
    private func holdAgainstTermination() {
        guard !terminationHold else { return }
        terminationHold = true
        ProcessInfo.processInfo.disableAutomaticTermination("P17 HID session is active")
        ProcessInfo.processInfo.disableSuddenTermination()
    }

    private func releaseTerminationHold() {
        guard terminationHold else { return }
        terminationHold = false
        ProcessInfo.processInfo.enableAutomaticTermination("P17 HID session ended")
        ProcessInfo.processInfo.enableSuddenTermination()
    }

    private func teardown() {
        controlChannel = nil
        interruptChannel = nil
        device = nil
        connectedAddress = nil
        isReady = false
        releaseTerminationHold()
    }

    // MARK: - Helpers

    private func note(_ message: String) {
        activity.append(message)
        if activity.count > 14 {
            activity.removeFirst(activity.count - 14)
        }
        log.info("\(message, privacy: .public)")
    }

    private func normalize(_ address: String) -> String {
        address.lowercased().filter(\.isHexDigit)
    }

    /// IOBluetoothDevice expects "aa-bb-cc-dd-ee-ff".
    private func dashForm(_ canonical: String) -> String {
        guard canonical.count == 12 else { return canonical }
        let pairs = stride(from: 0, to: 12, by: 2).map { offset -> String in
            let start = canonical.index(canonical.startIndex, offsetBy: offset)
            let end = canonical.index(start, offsetBy: 2)
            return String(canonical[start ..< end])
        }
        return pairs.joined(separator: "-")
    }

    private static func ioCode(_ code: IOReturn) -> String {
        String(format: "0x%08X", UInt32(bitPattern: code))
    }
}

extension ClassicHIDPeripheral: @preconcurrency IOBluetoothL2CAPChannelDelegate {
    func l2capChannelOpenComplete(_ channel: IOBluetoothL2CAPChannel!, status error: IOReturn) {
        guard error != kIOReturnSuccess else { return }
        Task { @MainActor in
            self.lastError = "L2CAP open completed with \(Self.ioCode(error))"
            self.note(self.lastError ?? "")
        }
    }

    func l2capChannelClosed(_ channel: IOBluetoothL2CAPChannel!) {
        Task { @MainActor in
            if channel === self.controlChannel { self.controlChannel = nil }
            if channel === self.interruptChannel { self.interruptChannel = nil }
            if self.controlChannel == nil, self.interruptChannel == nil {
                self.isReady = false
                self.connectedAddress = nil
                self.note("HID channels closed")
                self.releaseTerminationHold()
            }
        }
    }

    func l2capChannelReconfigured(_ channel: IOBluetoothL2CAPChannel!) {}

    func l2capChannelData(
        _ channel: IOBluetoothL2CAPChannel!,
        data dataPointer: UnsafeMutableRawPointer!,
        length dataLength: Int
    ) {
        guard let dataPointer, dataLength > 0, let channel else { return }
        let bytes = Data(bytes: dataPointer, count: dataLength)
        Task { @MainActor in
            self.handleHostMessage(bytes, on: channel)
        }
    }

    func l2capChannelWriteComplete(
        _ channel: IOBluetoothL2CAPChannel!,
        refcon: UnsafeMutableRawPointer!,
        status error: IOReturn
    ) {
        guard error != kIOReturnSuccess else { return }
        Task { @MainActor in
            self.note("write complete error \(Self.ioCode(error))")
        }
    }
}
