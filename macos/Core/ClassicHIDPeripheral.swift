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
    @Published private(set) var pairingCode: String?
    @Published private(set) var pairingState: String?

    private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "", category: "P17ClassicHIDP")
    private var serviceRecord: IOBluetoothSDPServiceRecord?
    private var device: IOBluetoothDevice?
    /// Signalled once when both L2CAP channels report closed after a termination close; the
    /// signal handlers wait on it (bounded) so bluetoothd sees the disconnect before exit.
    var closeWaitContinuation: (() -> Void)?
    private var pairer: IOBluetoothDevicePair?
    private var pendingAddress: String?
    /// Set only for an automated run whose numeric value the owner has already verified.
    private var autoAcceptPairing = false
    /// Re-pairs even when a local bond record exists, for a peer that dropped its side.
    private var forcePairing = false
    private var controlChannel: IOBluetoothL2CAPChannel?
    private var interruptChannel: IOBluetoothL2CAPChannel?
    private var lastReports: [UInt8: Data] = [
        HIDIdentity.ReportID.keyboard.rawValue: HIDIdentity.neutralReport
    ]
    private var terminationHold = false
    private var harnessRan = false

    override init() {
        super.init()
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

    /// Prepares IOBluetooth before any other call is made.
    ///
    /// The first touch of IOBluetooth initialises an internal CoreBluetooth coordinator whose init
    /// waits on a semaphore signalled from the main thread. If that first touch happens on the main
    /// thread the wait can never be satisfied, and touching IOBluetooth from two threads at once
    /// makes them wait on each other's `dispatch_once`. Doing the very first call off the main
    /// thread, with the run loop free, avoids both deadlocks.
    func warmUpIOBluetooth() async {
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
            DispatchQueue.global(qos: .userInitiated).async {
                _ = IOBluetoothDevice.pairedDevices()
                continuation.resume()
            }
        }
    }

    /// Loads paired peers off the main thread.
    ///
    /// `IOBluetoothDevice.pairedDevices()` initialises IOBluetooth's CoreBluetooth coordinator on
    /// first use, which waits for the main thread, so it must never run on the main thread and must
    /// not race another first-use call. Call `warmUpIOBluetooth()` first.
    func refreshPairedDevices() {
        DispatchQueue.global(qos: .userInitiated).async {
            let devices = (IOBluetoothDevice.pairedDevices() as? [IOBluetoothDevice]) ?? []
            let peers: [Peer] = devices.compactMap { peer in
                guard let address = peer.addressString else { return nil }
                let name = peer.name ?? address
                return Peer(
                    id: self.normalize(address),
                    name: name,
                    isConnected: peer.isConnected()
                )
            }
            .sorted { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }

            Task { @MainActor in
                self.pairedDevices = peers
            }
        }
    }

    // MARK: - Pairing

    /// Starts Classic pairing so a bond exists before HIDP channels are opened.
    ///
    /// IOBluetooth documents that a numeric comparison value must be shown to the user and the
    /// reply must carry their accept/decline decision, so the value is published for comparison
    /// and confirmation comes from `confirmPairing(_:)`.
    private func startPairing(with stored: IOBluetoothDevice, address: String) {
        guard pairer == nil else { return }
        publishService()
        pendingAddress = address
        pairingCode = nil
        pairingState = "starting"
        holdAgainstTermination()
        note("not bonded yet: starting Classic pairing")

        guard let attempt = IOBluetoothDevicePair(device: stored) else {
            lastError = "Could not create a pairing session"
            pairingState = "failed"
            releaseTerminationHold()
            return
        }
        attempt.delegate = self
        pairer = attempt
        let result = attempt.start()
        if result != kIOReturnSuccess {
            lastError = "Pairing start failed (\(Self.ioCode(result)))"
            pairingState = "failed"
            pairer = nil
            releaseTerminationHold()
        }
    }

    /// Sends the user's numeric-comparison decision and continues on success.
    func confirmPairing(_ accept: Bool) {
        guard let pairer else { return }
        pairingState = accept ? "accepted by user" : "declined by user"
        note("pairing confirmation: \(accept ? "accept" : "decline")")
        pairer.replyUserConfirmation(accept)
        if !accept {
            pairer.stop()
            self.pairer = nil
            releaseTerminationHold()
        }
    }

    private func finishPairing(error: IOReturn) {
        pairer = nil
        let address = pendingAddress
        pendingAddress = nil
        pairingCode = nil
        guard error == kIOReturnSuccess else {
            pairingState = "failed"
            lastError = "Pairing failed (\(Self.ioCode(error)))"
            note(lastError ?? "")
            releaseTerminationHold()
            return
        }
        pairingState = "paired"
        note("pairing complete")
        refreshPairedDevices()
        if let address {
            connect(to: address)
        }
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
        // HIDP requires an authenticated, encrypted link, so pair first when no bond exists.
        // A bond can also be stale: the peer may have removed this Mac while the local record
        // survives, and there is no public API to delete it. Pairing rewrites the link key.
        if forcePairing || !stored.isPaired() {
            startPairing(with: stored, address: normalized)
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
                HIDIdentity.classOfDevice,
                forTimeInterval: HIDIdentity.classOfDeviceDuration
            )
            note(codResult == kIOReturnSuccess
                ? "class of device -> 0x\(String(HIDIdentity.classOfDevice, radix: 16))"
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
        // Measurement aid: the negotiated protocol decides whether a report carries a report ID.
        // Recorded at the moment the channels are ready, before the host sends SET_PROTOCOL.
        note("channels ready protocol=report (default, host has not set it yet)")
        refreshPairedDevices()
    }

    func disconnect(keepError: Bool = false) {
        let hadChannels = controlChannel != nil || interruptChannel != nil
        controlChannel?.close()
        interruptChannel?.close()
        if hadChannels {
            // The close is asynchronous; the closed callback owns the cleanup and the termination
            // hold. Nil-ing the references and releasing the hold here raced the HCI teardown and
            // bluetoothd kept the HID session: the next launch failed with 0xE00002BC (measured
            // 2026-11-24, both with an immediate exit and with a 1.5 s hold after stop()).
            awaitingChannelClose = true
            // Drop the ACL too: with only the L2CAP channels closed, the peer's HID host kept a
            // half-open session and never answered the next PSM 17 open (kIOReturnTimeout-style
            // stall), while an ACL drop makes the HID host clean its own state.
            if let device {
                device.closeConnection()
            }
        } else {
            controlChannel = nil
            interruptChannel = nil
            finishDisconnect(keepError: keepError)
        }
    }

    /// True while the L2CAP closed-callbacks have not run yet after a close.
    public private(set) var awaitingChannelClose = false

    private func finishDisconnect(keepError: Bool) {
        connectedAddress = nil
        isReady = false
        protocolMode = 0x01
        idleRate = 0
        if !keepError { lastError = nil }
        awaitingChannelClose = false
        releaseTerminationHold()
        refreshPairedDevices()
    }

    func stop() {
        // Unpublish FIRST: a HID host auto-reconnects when the control channel drops while the
        // service record is still advertised, so the dying process would immediately own a fresh
        // session it cannot clean up. Measured 2026-11-24: SIGTERM with channels closed and the
        // record still up left the peer's HID host half-open and the next launch failed with
        // 0xE00002BC until the Mac's Bluetooth was cycled.
        unpublishService()
        disconnect()
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
    ///   --harness-type <text>               type a US-layout ASCII string once channels are ready
    func runHarnessIfRequested() {
        guard !harnessRan else { return }
        harnessRan = true
        let arguments = ProcessInfo.processInfo.arguments
        let wantsPublish = HarnessFlags.isOn("--harness-publish")
        let connectionIndex = HarnessFlags.isOn("--harness-connect")
            ? arguments.firstIndex(of: "--harness-connect") : nil
        let wantsType = HarnessFlags.isOn("--harness-type")
        guard wantsPublish || connectionIndex != nil || HarnessFlags.isOn("--harness-send") || wantsType else { return }

        publishService()
        if let connectionIndex, arguments.indices.contains(connectionIndex + 1) {
            // Only an automated run whose operator has already verified the numeric value may
            // answer the confirmation without a click.
            autoAcceptPairing = HarnessFlags.environment("P17_AUTO_ACCEPT_PAIRING") == "1"
            forcePairing = HarnessFlags.isOn("--harness-force-pair")
            connect(to: arguments[connectionIndex + 1])
        }

        if let sendIndex = (HarnessFlags.isOn("--harness-send") ? arguments.firstIndex(of: "--harness-send") : nil),
           arguments.indices.contains(sendIndex + 1) {
            sendHarnessArrow(named: arguments[sendIndex + 1].uppercased())
        }

        if let typeIndex = (wantsType ? arguments.firstIndex(of: "--harness-type") : nil),
           arguments.indices.contains(typeIndex + 1) {
            sendHarnessText(arguments[typeIndex + 1])
        }
    }

    /// Types one string over the HID link after the host finishes discovery. Debug harness only.
    private func sendHarnessText(_ text: String) {
        Task { @MainActor in
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard self.isReady else {
                self.lastError = "Harness type skipped: channels not ready"
                self.note("harness type skipped (not ready)")
                return
            }
            await self.typeText(text)
        }
    }

    private func sendHarnessArrow(named name: String) {
        let names = name.split(separator: ",").map { String($0).uppercased() }
        var plan: [(String, UInt8)] = []
        for entry in names {
            switch entry {
            case "UP": plan.append((entry, 0x52))
            case "DOWN": plan.append((entry, 0x51))
            case "LEFT": plan.append((entry, 0x50))
            case "RIGHT": plan.append((entry, 0x4F))
            default:
                lastError = "Unknown harness arrow '\(entry)'"
                return
            }
        }
        guard !plan.isEmpty else { return }
        Task { @MainActor in
            // Give the host time to finish HID discovery before the first report.
            try? await Task.sleep(nanoseconds: 6_000_000_000)
            guard self.isReady else {
                self.lastError = "Harness send skipped: channels not ready"
                self.note("harness send skipped (not ready)")
                return
            }
            // One HID session per connection: Android keeps the previous link until it times out,
            // so every report is sent over this single control/interrupt pair.
            for (label, usage) in plan {
                self.sendArrow(usage)
                self.note("harness sent \(label)")
                try? await Task.sleep(nanoseconds: 900_000_000)
            }
        }
    }

    // MARK: - Reports

    /// Sends one arrow press followed by a release. The byte pattern is unchanged from the
    /// P17 acceptance runs (report 2, key usage in slot 0, then a neutral report).
    func sendArrow(_ usage: UInt8) {
        sendKeyReport(modifiers: 0, keys: [usage])
    }

    /// Sends one arbitrary key press/release by HID keyboard usage (1...101).
    func sendKey(_ usage: UInt8, modifiers: UInt8 = 0) {
        sendKeyReport(modifiers: modifiers, keys: [usage])
    }

    /// Surfaces a caller-side input validation error in the shared activity log.
    func reportInputError(_ message: String) {
        lastError = message
        note(message)
    }

    /// Types a string as a sequence of key press/release pairs using the US HID keyboard map.
    /// Characters outside the map are reported and skipped; nothing is read from the clipboard.
    ///
    /// Android's HID stack drops reports sent back-to-back on the interrupt channel (the same
    /// reason the arrow harness paces presses). Each character is therefore paced so the peer can
    /// consume one press/release pair before the next arrives.
    func typeText(_ text: String, interKeyDelay: Duration = .milliseconds(120)) async {
        guard isReady else {
            lastError = "Not connected: no HID channels"
            return
        }
        var skipped: [String] = []
        var sent = 0
        for character in text {
            guard let press = HIDKeyboardMap.press(for: character) else {
                skipped.append(String(character))
                continue
            }
            sendKeyReport(modifiers: press.modifiers, keys: [press.usage])
            sent += 1
            try? await Task.sleep(for: interKeyDelay)
        }
        if skipped.isEmpty {
            note("typed \(sent) characters")
        } else {
            lastError = "Skipped unmappable characters: \(skipped.joined())"
            note("typed \(sent) characters; skipped \(skipped.count)")
        }
    }

    /// Shared report builder: one press report followed by a neutral release. Every report still
    /// travels over the single HID control/interrupt pair created by this app.
    private func sendKeyReport(modifiers: UInt8, keys: [UInt8]) {
        guard isReady else {
            lastError = "Not connected: no HID channels"
            return
        }
        guard let pressed = HIDKeyboardMap.report(modifiers: modifiers, keys: keys) else {
            lastError = "Invalid keyboard report (usage or key count out of range)"
            return
        }
        sendInputReport(HIDIdentity.ReportID.keyboard.rawValue, payload: pressed)
        sendInputReport(HIDIdentity.ReportID.keyboard.rawValue, payload: HIDIdentity.neutralReport)
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
        // Measurement aid: what was actually written, in which protocol, and whether the channel
        // accepted it. A successful write only means the stack queued it, so a host that rejects the
        // report looks identical here to one that accepts it; the counter it moves is the phone's.
        log.info("report written id=\(reportID, privacy: .public) protocol=\(self.protocolMode == 0x01 ? "report" : "boot", privacy: .public) bytes=\(byteCount, privacy: .public) prefix=0x\(String(buffer[0], radix: 16), privacy: .public) status=\(String(status, radix: 16), privacy: .public) total=\(self.reportsSent, privacy: .public)")
    }

    // MARK: - HIDP control channel

    private func handleHostMessage(_ bytes: Data, on channel: IOBluetoothL2CAPChannel) {
        guard let header = bytes.first else { return }
        let parameter = header & 0x0F
        // Measurement aid: every control-channel message the host sends, so a missing SET_PROTOCOL
        // or a rejected report is visible rather than inferred.
        log.info("host msg opcode=0x\(String(header & 0xF0, radix: 16), privacy: .public) parameter=0x\(String(parameter, radix: 16), privacy: .public) bytes=\(bytes.count, privacy: .public)")

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

extension ClassicHIDPeripheral: @preconcurrency IOBluetoothDevicePairDelegate {
    func devicePairingStarted(_ sender: Any!) {
        Task { @MainActor in
            self.pairingState = "pairing"
            self.note("pairing started")
        }
    }

    func devicePairingConnecting(_ sender: Any!) {
        Task { @MainActor in self.pairingState = "connecting" }
    }

    func devicePairingConnected(_ sender: Any!) {
        Task { @MainActor in self.pairingState = "connected" }
    }

    func devicePairingPINCodeRequest(_ sender: Any!) {
        Task { @MainActor in
            // The experiment has no PIN UI; an unhandled PIN request must fail closed.
            self.pairingState = "PIN requested"
            self.lastError = "Peer requested a PIN code; the POC does not provide one"
            (sender as? IOBluetoothDevicePair)?.stop()
        }
    }

    func devicePairingUserConfirmationRequest(_ sender: Any!, numericValue: BluetoothNumericValue) {
        Task { @MainActor in
            let code = String(format: "%06u", numericValue)
            self.pairingCode = code
            self.pairingState = "awaiting confirmation"
            self.note("numeric comparison value \(code)")
            if self.autoAcceptPairing {
                self.note("auto-accepting because the owner pre-verified this run")
                self.confirmPairing(true)
            }
        }
    }

    func devicePairingUserPasskeyNotification(_ sender: Any!, passkey: BluetoothPasskey) {
        Task { @MainActor in
            let code = String(format: "%06u", passkey)
            self.pairingCode = code
            self.pairingState = "passkey notification"
            self.note("passkey notification \(code)")
        }
    }

    func devicePairingFinished(_ sender: Any!, error: IOReturn) {
        Task { @MainActor in
            self.finishPairing(error: error)
        }
    }

    func deviceSimplePairingComplete(_ sender: Any!, status: BluetoothHCIEventStatus) {
        Task { @MainActor in
            self.note("simple pairing complete status \(status)")
        }
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
                let wasAwaiting = self.awaitingChannelClose
                if let device {
                    device.closeConnection()
                }
                self.device = nil
                self.finishDisconnect(keepError: false)
                self.note("HID channels closed")
                if wasAwaiting {
                    self.closeWaitContinuation?()
                    self.closeWaitContinuation = nil
                }
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
