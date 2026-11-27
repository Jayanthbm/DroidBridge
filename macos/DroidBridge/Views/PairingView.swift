import SwiftUI

struct PairingView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openURL) private var openURL

    private let bluetoothSettingsURL = URL(string: "x-apple.systempreferences:com.apple.BluetoothSettings")!

    var body: some View {
        ZStack {
            AmbientBackground()

            if let pairing = model.pairing {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        header(pairing)

                        GlassCard(padding: 20, cornerRadius: 22) {
                            VStack(alignment: .leading, spacing: 14) {
                                stepHeading(
                                    number: 1,
                                    title: "Verify Wi-Fi Pairing Code",
                                    subtitle: "Confirm this 6-digit code matches the DroidBridge app on your phone."
                                )

                                HStack(spacing: 9) {
                                    ForEach(Array(digits(of: pairing.code).enumerated()), id: \.offset) { _, digit in
                                        Text(String(digit))
                                            .font(.system(size: 26, weight: .bold, design: .rounded))
                                            .monospacedDigit()
                                            .frame(maxWidth: .infinity)
                                            .frame(height: 54)
                                            .background(.black.opacity(0.28), in: .rect(cornerRadius: 12))
                                            .overlay {
                                                RoundedRectangle(cornerRadius: 12)
                                                    .strokeBorder(.white.opacity(0.10), lineWidth: 1)
                                            }
                                    }
                                }

                                HStack(spacing: 6) {
                                    Image(systemName: "lock.shield.fill")
                                        .font(.system(size: 10))
                                        .foregroundStyle(AppTheme.green)
                                    Text("The same code is shown on your phone. Comparing it prevents man-in-the-middle attacks.")
                                        .font(.system(size: 10.5))
                                        .foregroundStyle(.secondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                        }

                        GlassCard(padding: 20, cornerRadius: 22) {
                            VStack(alignment: .leading, spacing: 14) {
                                stepHeading(
                                    number: 2,
                                    title: "One-Time Bluetooth HID Bond",
                                    subtitle: "This lets your Mac keyboard and mouse control the phone without Accessibility or ADB."
                                )

                                VStack(alignment: .leading, spacing: 10) {
                                    instruction(1, "Open Bluetooth Settings on this Mac.")
                                    instruction(2, "Make your Android phone discoverable in its Bluetooth settings.")
                                    instruction(3, "Select your phone in macOS Bluetooth Settings, then click Connect.")
                                    instruction(4, "Confirm the pairing prompt on both devices. It reconnects automatically after this.")
                                }

                                Button {
                                    openURL(bluetoothSettingsURL)
                                } label: {
                                    Label("Open Bluetooth Settings", systemImage: "arrow.up.forward.app")
                                        .font(.system(size: 12.5, weight: .semibold))
                                }
                                .buttonStyle(.glass)
                                .controlSize(.regular)
                            }
                        }

                        HStack(spacing: 10) {
                            Spacer()
                            Button("Cancel") {
                                model.cancelPairing()
                            }
                            .buttonStyle(.glass)
                            .controlSize(.large)
                            .keyboardShortcut(.cancelAction)

                            Button {
                                model.confirmPairing()
                            } label: {
                                Label("Confirm & Connect", systemImage: "checkmark")
                                    .font(.system(size: 13, weight: .semibold))
                            }
                            .buttonStyle(.glassProminent)
                            .controlSize(.large)
                            .tint(AppTheme.accent)
                            .keyboardShortcut(.defaultAction)
                        }
                        .padding(.top, 2)

                        Text("Pairing is mocked in Phase 1. TLS session establishment and trust storage arrive in Phase 2.")
                            .font(.system(size: 10))
                            .foregroundStyle(.tertiary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                    .padding(26)
                }
            } else {
                ProgressView()
                    .controlSize(.large)
            }
        }
        .frame(width: 560, height: 690)
        .tint(AppTheme.accent)
    }

    private func header(_ pairing: PairingSession) -> some View {
        HStack(spacing: 14) {
            ZStack(alignment: .bottomTrailing) {
                Image(systemName: "iphone.gen3")
                    .font(.system(size: 24, weight: .medium))
                    .foregroundStyle(AppTheme.accent)
                    .frame(width: 50, height: 50)
                    .glassEffect(.regular, in: .rect(cornerRadius: 15))

                Image(systemName: "plus.circle.fill")
                    .font(.system(size: 15, weight: .bold))
                    .foregroundStyle(.white, AppTheme.accent)
                    .offset(x: 3, y: 3)
            }

            VStack(alignment: .leading, spacing: 3) {
                Text("Pair New Phone")
                    .font(.system(size: 21, weight: .semibold, design: .rounded))
                Text("Connecting to \(pairing.deviceName) · 2 steps")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
    }

    private func stepHeading(number: Int, title: String, subtitle: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(number)")
                .font(.system(size: 11, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .frame(width: 21, height: 21)
                .background(AppTheme.accent, in: .circle)

            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.system(size: 14.5, weight: .semibold))
                Text(subtitle)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func instruction(_ number: Int, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Text("\(number).")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(AppTheme.accent)
                .frame(width: 16, alignment: .trailing)
            Text(text)
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private func digits(of code: String) -> [Character] {
        Array(code.replacingOccurrences(of: " ", with: ""))
    }
}
