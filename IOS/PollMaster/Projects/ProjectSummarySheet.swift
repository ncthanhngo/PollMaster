import SwiftUI
import PollMasterCore

/// "Xuất tổng hợp": one xlsx for the whole project, optionally with a zip of every record.
struct ProjectSummarySheet: View {
    @Environment(\.pmTheme) private var theme
    let project: Project
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var filter = ProjectSummaryExport.Filter.all
    @State private var withRecords = false
    @State private var working = false
    @State private var error: String?
    @State private var share: ShareItems?
    @State private var note: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Phiếu đưa vào") {
                    Picker("Lọc", selection: $filter) {
                        Text("Tất cả").tag(ProjectSummaryExport.Filter.all)
                        Text("Chỉ Hoàn thành").tag(ProjectSummaryExport.Filter.completed)
                    }.pickerStyle(.segmented)
                }
                Section {
                    Toggle("Kèm zip JSON + media của các phiếu", isOn: $withRecords)
                } footer: { Text("Excel: mỗi mẫu một sheet, mỗi phiếu một hàng, thêm sheet cho từng bảng.") }
                if working { Section { HStack { ProgressView(); Text("Đang tạo tệp…").padding(.leading, 8) } } }
                if let note { Section { Text(note).font(.footnote) } }
                if let error { Section { Text(error).foregroundStyle(theme.alert) } }
            }
            .navigationTitle("Xuất tổng hợp").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Xuất") { run() }.disabled(working) }
            }
            .sheet(item: $share) { ActivityView(items: $0.urls).presentationDetents([.medium, .large]) }
        }
        .presentationDetents([.medium, .large])
    }

    private func run() {
        env.flushEditors()
        working = true; error = nil; note = nil
        let project = project, filter = filter, withRecords = withRecords, store = env.recordStore, library = env.templates
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("exports/\(UUID().uuidString)")
        Task {
            do {
                let result = try await Task.detached(priority: .userInitiated) { () -> ([URL], ProjectSummaryExport.Output) in
                    try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                    let records = store.list().filter { $0.projectId == project.id }
                    let xlsx = dir.appendingPathComponent(ProjectSummaryExport.fileName(project))
                    let out = try ProjectSummaryExport.write(project: project, records: records, library: library, filter: filter, to: xlsx)
                    var urls = [xlsx]
                    if withRecords {
                        let zip = dir.appendingPathComponent(ProjectSummaryExport.recordsZipName(project))
                        try ProjectSummaryExport.writeRecordsZip(project: project, records: ProjectSummaryExport.select(records, filter: filter),
                                                                 library: library, store: store, to: zip)
                        urls.append(zip)
                    }
                    return (urls, out)
                }.value
                if result.1.skipped > 0 { note = "\(result.1.skipped) phiếu bị bỏ qua vì mẫu không còn trong thư viện." }
                share = ShareItems(urls: result.0)
            } catch { self.error = "Xuất thất bại: \(error.localizedDescription)" }
            working = false
        }
    }
}
