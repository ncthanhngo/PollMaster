import SwiftUI
import PollMasterCore

/// "Đọc chữ từ ảnh" for text / multiline fields with `photoRef` (ux-spec L15): lines recognised on the referenced photos, tap to use.
struct OCRButton: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope
    @Environment(\.pmTheme) private var theme
    @State private var capture = false
    @State private var busy = false
    @State private var lines: [String]?
    @State private var error: String?

    var body: some View {
        if let ref = field.photoRef, let r = scope.reference(ref), r.field.type == .photo, !scope.locked {
            VStack(alignment: .leading, spacing: 4) {
                Button { run(r.field, r.value) } label: {
                    if busy { ProgressView() } else { Label("Đọc chữ từ ảnh", systemImage: "text.viewfinder") }
                }
                .fieldAction().disabled(busy)
                if let error { Text(error).font(.caption).foregroundStyle(theme.alert) }
            }
            .sheet(isPresented: Binding(get: { lines != nil }, set: { if !$0 { lines = nil } })) {
                OCRResultSheet(lines: lines ?? []) { use($0) }
            }
            .fullScreenCover(isPresented: $capture) {
                CameraPicker(kind: .photo, onImage: { image in
                    capture = false
                    do {
                        let m = try scope.media.saveImage(image, fieldId: ref, location: scope.location.lastKnown)
                        var fvRef = r.value.wrappedValue
                        fvRef.mediaInV = fvRef.mediaInV + [m]
                        r.value.wrappedValue = fvRef
                        read([m], from: scope)
                    } catch { self.error = error.localizedDescription }
                }, onVideo: { _ in }).ignoresSafeArea()
            }
        }
    }

    private func run(_ ref: Field, _ value: Binding<FieldValue>) {
        error = nil
        let photos = value.wrappedValue.mediaInV
        if photos.isEmpty {
            if CameraPicker.isAvailable { capture = true } else { error = "Chưa có ảnh \(ref.id) và máy không có camera." }
        } else { read(photos, from: scope) }
    }

    private func read(_ photos: [Media], from scope: FieldScope) {
        busy = true
        let files = photos.map { scope.media.url($0) }
        Task {
            let found = await TextRecognizer.lines(in: files)
            busy = false
            if found.isEmpty { error = "Không đọc được chữ nào trong ảnh." } else { lines = found }
        }
    }

    private func use(_ line: String) {
        if field.type == .multiline, let cur = fv.v?.string, !cur.isEmpty { fv.v = .string(cur + "\n" + line) } else { fv.v = .string(line) }
        lines = nil
    }
}

private struct OCRResultSheet: View {
    let lines: [String]
    let onPick: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(lines, id: \.self) { line in
                Button { onPick(line) } label: { Text(line).foregroundStyle(.primary) }
            }
            .navigationTitle("Chữ đọc được")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}

/// 🎤 on multiline fields (ux-spec L16): dictate Vietnamese, appended to the end of the text. Hidden without a recogniser.
struct DictationButton: View {
    @Binding var fv: FieldValue
    @Environment(\.pmTheme) private var theme
    @State private var service = DictationService()
    @State private var base = ""

    var body: some View {
        if service.isAvailable {
            VStack(alignment: .leading, spacing: 4) {
                Button { toggle() } label: {
                    Label(service.listening ? "Dừng" : "Đọc giọng nói", systemImage: service.listening ? "stop.circle.fill" : "mic.fill")
                        .foregroundStyle(service.listening ? Color.red : Color.accentColor)
                }
                .fieldAction()
                if let e = service.error { Text(e).font(.caption).foregroundStyle(theme.alert) }
            }
            .onDisappear { service.stop() }
        }
    }

    private func toggle() {
        if service.listening { service.stop(); return }
        base = fv.v?.string ?? ""
        Task {
            await service.start { text in
                let sep = base.isEmpty || base.hasSuffix("\n") || base.hasSuffix(" ") ? "" : " "
                fv.v = .string(base + sep + text)
            }
        }
    }
}
