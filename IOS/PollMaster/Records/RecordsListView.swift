import SwiftUI
import PollMasterCore

private enum StatusFilter: String, CaseIterable, Identifiable {
    case all = "Tất cả", draft = "Nháp", completed = "Hoàn thành"
    var id: String { rawValue }
}

struct RecordsListView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var search = ""
    @State private var filter: StatusFilter = .all
    @State private var choosingTemplate = false
    @State private var deleting: RecordSummary?
    @State private var exporting: RecordSummary?

    private var shown: [RecordSummary] {
        env.summaries.filter { s in
            (filter == .all || (filter == .draft) == (s.status == .draft))
                && (search.isEmpty || s.title.localizedCaseInsensitiveContains(search) || s.subtitle.localizedCaseInsensitiveContains(search))
        }
    }

    var body: some View {
        @Bindable var env = env
        NavigationStack(path: $env.recordsPath) {
            VStack(spacing: 0) {
                Picker("Hiển thị", selection: $env.recordsShowMap) {
                    Text("Danh sách").tag(false)
                    Text("Bản đồ").tag(true)
                }
                .pickerStyle(.segmented).padding(.horizontal).padding(.bottom, 6)
                if env.recordsShowMap {
                    RecordsMapView(records: shown)
                } else if env.summaries.isEmpty {
                    ContentUnavailableView("Chưa có phiếu", systemImage: "list.clipboard",
                                           description: Text("Nhấn + để tạo phiếu khảo sát từ một mẫu."))
                } else {
                    List(shown) { s in
                        if s.loadError != nil {
                            RecordRow(summary: s)
                                .swipeActions(edge: .trailing) { Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s } }
                                .contextMenu { Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s } }
                        } else {
                            NavigationLink(value: Route.editor(s.id)) { RecordRow(summary: s) }
                                .swipeActions(edge: .trailing) {
                                    Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s }
                                    Button("Xuất", systemImage: "square.and.arrow.up") { exporting = s }.tint(.blue)
                                }
                                .swipeActions(edge: .leading) {
                                    Button("Nhân bản", systemImage: "plus.square.on.square") { env.duplicate(s.id) }.tint(.indigo)
                                }
                                .contextMenu {
                                    Button("Nhân bản", systemImage: "plus.square.on.square") { env.duplicate(s.id) }
                                    Button("Xuất", systemImage: "square.and.arrow.up") { exporting = s }
                                    Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s }
                                }
                        }
                    }
                }
            }
            .navigationTitle("Phiếu khảo sát")
            .searchable(text: $search, prompt: "Tìm theo tiêu đề")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Picker("Trạng thái", selection: $filter) {
                        ForEach(StatusFilter.allCases) { Text($0.rawValue).tag($0) }
                    }.pickerStyle(.menu)
                }
                ToolbarItem(placement: .topBarTrailing) { AppearanceToggleButton() }
                ToolbarItem(placement: .primaryAction) {
                    Button("Tạo phiếu", systemImage: "plus") {
                        if let only = env.newestTemplates.first, env.newestTemplates.count == 1 { env.startCreate(from: only) }
                        else { choosingTemplate = true }
                    }
                }
            }
            .navigationDestination(for: Route.self) { RouteDestination(route: $0) }
            .onAppear { env.reload() }
            .confirmationDialog("Chọn mẫu", isPresented: $choosingTemplate, titleVisibility: .visible) {
                ForEach(env.newestTemplates) { t in Button("\(t.title) (\(t.id) v\(t.version))") { env.startCreate(from: t) } }
            }
            .confirmationDialog("Xóa phiếu này? Ảnh và dữ liệu đi kèm sẽ mất.", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible) {
                Button("Xóa", role: .destructive) { if let d = deleting { env.delete(d.id) }; deleting = nil }
            }
            .sheet(item: $exporting) { ExportSheet(recordId: $0.id) }
        }
    }
}

struct RecordRow: View {
    let summary: RecordSummary
    @Environment(\.pmTheme) private var theme

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            thumbnail
            VStack(alignment: .leading, spacing: 3) {
                Text(summary.title).font(.headline).lineLimit(1)
                Text(summary.subtitle).font(.subheadline).secondaryText().lineLimit(summary.loadError == nil ? 1 : 3)
                if summary.loadError == nil {
                    HStack {
                        Text("Cập nhật \(ISODate.format(summary.updatedAt, "dd/MM HH:mm"))")
                        Spacer()
                        Text("\(summary.progress.done)/\(summary.progress.total)").monospacedDigit()
                    }
                    .font(.caption).secondaryText()
                    ProgressView(value: summary.progress.fraction)
                }
                FlowLayout(spacing: 6) {
                    ForEach(chips, id: \.text) { c in
                        Text(c.text).font(.caption2.weight(.semibold))
                            .padding(.horizontal, 7).padding(.vertical, 2)
                            .background(c.color.opacity(0.18), in: Capsule())
                            .overlay(Capsule().stroke(c.color.opacity(theme.highContrast ? 1 : 0.35), lineWidth: 1))
                            .foregroundStyle(c.text == "Nháp" ? theme.secondaryText : c.color)
                    }
                }
            }
        }
        .padding(.vertical, 3)
    }

    @ViewBuilder private var thumbnail: some View {
        if let url = summary.thumbnail {
            Thumbnail(url: url, video: false, size: 48)
        } else {
            Image(systemName: summary.loadError == nil ? "doc.text" : "exclamationmark.triangle")
                .font(.title3).secondaryText()
                .frame(width: 48, height: 48)
                .background(theme.inputBackground, in: RoundedRectangle(cornerRadius: 8))
        }
    }

    private var chips: [(text: String, color: Color)] {
        if summary.loadError != nil { return [("Lỗi đọc", theme.alert)] }
        var out: [(String, Color)] = []
        if summary.returned && summary.status == .draft { out.append(("Cần sửa", .orange)) }
        else { out.append(summary.status == .draft ? ("Nháp", .gray) : ("Hoàn thành", theme.success)) }
        if summary.needsBackup { out.append(("Chưa sao lưu", theme.warning)) }
        return out
    }
}
