import AVFoundation
import SwiftUI

/// P08 live mirror receiver surface.
struct LiveMirrorView: View {
    @ObservedObject var model: LiveMirrorReceiver

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("P08 — live mirror receiver")
                .font(.title2).bold()
            Text("Listens for an authenticated mirroring session and renders the live H.264 stream. " +
                 "Compare the pairing code on both devices before confirming.")
                .font(.callout)
                .foregroundStyle(.secondary)

            LiveVideoLayerView(layer: model.layer)
                .frame(minWidth: 480, minHeight: 270)
                .background(Color.black)
                .clipShape(RoundedRectangle(cornerRadius: 6))

            HStack(spacing: 10) {
                Button("Listen") { model.startListening() }
                Button("Confirm pairing") { model.confirmPairing() }
                Button("Reject pairing") { model.rejectPairing() }
                Button("Request keyframe") { model.requestKeyframe() }
                Button("Stop") { model.stop() }
            }

            Text("state=\(model.state.rawValue) received=\(model.framesReceived) " +
                 "enqueued=\(model.framesEnqueued) dropped=\(model.framesDropped) " +
                 "formats=\(model.formatChanges)")
                .font(.system(.caption, design: .monospaced))

            if let code = model.pairingCode {
                Text("pairing code: \(ShortAuthenticationCode.formatForDisplay(code))  peer pin: \(model.peerPin ?? "-")")
                    .font(.system(.caption, design: .monospaced))
            }

            Text("event: \(model.lastEvent)")
                .font(.system(.caption, design: .monospaced))
                .foregroundStyle(.secondary)

            GroupBox("Events") {
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(model.events.enumerated()), id: \.offset) { _, event in
                            Text(event)
                                .font(.system(.caption2, design: .monospaced))
                                .textSelection(.enabled)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                    .padding(4)
                }
                .frame(minHeight: 140)
            }
        }
        .padding(16)
    }
}

private struct LiveVideoLayerView: NSViewRepresentable {
    let layer: AVSampleBufferDisplayLayer

    func makeNSView(context: Context) -> LiveVideoContainerView {
        LiveVideoContainerView(videoLayer: layer)
    }

    func updateNSView(_ nsView: LiveVideoContainerView, context: Context) {
        nsView.videoLayer = layer
    }
}

private final class LiveVideoContainerView: NSView {
    var videoLayer: AVSampleBufferDisplayLayer {
        didSet {
            oldValue.removeFromSuperlayer()
            layer?.addSublayer(videoLayer)
            needsLayout = true
        }
    }

    init(videoLayer: AVSampleBufferDisplayLayer) {
        self.videoLayer = videoLayer
        super.init(frame: .zero)
        wantsLayer = true
        layer?.backgroundColor = NSColor.black.cgColor
        layer?.addSublayer(videoLayer)
    }

    required init?(coder: NSCoder) { nil }

    override func layout() {
        super.layout()
        videoLayer.frame = bounds
    }
}
