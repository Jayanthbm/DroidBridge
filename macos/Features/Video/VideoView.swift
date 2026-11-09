import AVFoundation
import SwiftUI

/// P07 viewer surface.
///
/// The layer is the real `AVSampleBufferDisplayLayer`; the controls exist so the recovery and
/// backpressure paths can be exercised without pretending the prototype is a product player.
struct VideoView: View {
    @ObservedObject var model: VideoReceiverModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("P07 — Mac H.264 receiver")
                .font(.title2).bold()
            Text("Generated Annex-B H.264 → format description → AVCC samples → display layer. "
                 + "No Android code is involved.")
                .font(.callout)
                .foregroundStyle(.secondary)

            VideoLayerView(layer: model.layer)
                .frame(minWidth: 480, minHeight: 270)
                .background(Color.black)
                .clipShape(RoundedRectangle(cornerRadius: 6))

            HStack(spacing: 10) {
                Button("Play") { model.play() }
                Button("Pause") { model.pause() }
                Button("Flush + recover") { model.recover() }
                Button("Reload fixture") { model.load(path: Self.fixturePath) }
            }

            Text("state=\(model.state.rawValue) frames=\(model.frameCount) " +
                 "ready=\(model.readyForDisplay) preventsCapture=true")
                .font(.system(.caption, design: .monospaced))

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
                .frame(minHeight: 120)
            }
        }
        .padding(16)
    }

    static var fixturePath: String {
        Bundle.main.url(forResource: "p07-test", withExtension: "h264")?.path
            ?? "/tmp/p07-test.h264"
    }
}

private struct VideoLayerView: NSViewRepresentable {
    let layer: AVSampleBufferDisplayLayer

    func makeNSView(context: Context) -> VideoContainerView {
        VideoContainerView(videoLayer: layer)
    }

    func updateNSView(_ nsView: VideoContainerView, context: Context) {
        nsView.videoLayer = layer
    }
}

private final class VideoContainerView: NSView {
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

    required init?(coder: NSCoder) {
        nil
    }

    override func layout() {
        super.layout()
        videoLayer.frame = bounds
    }
}
