// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
//
// HID report descriptor and SDP record for the P17 Classic HIDP experiment.
//
// Independently authored against the Bluetooth HID Profile specification (HIDP 1.1) and the
// Bluetooth Core specification's SDP attribute identifiers. No third-party implementation
// source was copied. The macOS-specific dictionary shape required by
// `IOBluetoothSDPServiceRecord.publishedServiceRecord(with:)` is Apple public API, and the
// attribute meanings below come from the HID Service specification.
import Foundation

/// Minimal keyboard-only HID identity for the experiment.
enum HIDIdentity {
    static let serviceName = "DroidBridge HID"
    static let providerName = "DroidBridge"
    static let serviceDescription = "Keyboard"

    /// Report identifiers used by the report descriptor below.
    enum ReportID: UInt8 {
        case keyboard = 2
        case keyboardLEDs = 3
    }

    /// HIDDeviceSubclass value for a keyboard (HID Profile spec, keyboard bit).
    static let deviceSubclass: UInt32 = 0x00C0
    /// Local Class of Device published while acting as a HID keyboard peripheral.
    ///
    /// 0x2540 = Peripheral major class, keyboard minor class. Android decides whether to accept
    /// a device-initiated HID connection partly from the peer's CoD, so the Mac must present a
    /// peripheral/keyboard class instead of its default computer class.
    static let classOfDevice: UInt32 = 0x2540
    /// Seconds the temporary Class of Device stays applied.
    static let classOfDeviceDuration: TimeInterval = 300
    /// HIDCountryCode: 0 = not supported. A non-zero code would require key-mapping tables.
    static let countryCode: UInt32 = 0x0000
    /// bcdHID advertised to the host.
    static let parserVersion: UInt32 = 0x0111
    /// HIDP version advertised in the SDP profile descriptor list.
    static let profileVersion: UInt32 = 0x0101
    /// HIDDeviceReleaseNumber.
    static let deviceReleaseNumber: UInt32 = 0x0100
    /// HIDSupervisionTimeout in milliseconds.
    static let supervisionTimeout: UInt32 = 0x1F40

    /// 8-byte boot-style keyboard input report: modifiers, reserved, six key usages.
    static let keyboardInputLength = 8

    /// Report Map describing a keyboard with LED output.
    ///
    /// Report ID 2 carries modifiers, a reserved byte and six key usages.
    /// Report ID 3 carries the five keyboard LED bits plus padding.
    static let reportMap = Data([
        0x05, 0x01,              // Usage Page (Generic Desktop)
        0x09, 0x06,              // Usage (Keyboard)
        0xA1, 0x01,              // Collection (Application)
        0x85, ReportID.keyboard.rawValue,
        0x05, 0x07,              //   Usage Page (Keyboard/Keypad)
        0x19, 0xE0,              //   Usage Minimum (LeftControl)
        0x29, 0xE7,              //   Usage Maximum (RightGUI)
        0x15, 0x00,              //   Logical Minimum (0)
        0x25, 0x01,              //   Logical Maximum (1)
        0x75, 0x01,              //   Report Size (1)
        0x95, 0x08,              //   Report Count (8)
        0x81, 0x02,              //   Input (Data, Variable, Absolute): modifier bits
        0x95, 0x01,              //   Report Count (1)
        0x75, 0x08,              //   Report Size (8)
        0x81, 0x01,              //   Input (Constant): reserved byte
        0x19, 0x00,              //   Usage Minimum (0)
        0x29, 0x65,              //   Usage Maximum (101)
        0x15, 0x00,              //   Logical Minimum (0)
        0x25, 0x65,              //   Logical Maximum (101)
        0x75, 0x08,              //   Report Size (8)
        0x95, 0x06,              //   Report Count (6)
        0x81, 0x00,              //   Input (Data, Array): six key usages
        0x85, ReportID.keyboardLEDs.rawValue,
        0x05, 0x08,              //   Usage Page (LEDs)
        0x19, 0x01,              //   Usage Minimum (Num Lock)
        0x29, 0x05,              //   Usage Maximum (Kana)
        0x15, 0x00,              //   Logical Minimum (0)
        0x25, 0x01,              //   Logical Maximum (1)
        0x75, 0x01,              //   Report Size (1)
        0x95, 0x05,              //   Report Count (5)
        0x91, 0x02,              //   Output (Data, Variable, Absolute): LED bits
        0x95, 0x03,              //   Report Count (3)
        0x91, 0x03,              //   Output (Constant): padding
        0xC0                     // End Collection
    ])

    /// Neutral report: no modifiers, no keys.
    static let neutralReport = Data(repeating: 0, count: keyboardInputLength)
}

/// Builds the SDP service record attributes for a HID device.
///
/// `IOBluetoothSDPServiceRecord.publishedServiceRecord(with:)` accepts a dictionary keyed by
/// "<attribute id hex> - <name>". Plain `NSNumber`, `String`, arrays and booleans are converted
/// by IOBluetooth; a nested dictionary with `DataElementType`/`DataElementValue` forces an exact
/// SDP data element, which is required for integer widths and raw descriptor bytes.
enum P17SDPRecord {
    /// SDP data element type codes from the Bluetooth Core specification.
    private enum ElementType {
        static let unsignedInteger = 1
        static let textString = 4
    }

    /// Assembles the HID device service record (service class 0x1124).
    static func hidDevice() -> [String: Any] {
        [
            "0000 - ServiceRecordHandle": 65_536 as NSNumber,
            "0001 - ServiceClassIDList": [
                uuid16(0x1124) // HID
            ],
            // Control channel (PSM 0x0011) on L2CAP.
            "0004 - ProtocolDescriptorList": [
                [
                    uuid16(0x0100), // L2CAP
                    unsigned(0x0011, bytes: 2)
                ],
                [
                    uuid16(0x0011) // HIDP
                ]
            ],
            "0005 - BrowseGroupList": [
                uuid16(0x1002) // PublicBrowseRoot
            ],
            "0006 - LanguageBaseAttributeIDList": [
                unsigned(0x656E, bytes: 2), // "en"
                unsigned(0x006A, bytes: 2), // UTF-8
                unsigned(0x0100, bytes: 2)  // base attribute offset
            ],
            "0009 - BluetoothProfileDescriptorList": [
                [
                    uuid16(0x1124),
                    unsigned(HIDIdentity.profileVersion, bytes: 2)
                ]
            ],
            // Interrupt channel (PSM 0x0013), expressed as an additional protocol descriptor.
            "000D - AdditionalProtocolDescriptorList": [
                [
                    [
                        uuid16(0x0100),
                        unsigned(0x0013, bytes: 2)
                    ],
                    [
                        uuid16(0x0011)
                    ]
                ]
            ],
            "0100 - ServiceName": HIDIdentity.serviceName,
            "0101 - ServiceDescription": HIDIdentity.serviceDescription,
            "0102 - ProviderName": HIDIdentity.providerName,

            "0200 - HIDDeviceReleaseNumber": unsigned(HIDIdentity.deviceReleaseNumber, bytes: 2),
            "0201 - HIDParserVersion": unsigned(HIDIdentity.parserVersion, bytes: 2),
            "0202 - HIDDeviceSubclass": unsigned(HIDIdentity.deviceSubclass, bytes: 1),
            "0203 - HIDCountryCode": unsigned(HIDIdentity.countryCode, bytes: 1),
            "0204 - HIDVirtualCable": true,
            "0205 - HIDReconnectInitiate": true,
            // HIDDescriptorList: one entry, report-descriptor type 0x22, raw descriptor bytes.
            "0206 - HIDDescriptorList": [
                [
                    unsigned(0x22, bytes: 1),
                    text(HIDIdentity.reportMap)
                ]
            ],
            "0207 - HIDLANGIDBaseList": [
                [
                    unsigned(0x0409, bytes: 2), // en-US
                    unsigned(0x0100, bytes: 2)
                ]
            ],
            "0209 - HIDBatteryPower": true,
            "020A - HIDRemoteWake": true,
            "020B - HIDProfileVersion": unsigned(HIDIdentity.profileVersion, bytes: 2),
            "020C - HIDSupervisionTimeout": unsigned(HIDIdentity.supervisionTimeout, bytes: 2),
            "020D - HIDNormallyConnectable": true,
            "020E - HIDBootDevice": true
        ]
    }

    /// Two-byte big-endian UUID value, as IOBluetooth expects for a 16-bit UUID element.
    private static func uuid16(_ value: UInt16) -> Data {
        Data([UInt8(value >> 8), UInt8(value & 0xFF)])
    }

    /// Forces an unsigned-integer data element of an exact width.
    private static func unsigned(_ value: UInt32, bytes: Int) -> [String: Any] {
        var data = Data()
        for shift in stride(from: (bytes - 1) * 8, through: 0, by: -8) {
            data.append(UInt8((value >> UInt32(shift)) & 0xFF))
        }
        return [
            "DataElementSize": 0,
            "DataElementType": ElementType.unsignedInteger,
            "DataElementValue": data
        ]
    }

    /// Forces a text-string data element, used to carry the raw report descriptor bytes.
    private static func text(_ bytes: Data) -> [String: Any] {
        [
            "DataElementType": ElementType.textString,
            "DataElementValue": bytes
        ]
    }
}
