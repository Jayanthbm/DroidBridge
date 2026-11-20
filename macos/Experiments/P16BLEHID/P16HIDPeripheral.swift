import Combine
import CoreBluetooth
import Foundation

/// Isolated P16 BLE HID Boot Keyboard feasibility peripheral.
/// Never captures host-wide input; keys are sent only from explicit POC buttons.
final class P16HIDPeripheral: NSObject, ObservableObject {
    @Published private(set) var state = "Bluetooth has not been started"
    @Published private(set) var advertising = false
    @Published private(set) var subscribed = false
    @Published private(set) var servicePublished = false
    @Published private(set) var started = false
    @Published private(set) var reportCount = 0

    // Use canonical Bluetooth-base UUIDs for GATT registration. Keep the short
    // HID UUID only for advertising, as in the independently reviewed reference.
    private let hidServiceID = CBUUID(string: "00001812-0000-1000-8000-00805F9B34FB")
    private let hidServiceAdvertisementID = CBUUID(string: "1812")
    private let batteryServiceID = CBUUID(string: "0000180F-0000-1000-8000-00805F9B34FB")
    private let deviceInformationServiceID = CBUUID(string: "0000180A-0000-1000-8000-00805F9B34FB")
    private let batteryLevelID = CBUUID(string: "00002A19-0000-1000-8000-00805F9B34FB")
    private let manufacturerNameID = CBUUID(string: "00002A29-0000-1000-8000-00805F9B34FB")
    private let modelNumberID = CBUUID(string: "00002A24-0000-1000-8000-00805F9B34FB")
    private let pnpIDID = CBUUID(string: "00002A50-0000-1000-8000-00805F9B34FB")
    private let hidInformationID = CBUUID(string: "00002A4A-0000-1000-8000-00805F9B34FB")
    private let reportMapID = CBUUID(string: "00002A4B-0000-1000-8000-00805F9B34FB")
    private let hidControlPointID = CBUUID(string: "00002A4C-0000-1000-8000-00805F9B34FB")
    private let protocolModeID = CBUUID(string: "00002A4E-0000-1000-8000-00805F9B34FB")
    private let bootKeyboardInputID = CBUUID(string: "00002A22-0000-1000-8000-00805F9B34FB")
    private let bootKeyboardOutputID = CBUUID(string: "00002A32-0000-1000-8000-00805F9B34FB")

    private var manager: CBPeripheralManager?
    private var batteryService: CBMutableService?
    private var deviceInformationService: CBMutableService?
    private var information: CBMutableCharacteristic?
    private var reportMap: CBMutableCharacteristic?
    private var protocolMode: CBMutableCharacteristic?
    private var bootKeyboardInput: CBMutableCharacteristic?
    private var bootKeyboardOutput: CBMutableCharacteristic?
    private var protocolModeValue: UInt8 = 0x01 // HIDS default: Report Protocol
    private var keyboardLEDValue: UInt8 = 0
    private var suspended = false
    private var subscribedInputs = Set<CBUUID>()
    private var hasStarted = false
    private var pendingReports: [Data] = []

    // This is deliberately Boot Protocol only. Apple's public
    // CBMutableDescriptor API does not support the Report Reference descriptor
    // (0x2908) required to serve HOGP Report Protocol characteristics. HID
    // Service still exposes its mandatory static Report Map, but no Report
    // characteristics; Boot Hosts ignore the map and use fixed 8-byte reports.
    private let keyboardReportMap = Data([
        0x05, 0x01,       // Usage Page (Generic Desktop)
        0x09, 0x06,       // Usage (Keyboard)
        0xA1, 0x01,       // Collection (Application)
        0x05, 0x07,       // Usage Page (Key Codes)
        0x19, 0xE0,       // Usage Minimum (Left Control)
        0x29, 0xE7,       // Usage Maximum (Right GUI)
        0x15, 0x00,       // Logical Minimum (0)
        0x25, 0x01,       // Logical Maximum (1)
        0x75, 0x01,       // Report Size (1)
        0x95, 0x08,       // Report Count (8)
        0x81, 0x02,       // Input: modifier byte
        0x95, 0x01,
        0x75, 0x08,
        0x81, 0x03,       // Input: reserved byte
        0x05, 0x08,       // Usage Page (LEDs)
        0x19, 0x01,       // Usage Minimum (Num Lock)
        0x29, 0x05,       // Usage Maximum (Kana)
        0x15, 0x00,
        0x25, 0x01,
        0x75, 0x01,
        0x95, 0x05,
        0x91, 0x02,       // Output: LED states
        0x95, 0x03,
        0x91, 0x03,       // Output: LED padding
        0x05, 0x07,       // Usage Page (Key Codes)
        0x15, 0x00,
        0x25, 0x65,
        0x19, 0x00,
        0x29, 0x65,
        0x75, 0x08,
        0x95, 0x06,
        0x81, 0x00,       // Input: six key codes
        0xC0
    ])

    func start() {
        hasStarted = true
        started = true
        if let manager {
            guard manager.state == .poweredOn else {
                state = "Cannot start: Bluetooth state is \(managerStateName(manager.state))"
                return
            }
            installServices()
            return
        }
        state = "Requesting Bluetooth access after explicit Start action…"
        manager = CBPeripheralManager(delegate: self, queue: .main, options: nil)
    }

    func startAdvertising() {
        guard let manager, manager.state == .poweredOn, servicePublished else {
            state = "Cannot advertise: HID service is not published yet"
            return
        }
        manager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [hidServiceAdvertisementID],
            CBAdvertisementDataLocalNameKey: "P16 Test Keyboard"
        ])
        state = "Advertising request sent"
    }

    func stop() {
        hasStarted = false
        started = false
        pendingReports.removeAll()
        manager?.stopAdvertising()
        manager?.removeAllServices()
        clearServices()
        advertising = false
        subscribed = false
        subscribedInputs.removeAll()
        suspended = false
        state = "Stopped by user; no BLE advertising or HID service remains active"
    }

    func sendArrow(_ key: ArrowKey) {
        guard advertising,
              protocolModeValue == 0x00,
              subscribedInputs.contains(bootKeyboardInputID),
              !suspended,
              bootKeyboardInput != nil,
              manager?.state == .poweredOn else {
            state = "Not sent: Android must select Boot Protocol and subscribe to keyboard input first"
            return
        }
        guard pendingReports.isEmpty else {
            state = "Not sent: previous notification is still queued"
            return
        }

        var pressed = Data(repeating: 0, count: 8)
        pressed[2] = key.usage
        pendingReports = [pressed, Data(repeating: 0, count: 8)]
        state = "Sending one synthetic \(key.label) key press/release"
        pumpReports()
    }

    private func installServices() {
        guard let manager, manager.state == .poweredOn else { return }
        manager.removeAllServices()
        clearServices()
        advertising = false
        subscribed = false
        subscribedInputs.removeAll()
        suspended = false
        protocolModeValue = 0x01
        keyboardLEDValue = 0
        pendingReports.removeAll()

        let battery = CBMutableService(type: batteryServiceID, primary: true)
        battery.characteristics = [
            CBMutableCharacteristic(
                type: batteryLevelID,
                properties: [.read, .notifyEncryptionRequired],
                value: nil,
                permissions: [.readable, .readEncryptionRequired]
            )
        ]
        batteryService = battery
        manager.add(battery)
        state = "Battery Service added; waiting for publication"
    }

    private func buildDeviceInformationService() -> CBMutableService {
        let service = CBMutableService(type: deviceInformationServiceID, primary: true)
        // PnP ID is mandatory for HOGP. 0xFFFF is a test-only placeholder VID,
        // not a claim to an assigned commercial vendor identifier.
        let pnpID = Data([0x01, 0xFF, 0xFF, 0x01, 0x00, 0x00, 0x01])
        service.characteristics = [
            CBMutableCharacteristic(
                type: manufacturerNameID,
                properties: .read,
                value: Data("DroidBridge".utf8),
                permissions: .readable
            ),
            CBMutableCharacteristic(
                type: modelNumberID,
                properties: .read,
                value: Data("P16 BLE HID Test".utf8),
                permissions: .readable
            ),
            CBMutableCharacteristic(
                type: pnpIDID,
                properties: .read,
                value: pnpID,
                permissions: .readable
            )
        ]
        return service
    }

    private func buildHIDService() -> CBMutableService {
        let service = CBMutableService(type: hidServiceID, primary: true)

        let hidInformation = CBMutableCharacteristic(
            type: hidInformationID,
            properties: .read,
            value: Data([0x11, 0x01, 0x00, 0x02]), // HID 1.11, country 0, normally connectable, no remote wake
            permissions: [.readable, .readEncryptionRequired]
        )
        information = hidInformation
        let controlPoint = CBMutableCharacteristic(
            type: hidControlPointID,
            properties: [.writeWithoutResponse],
            value: nil,
            permissions: [.writeEncryptionRequired]
        )
        let hidProtocolMode = CBMutableCharacteristic(
            type: protocolModeID,
            properties: [.read, .writeWithoutResponse],
            value: nil,
            permissions: [.readEncryptionRequired, .writeEncryptionRequired]
        )
        protocolMode = hidProtocolMode
        let bootInput = CBMutableCharacteristic(
            type: bootKeyboardInputID,
            properties: [.read, .notifyEncryptionRequired],
            value: nil,
            permissions: [.readable, .readEncryptionRequired]
        )
        bootKeyboardInput = bootInput
        let bootOutput = CBMutableCharacteristic(
            type: bootKeyboardOutputID,
            properties: [.read, .write, .writeWithoutResponse],
            value: nil,
            permissions: [.readable, .readEncryptionRequired, .writeable, .writeEncryptionRequired]
        )
        bootKeyboardOutput = bootOutput

        let hidReportMap = CBMutableCharacteristic(
            type: reportMapID,
            properties: .read,
            value: keyboardReportMap,
            permissions: [.readable, .readEncryptionRequired]
        )
        reportMap = hidReportMap

        service.characteristics = [controlPoint, hidProtocolMode, hidInformation, bootInput, bootOutput, hidReportMap]
        return service
    }

    private func clearServices() {
        batteryService = nil
        deviceInformationService = nil
        information = nil
        reportMap = nil
        protocolMode = nil
        bootKeyboardInput = nil
        bootKeyboardOutput = nil
        servicePublished = false
    }

    private func pumpReports() {
        guard let manager,
              protocolModeValue == 0x00,
              let destination = bootKeyboardInput,
              subscribedInputs.contains(bootKeyboardInputID),
              !suspended else { return }
        while let next = pendingReports.first {
            guard manager.updateValue(next, for: destination, onSubscribedCentrals: nil) else {
                state = "Notification queue full; waiting for CoreBluetooth readiness"
                return
            }
            pendingReports.removeFirst()
            reportCount += 1
            if pendingReports.isEmpty {
                state = "Sent synthetic keyboard report \(reportCount); key released"
            }
        }
    }

    private func managerStateName(_ state: CBManagerState) -> String {
        switch state {
        case .unknown: "unknown"
        case .resetting: "resetting"
        case .unsupported: "unsupported"
        case .unauthorized: "unauthorized"
        case .poweredOff: "powered off"
        case .poweredOn: "powered on"
        @unknown default: "unknown future state (\(state.rawValue))"
        }
    }
}

extension P16HIDPeripheral: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        state = "Bluetooth state: \(managerStateName(peripheral.state))"
        guard peripheral.state == .poweredOn else {
            advertising = false
            subscribed = false
            subscribedInputs.removeAll()
            servicePublished = false
            return
        }
        guard hasStarted else {
            state = "Bluetooth is ready; press Start Bluetooth to add the isolated HID service"
            return
        }
        installServices()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error {
            let nsError = error as NSError
            servicePublished = false
            state = "GATT service \(service.uuid) publication failed: \(nsError.localizedDescription) [\(nsError.domain) code \(nsError.code)]"
            return
        }

        switch service.uuid {
        case batteryServiceID:
            let deviceInfo = buildDeviceInformationService()
            deviceInformationService = deviceInfo
            peripheral.add(deviceInfo)
            state = "Battery Service published; adding Device Information Service"
        case deviceInformationServiceID:
            guard deviceInformationService != nil else { return }
            peripheral.add(buildHIDService())
            state = "Device Information Service published; adding HID Service"
        case hidServiceID:
            servicePublished = true
            state = "Boot-only HID, Battery, and Device Information services published; press Start Advertising"
        default:
            break
        }
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error {
            advertising = false
            let nsError = error as NSError
            state = "Advertising failed: \(nsError.localizedDescription) [\(nsError.domain) code \(nsError.code)]"
        } else {
            advertising = true
            state = "Advertising BLE Boot Keyboard as P16 Test Keyboard"
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic === bootKeyboardInput else { return }
        subscribedInputs.insert(characteristic.uuid)
        subscribed = !subscribedInputs.isEmpty
        // Send the neutral initial state some HID hosts require after enabling CCCD.
        pendingReports = [Data(repeating: 0, count: 8)]
        state = "Android subscribed to Boot Protocol keyboard input"
        pumpReports()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic === bootKeyboardInput else { return }
        subscribedInputs.remove(characteristic.uuid)
        subscribed = !subscribedInputs.isEmpty
        if !subscribed { pendingReports.removeAll() }
        state = "Android unsubscribed from keyboard reports"
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.offset == 0 else {
            peripheral.respond(to: request, withResult: .invalidOffset)
            return
        }
        switch request.characteristic.uuid {
        case batteryLevelID: request.value = Data([100])
        case hidInformationID: request.value = information?.value
        case reportMapID: request.value = reportMap?.value
        case protocolModeID: request.value = Data([protocolModeValue])
        case bootKeyboardInputID: request.value = Data(repeating: 0, count: 8)
        case bootKeyboardOutputID: request.value = Data([keyboardLEDValue])
        default:
            peripheral.respond(to: request, withResult: .requestNotSupported)
            return
        }
        peripheral.respond(to: request, withResult: .success)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        var result: CBATTError.Code = .success
        for request in requests {
            guard request.offset == 0, let value = request.value else {
                result = .invalidAttributeValueLength
                continue
            }

            if request.characteristic === bootKeyboardOutput {
                guard value.count == 1 else {
                    result = .invalidAttributeValueLength
                    continue
                }
                keyboardLEDValue = value[0]
                continue
            }

            guard value.count == 1 else {
                result = .invalidAttributeValueLength
                continue
            }
            switch request.characteristic.uuid {
            case protocolModeID where value[0] == 0x00 || value[0] == 0x01:
                protocolModeValue = value[0]
                state = protocolModeValue == 0x00
                    ? "Android selected Boot Protocol; keyboard reports are available"
                    : "Android selected Report Protocol; this Boot-only POC will not send reports"
                pumpReports()
            case hidControlPointID:
                guard value[0] == 0x00 || value[0] == 0x01 else {
                    result = .requestNotSupported
                    continue
                }
                suspended = value[0] == 0x00
                if suspended { pendingReports.removeAll() }
                state = suspended ? "HID reports suspended by Android" : "HID reports resumed by Android"
            default:
                result = .requestNotSupported
            }
        }
        if let request = requests.first {
            peripheral.respond(to: request, withResult: result)
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        pumpReports()
    }
}

enum ArrowKey: CaseIterable, Identifiable {
    case up, down, left, right

    var id: Self { self }
    var label: String { String(describing: self).uppercased() }
    var usage: UInt8 {
        switch self {
        case .up: 0x52
        case .down: 0x51
        case .left: 0x50
        case .right: 0x4F
        }
    }
}
