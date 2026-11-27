import SwiftUI

// MARK: - Palette

enum AppTheme {
    static let accent = Color(red: 0.29, green: 0.55, blue: 1.0)
    static let accentDeep = Color(red: 0.16, green: 0.33, blue: 0.86)
    static let green = Color(red: 0.22, green: 0.82, blue: 0.48)
    static let amber = Color(red: 1.0, green: 0.69, blue: 0.24)
    static let red = Color(red: 0.97, green: 0.37, blue: 0.40)
    static let violet = Color(red: 0.62, green: 0.45, blue: 1.0)
    static let teal = Color(red: 0.24, green: 0.78, blue: 0.86)
}

// MARK: - Ambient background

struct AmbientBackground: View {
    var body: some View {
        GeometryReader { proxy in
            let width = proxy.size.width
            let height = proxy.size.height

            ZStack {
                LinearGradient(
                    colors: [
                        Color(red: 0.055, green: 0.070, blue: 0.110),
                        Color(red: 0.035, green: 0.042, blue: 0.070),
                        Color(red: 0.020, green: 0.024, blue: 0.040)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )

                Circle()
                    .fill(AppTheme.accent.opacity(0.35))
                    .frame(width: max(width * 0.58, 280), height: max(width * 0.58, 280))
                    .blur(radius: 140)
                    .position(x: width * 0.10, y: height * 0.08)

                Circle()
                    .fill(AppTheme.violet.opacity(0.24))
                    .frame(width: max(width * 0.52, 260), height: max(width * 0.52, 260))
                    .blur(radius: 150)
                    .position(x: width * 0.90, y: height * 0.86)

                Circle()
                    .fill(AppTheme.teal.opacity(0.14))
                    .frame(width: max(width * 0.40, 200), height: max(width * 0.40, 200))
                    .blur(radius: 130)
                    .position(x: width * 0.82, y: height * 0.12)
            }
            .frame(width: width, height: height)
            .clipped()
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
    }
}

// MARK: - Glass surfaces

struct GlassCard<Content: View>: View {
    var padding: CGFloat = 18
    var cornerRadius: CGFloat = 22
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
    }
}

struct StatusPill: View {
    let label: String
    var color: Color = AppTheme.green
    var symbol: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            if let symbol {
                Image(systemName: symbol)
                    .font(.system(size: 10, weight: .semibold))
                    .foregroundStyle(color)
            } else {
                Circle()
                    .fill(color)
                    .frame(width: 7, height: 7)
                    .shadow(color: color.opacity(0.7), radius: 3)
            }
            Text(label)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.primary.opacity(0.9))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .glassEffect(.regular, in: .capsule)
    }
}

struct FooterBar: View {
    var leading: String = "DroidBridge v2.0.0"
    var trailing: String? = nil

    var body: some View {
        HStack(spacing: 8) {
            Text(leading)
            Spacer()
            if let trailing {
                Text(trailing)
            }
        }
        .font(.caption2)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 18)
        .padding(.vertical, 9)
        .background(.ultraThinMaterial)
        .overlay(alignment: .top) {
            Rectangle()
                .fill(.white.opacity(0.07))
                .frame(height: 1)
        }
    }
}

struct SectionLabel: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.system(size: 10, weight: .bold))
            .tracking(1.1)
            .foregroundStyle(.secondary)
    }
}

struct FeatureCard: View {
    let symbol: String
    let title: String
    let detail: String
    var tint: Color = AppTheme.accent

    var body: some View {
        GlassCard(padding: 16, cornerRadius: 20) {
            VStack(alignment: .leading, spacing: 9) {
                Image(systemName: symbol)
                    .font(.system(size: 18, weight: .semibold))
                    .foregroundStyle(tint)
                    .frame(width: 36, height: 36)
                    .background(tint.opacity(0.13), in: .rect(cornerRadius: 10))
                Text(title)
                    .font(.system(size: 13, weight: .semibold))
                Text(detail)
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

struct ToastView: View {
    let toast: ToastMessage

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: toast.symbol)
                .foregroundStyle(AppTheme.green)
            Text(toast.message)
                .font(.system(size: 12.5, weight: .medium))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .glassEffect(.regular, in: .capsule)
        .shadow(color: .black.opacity(0.25), radius: 18, y: 8)
    }
}

// MARK: - Buttons

struct DangerGlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(AppTheme.red)
            .padding(.horizontal, 15)
            .padding(.vertical, 9)
            .background(AppTheme.red.opacity(configuration.isPressed ? 0.22 : 0.12), in: .capsule)
            .overlay {
                Capsule().strokeBorder(AppTheme.red.opacity(0.28), lineWidth: 1)
            }
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
    }
}
