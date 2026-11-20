import SwiftUI

@main
struct P16BLEHIDApp: App {
    @StateObject private var peripheral = P16HIDPeripheral()

    var body: some Scene {
        WindowGroup("P16 BLE HID Test") {
            P16BLEHIDView(peripheral: peripheral)
                .frame(minWidth: 520, minHeight: 560)
        }
    }
}
