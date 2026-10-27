import SwiftUI
import UIKit
import PollMasterCore

struct ShareItems: Identifiable { let id = UUID(); let urls: [URL] }

struct ActivityView: UIViewControllerRepresentable {
    let items: [URL]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}

/// Choose formats, build files off the main thread, then present the share sheet.
struct ExportSheet: View {
    @Environment(\.pmTheme) private var theme
    let recordId: String
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var working = false
    @State private var error: String?
    @State private var share: ShareItems?

    var body: some View {
        @Bindable var settings = env.settings
        NavigationStack {
            Form {
                Section("Định dạng") {
                    ForEach(ExportFormat.allCases) { f in
                        Toggle(f.title, isOn: Binding(
                            get: { settings.exportFormats.contains(f) },
                            set: { on in if on { settings.exportFormats.insert(f) } else { settings.exportFormats.remove(f) } }))
                    }
                }
                if working { Section { HStack { ProgressView(); Text("Đang tạo tệp…").padding(.leading, 8) } } }
                if let error { Section { Text(error).foregroundStyle(theme.alert) } }
            }
            .navigationTitle("Xuất")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Xuất") { run() }.disabled(working || settings.exportFormats.isEmpty)
                }
            }
            .sheet(item: $share, onDismiss: { dismiss() }) { ActivityView(items: $0.urls).presentationDetents([.medium, .large]) }
        }
        .presentationDetents([.medium, .large])
    }

    private func run() {
        env.flushEditors()   // any open editor so the export sees the latest data
        guard let record = env.recordStore.load(recordId), let template = env.template(for: recordId) else {
            error = "Không đọc được phiếu hoặc mẫu."; return
        }
        working = true; error = nil
        let exporter = RecordExporter(store: env.recordStore)
        let formats = Array(env.settings.exportFormats)
        let project = env.project(of: record)
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports/\(UUID().uuidString)")
        Task {
            do {
                let urls = try await Task.detached(priority: .userInitiated) {
                    try exporter.export(template, record, formats: formats, into: dir, project: project)
                }.value
                share = ShareItems(urls: urls)
            } catch { self.error = "Xuất thất bại: \(error.localizedDescription)" }
            working = false
        }
    }
}
