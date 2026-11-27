import SwiftUI

struct EmptyStateView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 24)

            VStack(spacing: 20) {
                ZStack {
                    Circle()
                        .fill(AppTheme.accent.opacity(0.18))
                        .frame(width: 104, height: 104)
                        .blur(radius: 18)
                    Image(systemName: "iphone.radiowaves.left.and.right")
                        .font(.system(size: 44, weight: .light))
                        .foregroundStyle(
                            LinearGradient(
                                colors: [.white, AppTheme.accent.opacity(0.9)],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                }

                VStack(spacing: 8) {
                    Text("No Phone Connected")
                        .font(.system(size: 24, weight: .semibold, design: .rounded))
                    Text("Connect an Android phone over Wi-Fi and Bluetooth to mirror its screen, control it with your Mac keyboard and mouse, and sync your clipboard.")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .lineSpacing(3)
                        .frame(maxWidth: 430)
                }

                Button {
                    model.beginPairing()
                } label: {
                    Label("Connect a Phone", systemImage: "plus")
                        .font(.system(size: 13.5, weight: .semibold))
                        .padding(.horizontal, 6)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .tint(AppTheme.accent)

                HStack(spacing: 8) {
                    miniPill("Wi-Fi TLS 1.3", symbol: "lock.shield")
                    miniPill("BT Classic HID", symbol: "keyboard")
                    miniPill("No ADB", symbol: "nosign")
                }
                .padding(.top, 4)
            }
            .padding(.horizontal, 42)
            .padding(.vertical, 38)
            .frame(maxWidth: 560)
            .glassEffect(.regular, in: .rect(cornerRadius: 30))
            .shadow(color: .black.opacity(0.22), radius: 40, y: 24)

            Spacer(minLength: 24)
            FooterBar()
        }
        .padding(.horizontal, 32)
        .padding(.top, 28)
        .navigationTitle("DroidBridge")
    }

    private func miniPill(_ text: String, symbol: String) -> some View {
        HStack(spacing: 5) {
            Image(systemName: symbol)
                .font(.system(size: 9.5, weight: .semibold))
            Text(text)
                .font(.system(size: 10.5, weight: .medium))
        }
        .foregroundStyle(.secondary)
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(.white.opacity(0.05), in: .capsule)
        .overlay {
            Capsule().strokeBorder(.white.opacity(0.08), lineWidth: 1)
        }
    }
}
