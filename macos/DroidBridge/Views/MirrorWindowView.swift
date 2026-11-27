import SwiftUI

struct MirrorWindowView: View {
    let deviceID: String

    @Environment(AppModel.self) private var model
    @Environment(\.dismissWindow) private var dismissWindow
    @Environment(\.controlActiveState) private var controlActiveState

    private var device: PhoneDevice? { model.device(withID: deviceID) }
    private var isFocused: Bool { controlActiveState == .key && model.focusedDeviceID == deviceID }

    var body: some View {
        ZStack {
            AmbientBackground()

            if let device {
                VStack(spacing: 0) {
                    toolbar(device)
                    stage(device)
                    navigationBar
                    FooterBar(trailing: "Mock video · Phase 1")
                }
            } else {
                ContentUnavailableView(
                    "Mirroring Ended",
                    systemImage: "iphone.slash",
                    description: Text("This phone is no longer connected.")
                )
            }
        }
        .frame(minWidth: 360, minHeight: 600)
        .navigationTitle(device?.name ?? "Phone Mirror")
        .navigationSubtitle(device?.modelName ?? "")
        .onAppear {
            model.focus(deviceID: deviceID)
        }
        .onChange(of: controlActiveState) { _, newValue in
            if newValue == .key {
                model.focus(deviceID: deviceID)
            }
        }
        .onDisappear {
            model.stopMirroring(deviceID: deviceID)
        }
    }

    // MARK: Toolbar

    private func toolbar(_ device: PhoneDevice) -> some View {
        HStack(spacing: 8) {
            HStack(spacing: 6) {
                Image(systemName: isFocused ? "keyboard.fill" : "keyboard")
                    .font(.system(size: 10, weight: .semibold))
                Text(isFocused ? "Input: Active" : "Click to focus")
                    .font(.system(size: 10.5, weight: .semibold))
            }
            .foregroundStyle(isFocused ? AppTheme.green : .secondary)
            .padding(.horizontal, 10)
            .padding(.vertical, 5)
            .glassEffect(.regular, in: .capsule)

            Spacer(minLength: 4)

            Button {
                model.setAudio(deviceID: deviceID, enabled: !device.settings.audioEnabled)
            } label: {
                Image(systemName: device.settings.audioEnabled ? "speaker.wave.2.fill" : "speaker.slash.fill")
                    .font(.system(size: 11, weight: .semibold))
                    .frame(width: 26, height: 22)
            }
            .buttonStyle(.glass)
            .help(device.settings.audioEnabled ? "Mute phone audio" : "Unmute phone audio")

            Button {
                model.syncClipboard(deviceID: deviceID)
            } label: {
                Image(systemName: "doc.on.clipboard")
                    .font(.system(size: 11, weight: .semibold))
                    .frame(width: 26, height: 22)
            }
            .buttonStyle(.glass)
            .help("Sync clipboard")

            Button {
                model.stopMirroring(deviceID: deviceID)
                dismissWindow(id: "mirror", value: deviceID)
            } label: {
                Label("Stop", systemImage: "stop.fill")
                    .font(.system(size: 10.5, weight: .semibold))
            }
            .buttonStyle(DangerGlassButtonStyle())
            .help("Stop mirroring")
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial)
        .overlay(alignment: .bottom) {
            Rectangle()
                .fill(.white.opacity(0.07))
                .frame(height: 1)
        }
    }

    // MARK: Stage

    private func stage(_ device: PhoneDevice) -> some View {
        VStack(spacing: 14) {
            PhoneBezelView(device: device, focused: isFocused)
                .aspectRatio(9.0 / 19.5, contentMode: .fit)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .padding(.horizontal, 24)
                .padding(.top, 14)

            Text(isFocused ? "Keyboard and pointer input is routed to this phone." : "Click the window to route Bluetooth input here.")
                .font(.system(size: 10))
                .foregroundStyle(.tertiary)
                .padding(.bottom, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Bottom navigation

    private var navigationBar: some View {
        HStack(spacing: 6) {
            navButton(title: "Back", symbol: "chevron.backward", key: "Back")
            navButton(title: "Home", symbol: "circle", key: "Home")
            navButton(title: "Recents", symbol: "square", key: "Recents")
        }
        .padding(6)
        .glassEffect(.regular, in: .capsule)
        .padding(.bottom, 12)
    }

    private func navButton(title: String, symbol: String, key: String) -> some View {
        Button {
            model.showToast("\(key) sent over Bluetooth HID", symbol: "keyboard")
        } label: {
            Label(title, systemImage: symbol)
                .font(.system(size: 11, weight: .medium))
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
        .help("Send \(key) to the phone")
    }
}

// MARK: - Phone bezel

struct PhoneBezelView: View {
    let device: PhoneDevice
    let focused: Bool

    private struct MockApp: Identifiable {
        let id: String
        let symbol: String
        let name: String
        let tint: Color
    }

    private let apps: [MockApp] = [
        MockApp(id: "phone", symbol: "phone.fill", name: "Phone", tint: .green),
        MockApp(id: "messages", symbol: "message.fill", name: "Messages", tint: .green),
        MockApp(id: "camera", symbol: "camera.fill", name: "Camera", tint: .gray),
        MockApp(id: "maps", symbol: "map.fill", name: "Maps", tint: .blue),
        MockApp(id: "browser", symbol: "safari.fill", name: "Browser", tint: .cyan),
        MockApp(id: "music", symbol: "music.note", name: "Music", tint: .pink),
        MockApp(id: "photos", symbol: "photo.fill", name: "Photos", tint: .orange),
        MockApp(id: "settings", symbol: "gearshape.fill", name: "Settings", tint: .gray)
    ]

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 42, style: .continuous)
                .fill(
                    LinearGradient(
                        colors: [
                            Color(red: 0.16, green: 0.17, blue: 0.21),
                            Color(red: 0.07, green: 0.08, blue: 0.10)
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                )
                .overlay {
                    RoundedRectangle(cornerRadius: 42, style: .continuous)
                        .strokeBorder(.white.opacity(0.14), lineWidth: 1)
                }
                .shadow(color: .black.opacity(0.55), radius: 34, y: 18)

            screen
                .padding(7)
                .clipShape(RoundedRectangle(cornerRadius: 36, style: .continuous))

            if !focused {
                RoundedRectangle(cornerRadius: 36, style: .continuous)
                    .fill(.black.opacity(0.22))
                    .padding(7)
                    .overlay {
                        Text("Click to focus")
                            .font(.system(size: 11, weight: .semibold))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .glassEffect(.regular, in: .capsule)
                    }
            }
        }
    }

    private var screen: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            let scale = max(width / 300, 0.75)

            ZStack(alignment: .top) {
                LinearGradient(
                    colors: [
                        Color(red: 0.10, green: 0.24, blue: 0.62),
                        Color(red: 0.24, green: 0.10, blue: 0.48),
                        Color(red: 0.06, green: 0.08, blue: 0.16)
                    ],
                    startPoint: .topLeading,
                    endPoint: .bottomTrailing
                )

                Circle()
                    .fill(Color(red: 0.36, green: 0.65, blue: 1.0).opacity(0.45))
                    .frame(width: 220 * scale, height: 220 * scale)
                    .blur(radius: 55)
                    .offset(x: -70 * scale, y: 30 * scale)

                Circle()
                    .fill(Color(red: 0.85, green: 0.32, blue: 0.82).opacity(0.32))
                    .frame(width: 210 * scale, height: 210 * scale)
                    .blur(radius: 65)
                    .offset(x: 100 * scale, y: 210 * scale)

                VStack(spacing: 0) {
                    statusBar(scale: scale)
                    Spacer(minLength: 8 * scale)
                    clock(scale: scale)
                    Spacer(minLength: 12 * scale)
                    searchBar(scale: scale)
                    Spacer(minLength: 14 * scale)
                    appGrid(scale: scale)
                    Spacer(minLength: 10 * scale)
                    dock(scale: scale)
                        .padding(.bottom, 10 * scale)
                }
                .padding(.horizontal, 14 * scale)

                // Bluetooth HID pointer
                Image(systemName: "cursorarrow")
                    .font(.system(size: 18 * scale, weight: .medium))
                    .foregroundStyle(.white)
                    .shadow(color: .black.opacity(0.7), radius: 2, x: 1, y: 2)
                    .offset(x: 55 * scale, y: 190 * scale)
            }
            .overlay(alignment: .top) {
                Capsule()
                    .fill(.black.opacity(0.85))
                    .frame(width: 28 * scale, height: 7 * scale)
                    .padding(.top, 7 * scale)
            }
        }
    }

    private func statusBar(scale: CGFloat) -> some View {
        HStack {
            Text("9:41")
                .font(.system(size: 10.5 * scale, weight: .semibold))
            Spacer()
            HStack(spacing: 4 * scale) {
                Image(systemName: "cellularbars")
                Image(systemName: "wifi")
                Image(systemName: "battery.75percent")
                Text("\(device.batteryLevel)%")
            }
            .font(.system(size: 9.5 * scale, weight: .medium))
        }
        .foregroundStyle(.white)
        .padding(.top, 17 * scale)
    }

    private func clock(scale: CGFloat) -> some View {
        VStack(spacing: 2 * scale) {
            Text("9:41")
                .font(.system(size: 54 * scale, weight: .light, design: .rounded))
                .foregroundStyle(.white)
            Text("Friday, November 27")
                .font(.system(size: 11 * scale, weight: .medium))
                .foregroundStyle(.white.opacity(0.8))
        }
    }

    private func searchBar(scale: CGFloat) -> some View {
        HStack(spacing: 6 * scale) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 10 * scale, weight: .semibold))
            Text("Search")
                .font(.system(size: 10 * scale, weight: .medium))
            Spacer()
        }
        .foregroundStyle(.white.opacity(0.75))
        .padding(.horizontal, 10 * scale)
        .padding(.vertical, 8 * scale)
        .background(.white.opacity(0.14), in: .capsule)
    }

    private func appGrid(scale: CGFloat) -> some View {
        LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), spacing: 9 * scale), count: 4),
            spacing: 11 * scale
        ) {
            ForEach(apps) { app in
                VStack(spacing: 5 * scale) {
                    RoundedRectangle(cornerRadius: 11 * scale, style: .continuous)
                        .fill(app.tint.opacity(0.82))
                        .overlay {
                            Image(systemName: app.symbol)
                                .font(.system(size: 17 * scale, weight: .semibold))
                                .foregroundStyle(.white)
                        }
                        .frame(height: 42 * scale)
                        .overlay {
                            RoundedRectangle(cornerRadius: 11 * scale, style: .continuous)
                                .strokeBorder(.white.opacity(0.16), lineWidth: 0.7)
                        }
                    Text(app.name)
                        .font(.system(size: 7.5 * scale, weight: .medium))
                        .foregroundStyle(.white.opacity(0.9))
                        .lineLimit(1)
                }
            }
        }
    }

    private func dock(scale: CGFloat) -> some View {
        HStack(spacing: 13 * scale) {
            ForEach(["phone.fill", "message.fill", "safari.fill", "music.note"], id: \.self) { symbol in
                Image(systemName: symbol)
                    .font(.system(size: 15 * scale, weight: .semibold))
                    .foregroundStyle(.white)
                    .frame(width: 35 * scale, height: 35 * scale)
                    .background(.white.opacity(0.15), in: .rect(cornerRadius: 10 * scale))
            }
        }
        .padding(7 * scale)
        .background(.white.opacity(0.10), in: .rect(cornerRadius: 17 * scale))
    }
}
