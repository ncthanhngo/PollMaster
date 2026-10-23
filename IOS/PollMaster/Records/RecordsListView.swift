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
                && (search.isEmpty || s.title.localizedCaseInsensitiveContains(search))
        }
    }

    var body: some View {
        @Bindable var env = env
        NavigationStack(path: $env.recordsPath) {
            Group {
                if env.summaries.isEmpty {
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
                ToolbarItem(placement: .primaryAction) {
                    Button("Tạo phiếu", systemImage: "plus") { choosingTemplate = true }
                }
            }
            .navigationDestination(for: Route.self) { RouteDestination(route: $0) }
            .onAppear { env.reload() }
            .confirmationDialog("Chọn mẫu", isPresented: $choosingTemplate, titleVisibility: .visible) {
                ForEach(env.templates) { t in Button("\(t.title) (\(t.id) v\(t.version))") { env.createRecord(from: t) } }
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

    private var badge: (text: String, color: Color) {
        if summary.loadError != nil { return ("Lỗi đọc", .red) }
        return summary.status == .draft ? ("Nháp", .orange) : ("Hoàn thành", .green)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack {
                Text(summary.title).font(.headline).lineLimit(1)
                Spacer()
                Text(badge.text).font(.caption.weight(.semibold))
                    .padding(.horizontal, 8).padding(.vertical, 2)
                    .background(badge.color.opacity(0.2), in: Capsule())
                    .foregroundStyle(badge.color)
            }
            Text(summary.templateTitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(summary.loadError == nil ? 1 : 3)
            HStack {
                Text(ISODate.format(summary.updatedAt, "HH:mm dd/MM/yyyy")).font(.caption).foregroundStyle(.secondary)
                Spacer()
                if summary.loadError == nil {
                    Text("\(summary.progress.done)/\(summary.progress.total)").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                }
            }
            if summary.loadError == nil { ProgressView(value: summary.progress.fraction) }
        }
        .padding(.vertical, 3)
    }
}
