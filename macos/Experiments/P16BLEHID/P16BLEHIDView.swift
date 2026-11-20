import SwiftUI

struct P16BLEHIDView: View {
    @ObservedObject var peripheral: P16HIDPeripheral

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("P16 — BLE HID keyboard")
                .font(.title2.weight(.semibold))
            Text("Isolated feasibility test • no production app changes")
                .font(.callout)
                .foregroundStyle(.secondary)

            GroupBox("Status") {
                VStack(alignment: .leading, spacing: 8) {
                    Label(peripheral.state, systemImage: peripheral.advertising ? "dot.radiowaves.left.and.right" : "stop.circle")
                    Text("Bluetooth service started: \(peripheral.started ? "yes" : "no")")
                    Text("HID service published: \(peripheral.servicePublished ? "yes" : "no")")
                    Text("Advertising: \(peripheral.advertising ? "yes" : "no")")
                    Text("Android subscribed to input reports: \(peripheral.subscribed ? "yes" : "no")")
                    Text("Synthetic reports sent: \(peripheral.reportCount)")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
            }

            HStack {
                Button("Start Bluetooth") { peripheral.start() }
                    .disabled(peripheral.started)
                Button("Start Advertising") { peripheral.startAdvertising() }
                    .disabled(!peripheral.servicePublished || peripheral.advertising)
                Button("Stop / Disconnect") { peripheral.stop() }
                    .tint(.red)
            }

            GroupBox("Send one synthetic arrow key") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("Use only after the Android P16 receiver is open and paired. Each action sends one key-down followed by key-up.")
                        .font(.callout)
                    HStack {
                        ForEach(ArrowKey.allCases) { key in
                            Button(key.label) { peripheral.sendArrow(key) }
                                .disabled(!peripheral.advertising || !peripheral.subscribed)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }

            GroupBox("Safety / scope") {
                VStack(alignment: .leading, spacing: 6) {
                    Text("• BLE HID is started only by your explicit button action.")
                    Text("• No global Mac keyboard/mouse capture or Accessibility permission.")
                    Text("• This test does not send text, pointer, touch, screen, Wi-Fi, or clipboard data.")
                    Text("• Use only the harmless P16 receiver screen. Stop advertising before ending the test.")
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .onDisappear { peripheral.stop() }
    }
}
