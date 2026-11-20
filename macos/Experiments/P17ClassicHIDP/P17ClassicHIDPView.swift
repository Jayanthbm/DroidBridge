// Copyright (C) 2026 DroidBridge contributors.
// SPDX-License-Identifier: none — internal feasibility experiment, not for distribution.
import SwiftUI

struct P17ClassicHIDPView: View {
    @ObservedObject var peripheral: ClassicHIDPeripheral
    @State private var receiverConfirmed = false

    private let arrows: [(String, UInt8)] = [
        ("UP", 0x52), ("DOWN", 0x51), ("LEFT", 0x50), ("RIGHT", 0x4F)
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("P17 — Bluetooth Classic HID device")
                .font(.title2.weight(.semibold))
            Text("Isolated HIDP experiment. Public IOBluetooth APIs only. Not production code.")
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
                    Text("Confirm the harmless DroidBridge receiver is focused on the phone before each arrow. Confirmation is used up by one click.")
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
                    Text("• Keyboard arrow usages only; no pointer, text entry or touch-equivalent control.")
                    Text("• No Accessibility permission, event tap or Mac input capture.")
                    Text("• Use only the purpose-built DroidBridge receiver and never a sensitive app.")
                    Text("• Not for distribution; feasibility evidence only.")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            Spacer(minLength: 0)
        }
        .padding(20)
        .onAppear { peripheral.runHarnessIfRequested() }
    }

    private func statusRow(_ label: String, _ value: Bool) -> some View {
        Text("\(label): \(value ? "yes" : "no")")
    }
}
