// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
import SwiftUI

/// The Phase 5 HID remote: the P17 feasibility surface promoted into the product app.
///
/// Every safety control from the experiment survives the promotion unchanged, because the
/// acceptance evidence (P17, 48/48 arrow presses on both phones) was gathered with exactly
/// these controls: one arrow press per confirmed foreground check, no pointer, no text entry.
struct HIDRemoteView: View {
    @ObservedObject var peripheral: ClassicHIDPeripheral
    @State private var receiverConfirmed = false
    @State private var textConfirmed = false
    @State private var peerAddress = ""
    @State private var textToType = ""
    @State private var keyUsage = ""
    @State private var modifierShift = false
    @State private var modifierControl = false
    @State private var modifierAlt = false
    @State private var modifierGUI = false

    private let arrows: [(String, UInt8)] = [
        ("UP", 0x52), ("DOWN", 0x51), ("LEFT", 0x50), ("RIGHT", 0x4F)
    ]

    private var activeModifiers: UInt8 {
        var bits: UInt8 = 0
        if modifierShift { bits |= HIDKeyboardMap.shift }
        if modifierControl { bits |= HIDKeyboardMap.control }
        if modifierAlt { bits |= HIDKeyboardMap.alt }
        if modifierGUI { bits |= HIDKeyboardMap.gui }
        return bits
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Bluetooth Classic HID keyboard")
                .font(.title2.weight(.semibold))
            Text("The Mac appears as a keyboard to the paired phone. Public IOBluetooth APIs only.")
                .font(.callout)
                .foregroundStyle(.secondary)

            GroupBox("Status") {
                VStack(alignment: .leading, spacing: 7) {
                    statusRow("HID SDP record published", peripheral.isSDPPublished)
                    statusRow("HID channels ready", peripheral.isReady)
                    Text("Connected peer: \(peripheral.connectedAddress ?? "none")")
                    Text("Negotiated protocol: \(peripheral.protocolMode == 0 ? "Boot" : "Report")")
                    Text("Input reports written: \(peripheral.reportsSent)")
                    if let error = peripheral.lastError {
                        Text(error).foregroundStyle(.red)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
            }

            HStack {
                Button("Publish HID service") { peripheral.publishService() }
                    .disabled(peripheral.isSDPPublished)
                Button("Stop / Disconnect", role: .destructive) { peripheral.stop() }
                Button("Refresh peers") { peripheral.refreshPairedDevices() }
            }

            GroupBox("Connect by address") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Classic HIDP has no mDNS discovery: type the peer's Bluetooth address, then connect. An unbonded peer is paired first.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    HStack {
                        TextField("5C-17-CF-B4-1B-B9", text: $peerAddress)
                            .font(.system(.body, design: .monospaced))
                            .frame(maxWidth: 240)
                        Button("Connect / Pair") { peripheral.connect(to: peerAddress) }
                            .disabled(peerAddress.trimmingCharacters(in: .whitespaces).isEmpty)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Pairing") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Confirm that the code below matches the code shown on the phone, then answer on both devices.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    if let state = peripheral.pairingState {
                        Text("State: \(state)").font(.callout)
                    }
                    if let code = peripheral.pairingCode {
                        Text(code)
                            .font(.system(size: 34, weight: .semibold, design: .monospaced))
                        HStack {
                            Button("Codes match — Accept") { peripheral.confirmPairing(true) }
                            Button("Decline") { peripheral.confirmPairing(false) }
                        }
                    } else {
                        Text("No pairing code pending.").foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Paired peers") {
                VStack(alignment: .leading, spacing: 8) {
                    Text("The Mac must connect outbound; macOS owns the inbound HID host role.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    if peripheral.pairedDevices.isEmpty {
                        Text("No paired peers found.")
                    }
                    ForEach(peripheral.pairedDevices) { peer in
                        HStack {
                            Text(peer.name)
                            Text(peer.id)
                                .font(.system(.caption, design: .monospaced))
                                .foregroundStyle(.secondary)
                            Spacer()
                            if peripheral.connectedAddress == peer.id {
                                Button("Disconnect") { peripheral.disconnect() }
                            } else {
                                Button("Connect") { peripheral.connect(to: peer.id) }
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Send one arrow press/release") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Confirm a DroidBridge receiver is focused on the phone before each arrow. Confirmation is used up by one click.")
                        .font(.callout)
                    Toggle("The P16 receiver is foreground and focused", isOn: $receiverConfirmed)
                    HStack {
                        ForEach(arrows, id: \.0) { arrow in
                            Button(arrow.0) {
                                peripheral.sendArrow(arrow.1)
                                receiverConfirmed = false
                            }
                            .disabled(!peripheral.isReady || !receiverConfirmed)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Send a key or modifier chord") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("One click sends one press/release. The confirmation is used up each time.")
                        .font(.callout)
                    Toggle("The DroidBridge receiver is foreground and focused", isOn: $receiverConfirmed)
                    HStack {
                        Toggle("Shift", isOn: $modifierShift)
                        Toggle("Ctrl", isOn: $modifierControl)
                        Toggle("Alt", isOn: $modifierAlt)
                        Toggle("Cmd", isOn: $modifierGUI)
                    }
                    HStack {
                        TextField("Usage (hex), e.g. 04 for A", text: $keyUsage)
                            .font(.system(.body, design: .monospaced))
                            .frame(maxWidth: 240)
                        Button("Send key") {
                            if let usage = UInt8(keyUsage.trimmingCharacters(in: .whitespaces), radix: 16) {
                                peripheral.sendKey(usage, modifiers: activeModifiers)
                            } else {
                                peripheral.reportInputError("Key usage must be a hex byte (01...65)")
                            }
                            receiverConfirmed = false
                        }
                        .disabled(!peripheral.isReady || !receiverConfirmed)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Type text") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Types US-layout ASCII into the focused receiver, one key per character. Non-ASCII is skipped and reported. Never use for passwords or sensitive fields.")
                        .font(.callout)
                    Toggle("Receiver focused — I confirm this text is safe to type", isOn: $textConfirmed)
                    HStack {
                        TextField("Text to type", text: $textToType)
                        Button("Type") {
                            peripheral.typeText(textToType)
                            textConfirmed = false
                        }
                        .disabled(!peripheral.isReady || !textConfirmed || textToType.isEmpty)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Activity") {
                VStack(alignment: .leading, spacing: 3) {
                    if peripheral.activity.isEmpty {
                        Text("No activity yet.").foregroundStyle(.secondary)
                    }
                    ForEach(Array(peripheral.activity.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(.caption, design: .monospaced))
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
            }

            GroupBox("Scope") {
                VStack(alignment: .leading, spacing: 5) {
                    Text("• Keyboard usages and US-layout ASCII text only; no pointer, touch-equivalent control or password entry.")
                    Text("• No Accessibility permission, event tap or Mac input capture.")
                    Text("• Send only to the purpose-built DroidBridge receiver, never to a sensitive app.")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Spacer(minLength: 0)
        }
        .padding(20)
        .task {
            await peripheral.warmUpIOBluetooth()
            peripheral.refreshPairedDevices()
            peripheral.runHarnessIfRequested()
        }
    }

    private func statusRow(_ label: String, _ value: Bool) -> some View {
        Text("\(label): \(value ? "yes" : "no")")
    }
}
