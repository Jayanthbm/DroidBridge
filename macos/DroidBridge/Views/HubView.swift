import SwiftUI

// MARK: - Hub

struct HubView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        @Bindable var model = model

        VStack(spacing: 0) {
            NavigationSplitView {
                sidebar(selection: $model.selectedDeviceID)
                    .navigationSplitViewColumnWidth(min: 232, ideal: 268, max: 320)
            } detail: {
                if let device = model.selectedDevice {
                    DeviceDetailView(deviceID: device.id)
                } else {
                    ContentUnavailableView(
                        "Select a Phone",
                        systemImage: "iphone.gen3",
                        description: Text("Choose a paired phone in the sidebar to see its session controls.")
                    )
                }
            }
            .navigationSplitViewStyle(.balanced)
            .navigationTitle("DroidBridge")

            FooterBar(trailing: model.devices.count == 1 ? "1 phone paired" : "\(model.devices.count) phones paired")
        }
        .background(AmbientBackground())
        .tint(AppTheme.accent)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    model.beginPairing()
                } label: {
                    Label("Pair New Phone", systemImage: "plus")
                }
                .help("Pair another Android phone")
            }

            #if DEBUG
            ToolbarItem(placement: .automatic) {
                Menu {
                    if let device = model.selectedDevice {
                        Button("Simulate Wi-Fi Drop", systemImage: "wifi.exclamationmark") {
                            model.simulateWiFiDrop(deviceID: device.id)
                        }
                        Button("Simulate Bluetooth Drop", systemImage: "keyboard") {
                            model.simulateBluetoothDrop(deviceID: device.id)
                        }
                        Button("Reconnect Now", systemImage: "arrow.clockwise") {
                            model.reconnect(deviceID: device.id)
                        }
                    }
                    Divider()
                    Button("Reset Demo Data", systemImage: "arrow.counterclockwise", role: .destructive) {
                        model.resetDemo()
                    }
                } label: {
                    Label("Demo Controls", systemImage: "ladybug")
                }
                .help("Phase 1 mock-state controls")
            }
            #endif
        }
    }

    private func sidebar(selection: Binding<String?>) -> some View {
        VStack(spacing: 0) {
            List(selection: selection) {
                Section {
                    ForEach(model.devices) { device in
                        DeviceSidebarRow(device: device)
                            .tag(device.id)
                            .contextMenu {
                                Button("Simulate Wi-Fi Drop", systemImage: "wifi.exclamationmark") {
                                    model.simulateWiFiDrop(deviceID: device.id)
                                }
                                Button("Simulate Bluetooth Drop", systemImage: "keyboard") {
                                    model.simulateBluetoothDrop(deviceID: device.id)
                                }
                                Button("Reconnect Now", systemImage: "arrow.clockwise") {
                                    model.reconnect(deviceID: device.id)
                                }
                                Divider()
                                Button("Disconnect", systemImage: "xmark.circle", role: .destructive) {
                                    model.disconnect(deviceID: device.id)
                                }
                            }
                    }
                } header: {
                    SectionLabel(text: "Paired Phones")
                        .padding(.leading, 4)
                }
            }
            .listStyle(.sidebar)
            .scrollContentBackground(.hidden)

            VStack(spacing: 0) {
                Rectangle()
                    .fill(.white.opacity(0.07))
                    .frame(height: 1)
                Button {
                    model.beginPairing()
                } label: {
                    Label("Pair New Phone", systemImage: "plus")
                        .font(.system(size: 12.5, weight: .semibold))
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .controlSize(.large)
                .tint(AppTheme.accent)
                .padding(12)
            }
        }
    }
}

// MARK: - Sidebar row

struct DeviceSidebarRow: View {
    let device: PhoneDevice

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                RoundedRectangle(cornerRadius: 9, style: .continuous)
                    .fill(.white.opacity(0.07))
                Image(systemName: "iphone.gen3")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundStyle(device.wiFi.isConnected ? AppTheme.accent : .secondary)
            }
            .frame(width: 36, height: 36)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Text(device.name)
                        .font(.system(size: 12.5, weight: .semibold))
                        .lineLimit(1)
                    if device.needsAttention {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 9))
                            .foregroundStyle(AppTheme.amber)
                    }
                }
                Text(device.connectionSummary)
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer(minLength: 4)

            Circle()
                .fill(statusColor)
                .frame(width: 7, height: 7)
                .shadow(color: statusColor.opacity(0.7), radius: 3)
        }
        .padding(.vertical, 4)
    }

    private var statusColor: Color {
        if device.wiFi == .connected && device.bluetooth == .connected { return AppTheme.green }
        if device.wiFi == .reconnecting || device.bluetooth == .reconnecting { return AppTheme.amber }
        if device.wiFi == .connected { return AppTheme.accent }
        return .secondary
    }
}

// MARK: - Detail

struct DeviceDetailView: View {
    let deviceID: String

    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @State private var showSettings = ProcessInfo.processInfo.arguments.contains("--demo-settings")

    var body: some View {
        ZStack {
            AmbientBackground()

            if let device = model.device(withID: deviceID) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 18) {
                        if device.needsAttention {
                            ReconnectBanner(device: device) {
                                withAnimation(.spring(response: 0.35, dampingFraction: 0.85)) {
                                    model.reconnect(deviceID: device.id)
                                }
                            }
                            .transition(.move(edge: .top).combined(with: .opacity))
                        }

                        header(device)
                        actions(device)

                        if showSettings {
                            DeviceSettingsPanel(
                                device: device,
                                onQualityChange: { model.setStreamQuality(deviceID: device.id, quality: $0) },
                                onAudioChange: { model.setAudio(deviceID: device.id, enabled: $0) },
                                onAutoSyncChange: { model.setAutoSync(deviceID: device.id, enabled: $0) },
                                onAutoReconnectChange: { model.setAutoReconnect(deviceID: device.id, enabled: $0) },
                                onDone: {
                                    withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                                        showSettings = false
                                    }
                                }
                            )
                            .transition(.opacity.combined(with: .move(edge: .top)))
                        }

                        featureGrid
                    }
                    .padding(26)
                }
                .scrollIndicators(.hidden)
            } else {
                ContentUnavailableView("Phone Disconnected", systemImage: "iphone.slash")
            }
        }
        .navigationTitle(model.device(withID: deviceID)?.name ?? "DroidBridge")
        .navigationSubtitle(model.device(withID: deviceID)?.modelName ?? "")
        .animation(.spring(response: 0.35, dampingFraction: 0.85), value: model.device(withID: deviceID)?.needsAttention)
    }

    private func header(_ device: PhoneDevice) -> some View {
        HStack(alignment: .top, spacing: 20) {
            VStack(alignment: .leading, spacing: 6) {
                Text(device.name)
                    .font(.system(size: 24, weight: .semibold, design: .rounded))
                Text("\(device.modelName) · \(device.androidVersion) · \(device.batteryLevel)% battery")
                    .font(.system(size: 11.5))
                    .foregroundStyle(.secondary)
                Text("Ready for mirroring, keyboard and mouse control, and clipboard sync.")
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .padding(.top, 2)
            }

            Spacer(minLength: 8)

            VStack(alignment: .trailing, spacing: 8) {
                HStack(spacing: 7) {
                    StatusPill(
                        label: device.wiFi == .connected ? "Wi-Fi TLS" : device.wiFi.label,
                        color: device.wiFi == .connected ? AppTheme.green : AppTheme.amber
                    )
                    StatusPill(
                        label: device.bluetooth == .connected ? "BT Classic HID" : device.bluetooth.label,
                        color: device.bluetooth == .connected ? AppTheme.green : AppTheme.amber
                    )
                }
                Text(device.bluetoothAddress.map { "HID \($0) · pinned TLS 1.3" } ?? "Pinned TLS 1.3 · secure session")
                    .font(.system(size: 9.5))
                    .foregroundStyle(.tertiary)
            }
        }
    }

    private func actions(_ device: PhoneDevice) -> some View {
        HStack(spacing: 9) {
            Button {
                model.startMirroring(deviceID: device.id)
                openWindow(id: "mirror", value: device.id)
            } label: {
                Label("Start Mirroring", systemImage: "play.fill")
                    .font(.system(size: 12.5, weight: .semibold))
            }
            .buttonStyle(.glassProminent)
            .controlSize(.large)
            .tint(AppTheme.accent)

            Button {
                model.syncClipboard(deviceID: device.id)
            } label: {
                Label("Sync Clipboard", systemImage: "doc.on.clipboard")
                    .font(.system(size: 12.5, weight: .medium))
            }
            .buttonStyle(.glass)
            .controlSize(.large)

            if device.bluetooth != .connected {
                Button {
                    model.beginBluetoothSetup(deviceID: device.id)
                } label: {
                    Label("Bluetooth Setup", systemImage: "keyboard.badge.ellipsis")
                        .font(.system(size: 12.5, weight: .medium))
                }
                .buttonStyle(.glass)
                .controlSize(.large)
                .tint(AppTheme.violet)
                .help("Pair this Mac as a Bluetooth keyboard and mouse")
            }

            Button {
                withAnimation(.spring(response: 0.32, dampingFraction: 0.86)) {
                    showSettings.toggle()
                }
            } label: {
                Label("Settings", systemImage: "gearshape")
                    .font(.system(size: 12.5, weight: .medium))
            }
            .buttonStyle(.glass)
            .controlSize(.large)
            .tint(showSettings ? AppTheme.accent : .primary)

            Spacer(minLength: 4)

            Button {
                model.disconnect(deviceID: device.id)
            } label: {
                Label("Disconnect", systemImage: "xmark")
                    .labelStyle(.titleAndIcon)
            }
            .buttonStyle(DangerGlassButtonStyle())
            .controlSize(.large)
        }
    }

    private var featureGrid: some View {
        LazyVGrid(
            columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)],
            spacing: 12
        ) {
            FeatureCard(
                symbol: "play.rectangle",
                title: "Screen Mirroring (Wi-Fi)",
                detail: "H.264 over the encrypted session with automatic rotation and FLAG_SECURE privacy blackout.",
                tint: AppTheme.accent
            )
            FeatureCard(
                symbol: "keyboard",
                title: "Keyboard & Mouse (Bluetooth)",
                detail: "Classic HID keyboard and pointer. No Accessibility Service and no ADB at runtime.",
                tint: AppTheme.violet
            )
            FeatureCard(
                symbol: "doc.on.clipboard",
                title: "Clipboard Sync",
                detail: "Bidirectional text sync over Wi-Fi, with a foreground-tap fallback when Android is backgrounded.",
                tint: AppTheme.teal
            )
            FeatureCard(
                symbol: "square.stack.3d.up",
                title: "Multi-Device Ready",
                detail: "Connect several phones at once. Bluetooth HID input follows the focused mirroring window.",
                tint: AppTheme.green
            )
        }
    }
}

// MARK: - Reconnect banner

struct ReconnectBanner: View {
    let device: PhoneDevice
    let onReconnect: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "wifi.exclamationmark")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(AppTheme.amber)
                .frame(width: 36, height: 36)
                .background(AppTheme.amber.opacity(0.12), in: .rect(cornerRadius: 10))

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12.5, weight: .semibold))
                Text(subtitle)
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
            }

            Spacer()

            Button("Reconnect Now", systemImage: "arrow.clockwise", action: onReconnect)
                .buttonStyle(.glass)
                .controlSize(.small)
        }
        .padding(13)
        .glassEffect(.regular, in: .rect(cornerRadius: 16))
        .overlay {
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(AppTheme.amber.opacity(0.35), lineWidth: 1)
        }
    }

    private var title: String {
        if device.wiFi == .reconnecting { return "Wi-Fi connection lost" }
        if device.wiFi == .disconnected { return "Phone offline" }
        if device.bluetooth == .reconnecting || device.bluetooth == .disconnected {
            return "Bluetooth HID disconnected"
        }
        return "Connection issue"
    }

    private var subtitle: String {
        if device.wiFi == .reconnecting {
            return "Reconnecting to \(device.name) over pinned TLS 1.3…"
        }
        if device.wiFi == .disconnected {
            return "Bring the phone back onto the same Wi-Fi network."
        }
        return "Keyboard and mouse input is paused until the HID bond reconnects."
    }
}

// MARK: - Inline settings

struct DeviceSettingsPanel: View {
    let device: PhoneDevice
    let onQualityChange: @MainActor @Sendable (StreamQuality) -> Void
    let onAudioChange: @MainActor @Sendable (Bool) -> Void
    let onAutoSyncChange: @MainActor @Sendable (Bool) -> Void
    let onAutoReconnectChange: @MainActor @Sendable (Bool) -> Void
    let onDone: @MainActor @Sendable () -> Void

    var body: some View {
        GlassCard(padding: 18, cornerRadius: 20) {
            VStack(spacing: 0) {
                HStack {
                    Label("Device Settings", systemImage: "slider.horizontal.3")
                        .font(.system(size: 13, weight: .semibold))
                    Spacer()
                    Button("Done", action: onDone)
                        .buttonStyle(.glass)
                        .controlSize(.small)
                }
                .padding(.bottom, 6)

                divider

                settingRow(
                    title: "Stream Quality",
                    subtitle: device.settings.streamQuality.detail
                ) {
                    Picker(
                        "Stream Quality",
                        selection: Binding(
                            get: { device.settings.streamQuality },
                            set: onQualityChange
                        )
                    ) {
                        ForEach(StreamQuality.allCases) { quality in
                            Text(quality.label).tag(quality)
                        }
                    }
                    .labelsHidden()
                    .pickerStyle(.menu)
                    .frame(width: 148)
                }

                divider

                settingRow(
                    title: "Audio Playback",
                    subtitle: "Stream phone media audio to this Mac"
                ) {
                    toggle(
                        isOn: Binding(
                            get: { device.settings.audioEnabled },
                            set: onAudioChange
                        )
                    )
                }

                divider

                settingRow(
                    title: "Auto-Sync Clipboard",
                    subtitle: "Keep text in sync while the session is active"
                ) {
                    toggle(
                        isOn: Binding(
                            get: { device.settings.autoSyncClipboard },
                            set: onAutoSyncChange
                        )
                    )
                }

                divider

                settingRow(
                    title: "Auto-Reconnect",
                    subtitle: "Rejoin the phone automatically when it returns to the LAN"
                ) {
                    toggle(
                        isOn: Binding(
                            get: { device.settings.autoReconnect },
                            set: onAutoReconnectChange
                        )
                    )
                }
            }
        }
    }

    private var divider: some View {
        Rectangle()
            .fill(.white.opacity(0.06))
            .frame(height: 1)
    }

    private func settingRow<Control: View>(
        title: String,
        subtitle: String,
        @ViewBuilder control: () -> Control
    ) -> some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.system(size: 12.5, weight: .medium))
                Text(subtitle)
                    .font(.system(size: 10.5))
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 12)
            control()
        }
        .padding(.vertical, 10)
    }

    private func toggle(isOn: Binding<Bool>) -> some View {
        Toggle("", isOn: isOn)
            .labelsHidden()
            .toggleStyle(.switch)
            .controlSize(.small)
    }
}
