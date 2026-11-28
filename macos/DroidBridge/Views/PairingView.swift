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

                                codeBoxes(pairing.code)

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
                                    instruction(1, "Open Settings → Bluetooth on your Android phone.")
                                    instruction(2, "Under Available devices, tap \(model.macBluetoothName).")
                                    instruction(3, "Confirm the pairing code on the phone and on this Mac.")
                                    instruction(4, "Keep this window open. Keyboard and mouse control connects automatically.")
                                }

                                HStack(spacing: 7) {
                                    StatusPill(
                                        label: bluetoothStatusLabel,
                                        color: model.bluetoothPairingStatus == .connected ? AppTheme.green : AppTheme.amber
                                    )
                                    Text(model.bluetoothPairingStatus == .connected
                                         ? "Your Mac is bonded as a Bluetooth keyboard and mouse."
                                         : "This Mac is discoverable while this sheet is open.")
                                        .font(.system(size: 10.5))
                                        .foregroundStyle(.secondary)
                                }

                                Button {
                                    openURL(bluetoothSettingsURL)
                                } label: {
                                    Label("Open Mac Bluetooth Settings", systemImage: "arrow.up.forward.app")
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
                                Label(
                                    model.isConfirmingPairing ? "Waiting for Phone…" : "Confirm & Connect",
                                    systemImage: model.isConfirmingPairing ? "hourglass" : "checkmark"
                                )
                                .font(.system(size: 13, weight: .semibold))
                            }
                            .buttonStyle(.glassProminent)
                            .controlSize(.large)
                            .tint(AppTheme.accent)
                            .keyboardShortcut(.defaultAction)
                            .disabled(model.isConfirmingPairing)
                        }
                        .padding(.top, 2)

                        Text("Protected by TLS 1.3 and certificate pinning. Compare the code on both screens before connecting.")
                            .font(.system(size: 10))
                            .foregroundStyle(.tertiary)
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                    .padding(26)
                }
            } else if model.isWaitingForPhone {
                waitingContent
            } else {
                ProgressView()
                    .controlSize(.large)
            }
        }
        .frame(width: 560, height: 690)
        .tint(AppTheme.accent)
    }

    private var waitingContent: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                HStack(spacing: 14) {
                    Image(systemName: "iphone.gen3")
                        .font(.system(size: 24, weight: .medium))
                        .foregroundStyle(AppTheme.accent)
                        .frame(width: 50, height: 50)
                        .glassEffect(.regular, in: .rect(cornerRadius: 15))

                    VStack(alignment: .leading, spacing: 3) {
                        Text("Pair New Phone")
                            .font(.system(size: 21, weight: .semibold, design: .rounded))
                        Text("Waiting for a phone on your Wi-Fi")
                            .font(.system(size: 11.5))
                            .foregroundStyle(.secondary)
                    }

                    Spacer()
                }

                GlassCard(padding: 20, cornerRadius: 22) {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack(spacing: 10) {
                            ProgressView()
                                .controlSize(.small)
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Looking for DroidBridge")
                                    .font(.system(size: 13.5, weight: .semibold))
                                Text(model.isNetworkReady
                                     ? "Open DroidBridge on your Android phone and choose Pair with Mac."
                                     : "Starting the secure local-network service…")
                                    .font(.system(size: 11))
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }

                        codeBoxes("------")

                        HStack(spacing: 6) {
                            Image(systemName: "lock.shield.fill")
                                .font(.system(size: 10))
                                .foregroundStyle(AppTheme.green)
                            Text("The code appears here once the phone connects. It is derived from both TLS certificates.")
                                .font(.system(size: 10.5))
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }

                if let error = model.networkError {
                    Text(error)
                        .font(.system(size: 11))
                        .foregroundStyle(AppTheme.red)
                        .padding(.horizontal, 4)
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

                HStack {
                    Spacer()
                    Button("Cancel") {
                        model.cancelPairing()
                    }
                    .buttonStyle(.glass)
                    .controlSize(.large)
                    .keyboardShortcut(.cancelAction)
                }

                Text("No Accessibility Service and no ADB are used. The phone must be on the same Wi-Fi network as this Mac.")
                    .font(.system(size: 10))
                    .foregroundStyle(.tertiary)
                    .frame(maxWidth: .infinity, alignment: .center)
            }
            .padding(26)
        }
    }

    private func codeBoxes(_ code: String) -> some View {
        let characters = Array(code.replacingOccurrences(of: " ", with: ""))
        return HStack(spacing: 9) {
            ForEach(0..<6, id: \.self) { index in
                Text(index < characters.count ? String(characters[index]) : "–")
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
                Text(pairingSubtitle(pairing))
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
            }

            Spacer()
        }
    }

    private var bluetoothStatusLabel: String {
        switch model.bluetoothPairingStatus {
        case .connected: "Bluetooth HID bonded"
        case .reconnecting: "Waiting for phone"
        case .disconnected: "Bluetooth unavailable"
        case .idle: "Not started"
        }
    }

    private func pairingSubtitle(_ pairing: PairingSession) -> String {
        let details = [pairing.phoneModel, pairing.androidVersion]
            .filter { !$0.isEmpty }
            .joined(separator: " · ")
        return details.isEmpty ? "\(pairing.deviceName) · 2 steps" : "\(details) · 2 steps"
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
}
