import SwiftUI

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        @Bindable var model = model

        ZStack {
            AmbientBackground()

            if model.devices.isEmpty {
                EmptyStateView()
                    .transition(.opacity.combined(with: .scale(scale: 0.98)))
            } else {
                HubView()
                    .transition(.opacity)
            }
        }
        .frame(minWidth: 920, minHeight: 620)
        .tint(AppTheme.accent)
        .sheet(isPresented: $model.isPairingPresented) {
            PairingView()
                .environment(model)
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                ToastView(toast: toast)
                    .padding(.bottom, 54)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.35, dampingFraction: 0.82), value: model.toast)
        .animation(.easeInOut(duration: 0.25), value: model.devices.isEmpty)
        .task {
            #if DEBUG
            if ProcessInfo.processInfo.arguments.contains("--demo-mirror"),
               let deviceID = model.selectedDeviceID {
                try? await Task.sleep(for: .milliseconds(450))
                openWindow(id: "mirror", value: deviceID)
            }
            #endif
        }
    }
}
