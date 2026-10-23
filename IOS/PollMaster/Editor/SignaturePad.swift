import SwiftUI
import PollMasterCore

/// Finger/pencil drawing canvas that reports a PNG when a stroke ends.
struct SignaturePad: View {
    var onCommit: (Data) -> Void
    @State private var strokes: [[CGPoint]] = []
    @State private var current: [CGPoint] = []
    @State private var size: CGSize = .zero

    var body: some View {
        VStack(spacing: 6) {
            Canvas { ctx, _ in
                for s in strokes + [current] where s.count > 1 {
                    var p = Path(); p.addLines(s)
                    ctx.stroke(p, with: .color(.black), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
                }
            }
            .frame(height: 160)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.5)))
            .overlay(alignment: .topLeading) {
                if strokes.isEmpty && current.isEmpty { Text("Ký vào đây").font(.caption).foregroundStyle(.gray).padding(8) }
            }
            .background(GeometryReader { g in Color.clear.onAppear { size = g.size }.onChange(of: g.size) { _, s in size = s } })
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { current.append($0.location) }
                .onEnded { _ in
                    if current.count > 1 { strokes.append(current) }
                    current = []
                    commit()
                })
            HStack {
                Spacer()
                Button("Xóa", role: .destructive) { strokes = []; current = [] }.buttonStyle(.bordered)
            }
        }
    }

    @MainActor private func commit() {
        guard !strokes.isEmpty, size.width > 0 else { return }
        let content = Canvas { ctx, _ in
            for s in strokes where s.count > 1 {
                var p = Path(); p.addLines(s)
                ctx.stroke(p, with: .color(.black), style: StrokeStyle(lineWidth: 2.5, lineCap: .round, lineJoin: .round))
            }
        }
        .frame(width: size.width, height: size.height)
        .background(Color.white)
        let renderer = ImageRenderer(content: content)
        renderer.scale = 2
        if let png = renderer.uiImage?.pngData() { onCommit(png) }
    }
}
