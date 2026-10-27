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
    @State private var creating = false
    @State private var moving: RecordSummary?
    @State private var deleting: RecordSummary?
    @State private var exporting: RecordSummary?
    /// Height of the template tab on the card's top edge (caption2 + its padding), grows with Dynamic Type.
    @ScaledMetric(relativeTo: .caption2) private var badgeHeight: CGFloat = 18

    private var shown: [RecordSummary] {
        env.projectSummaries.filter { s in
            (filter == .all || (filter == .draft) == (s.status == .draft))
                && (search.isEmpty || s.title.localizedCaseInsensitiveContains(search) || s.subtitle.localizedCaseInsensitiveContains(search))
        }
    }

    var body: some View {
        @Bindable var env = env
        NavigationStack(path: $env.recordsPath) {
            VStack(spacing: 0) {
                HStack {
                    Picker("Hiển thị", selection: $env.recordsShowMap) {
                        Text("Danh sách").tag(false)
                        Text("Bản đồ").tag(true)
                    }
                    .pickerStyle(.segmented)
                    Picker("Trạng thái", selection: $filter) {
                        ForEach(StatusFilter.allCases) { Text($0.rawValue).tag($0) }
                    }.pickerStyle(.menu)
                }
                .padding(.horizontal).padding(.bottom, 6)
                if let p = env.syncProgress { SyncProgressBar(progress: p) }
                if env.recordsShowMap {
                    RecordsMapView(records: shown)
                } else if env.projectSummaries.isEmpty {
                    ContentUnavailableView("Chưa có phiếu", systemImage: "list.clipboard",
                                           description: Text("Nhấn + để tạo phiếu khảo sát từ một mẫu của dự án."))
                } else {
                    List(shown) { s in
                        if s.loadError != nil {
                            RecordRow(summary: s, project: nil)
                                .swipeActions(edge: .trailing) { Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s } }
                                .contextMenu { Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s } }
                        } else {
                            let mayEdit = env.canEdit(s), mayCopy = env.canPost(to: s.projectId)
                            // the card draws its own background and outline: a list row background is clipped to the
                            // cell's rounded corners, which cut the outline at the corners
                            RecordRow(summary: s, project: env.project(id: s.projectId), me: env.currentUserId, failure: env.recordSyncIssues[s.id])
                                .padding(.leading, 16).padding(.trailing, 12).padding(.top, s.badge == nil ? 14 : badgeHeight + 4).padding(.bottom, 10)
                                .background(RecordFrame(summary: s))
                                .overlay { NavigationLink(value: Route.editor(s.id)) { EmptyView() }.opacity(0) }
                                .listRowInsets(EdgeInsets())
                                .listRowBackground(Color.clear)
                                .listRowSeparator(.hidden)
                            .swipeActions(edge: .trailing) {
                                if mayEdit { Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s } }
                                Button("Xuất", systemImage: "square.and.arrow.up") { exporting = s }.tint(.blue)
                            }
                            .swipeActions(edge: .leading) {
                                if mayCopy { Button("Nhân bản", systemImage: "plus.square.on.square") { env.duplicate(s.id) }.tint(.indigo) }
                            }
                            .contextMenu {
                                if mayCopy { Button("Nhân bản", systemImage: "plus.square.on.square") { env.duplicate(s.id) } }
                                Button("Xuất", systemImage: "square.and.arrow.up") { exporting = s }
                                Button("Lịch sử sửa", systemImage: "clock.arrow.circlepath") { env.recordsPath.append(.history(s.id)) }
                                if mayEdit {
                                    Button("Chuyển dự án", systemImage: "folder") { moving = s }
                                    Button("Xóa", systemImage: "trash", role: .destructive) { deleting = s }
                                }
                            }
                        }
                    }
                    .listRowSpacing(10)
                    .refreshable { await env.syncProjects() }
                }
            }
            .navigationTitle("Phiếu khảo sát")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $search, prompt: "Tìm theo tiêu đề")
            .toolbar {
                ToolbarItem(placement: .principal) { ProjectTitleButton { env.showProjectSwitcher = true } }
                ToolbarItem(placement: .topBarLeading) { AppearanceToggleButton() }
                if env.currentProject?.canPost != false {
                    ToolbarItem(placement: .primaryAction) { Button("Tạo phiếu", systemImage: "plus") { startNew() } }
                }
            }
            .navigationDestination(for: Route.self) { RouteDestination(route: $0) }
            .sheet(isPresented: $creating) { NewRecordSheet() }
            .sheet(isPresented: $env.showProjectSwitcher) { ProjectSwitcherSheet() }
            .confirmationDialog(moveTitle, isPresented: Binding(get: { moving != nil }, set: { if !$0 { moving = nil } }), titleVisibility: .visible) {
                if let m = moving {
                    ForEach(env.moveTargets(for: m)) { p in Button("\(p.name) (\(p.code))") { env.move(m.id, to: p); moving = nil } }
                }
            } message: { Text("Chỉ hiện các dự án có cùng mẫu với phiếu này.") }
            .confirmationDialog("Xóa phiếu này? Ảnh và dữ liệu đi kèm sẽ mất.", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible) {
                Button("Xóa", role: .destructive) { if let d = deleting { env.delete(d.id) }; deleting = nil }
            }
            .sheet(item: $exporting) { ExportSheet(recordId: $0.id) }
        }
    }
}

extension RecordsListView {
    private var moveTitle: String {
        guard let m = moving else { return "" }
        return env.moveTargets(for: m).isEmpty ? "Không có dự án nào khác dùng mẫu này. Thêm mẫu vào dự án đích trước." : "Chuyển phiếu sang dự án"
    }

    /// One template in a single current project starts right away; otherwise pick project / template first.
    func startNew() {
        if let p = env.currentProject, p.templates.count == 1, let t = env.template(p.templates[0]) { env.startCreate(from: t, in: p) }
        else { creating = true }
    }
}

/// Title of the records tab: project colour dot, name, chevron; opens the switcher.
struct ProjectTitleButton: View {
    let action: () -> Void
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let p = env.currentProject { ProjectAvatar(project: p, size: 24) } else { Image(systemName: "square.stack.3d.up").font(.footnote) }
                Text(env.currentProject?.name ?? "Tất cả dự án").font(.headline).lineLimit(1)
                Image(systemName: "chevron.down").font(.caption2.weight(.bold))
            }
            .foregroundStyle(.primary)
        }
        .tint(.primary)
        .accessibilityLabel("Dự án hiện tại: \(env.currentProject?.name ?? "Tất cả dự án")")
    }
}

struct RecordRow: View {
    let summary: RecordSummary
    /// The project the record belongs to: shown on every row, so it is always clear what the survey is for.
    var project: Project?
    /// Signed-in ERP user id (a record of somebody else shows "của …") and the last sync problem of this record.
    var me: String?
    var failure: String?
    @Environment(\.pmTheme) private var theme

    /// Three lines, none wrapping: title · project + date · status, backup flag and progress.
    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            thumbnail
            VStack(alignment: .leading, spacing: 5) {
                Text(summary.title).font(.body.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.85)
                if summary.loadError != nil {
                    Text(summary.subtitle).font(.footnote).secondaryText().lineLimit(3)
                } else {
                    HStack(spacing: 6) {
                        if let p = project {
                            ProjectAvatar(project: p, size: 16)
                            // full name when it fits, else the short code, never a cut-off name
                            ViewThatFits(in: .horizontal) {
                                Text(p.name).lineLimit(1)
                                Text(p.code).lineLimit(1)
                            }
                            Text("·")
                        }
                        Text(ISODate.format(summary.updatedAt, "dd/MM HH:mm")).fixedSize()
                    }
                    .font(.footnote).secondaryText()
                    .accessibilityElement(children: .combine)
                }
                HStack(spacing: 8) {
                    ForEach(chips, id: \.text) { c in StatusPill(text: c.text, color: c.color, neutral: c.text == "Nháp").fixedSize() }
                    if summary.needsBackup {
                        Image(systemName: "icloud.slash").font(.footnote.weight(.semibold)).foregroundStyle(theme.warning)
                            .accessibilityLabel("Chưa sao lưu")
                    }
                    if summary.loadError == nil {
                        ProgressView(value: summary.progress.fraction)
                        Text("\(summary.progress.done)/\(summary.progress.total)").font(.caption).monospacedDigit().secondaryText().fixedSize()
                    }
                }
                if summary.loadError == nil {
                    HStack(spacing: 6) {
                        SyncMarker(state: .of(dirty: summary.syncDirty, conflict: summary.syncConflict, failure: failure, syncedAt: summary.erpUploadedAt))
                        if let owner { Text("· của \(owner)").font(.caption).secondaryText().lineLimit(1) }
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }

    /// Creator's name when the record is somebody else's.
    private var owner: String? {
        guard let by = summary.createdBy, by != me else { return nil }
        return summary.createdByName ?? "người khác"
    }

    @ViewBuilder private var thumbnail: some View {
        if let url = summary.thumbnail {
            Thumbnail(url: url, video: false, size: 48)
        } else {
            IconBadge(systemImage: statusIcon.name, tint: statusIcon.tint, size: 48)
        }
    }

    private var statusIcon: (name: String, tint: Color) {
        if summary.loadError != nil { return ("exclamationmark.triangle.fill", theme.alert) }
        if summary.returned && summary.status == .draft { return ("arrow.uturn.backward.circle.fill", theme.attention) }
        return summary.status == .draft ? ("doc.text", theme.secondaryText) : ("checkmark.seal.fill", theme.success)
    }

    private var chips: [(text: String, color: Color)] {
        if summary.loadError != nil { return [("Lỗi đọc", theme.alert)] }
        var out: [(String, Color)] = []
        if summary.returned && summary.status == .draft { out.append(("Cần sửa", theme.attention)) }
        else { out.append(summary.status == .draft ? ("Nháp", .gray) : ("Hoàn thành", theme.success)) }
        return out
    }
}

/// Card of a record in its template's colour, with the template's short name on the top edge.
struct RecordFrame: View {
    let summary: RecordSummary

    var body: some View {
        // at least as round as the list cell (large on iOS 26), so the cell's own clipping never cuts the outline
        let shape = RoundedRectangle(cornerRadius: 28, style: .continuous)
        shape.fill(Color(.secondarySystemGroupedBackground))
            .overlay { shape.strokeBorder(Color(hex: summary.badgeColor) ?? .clear, lineWidth: 2) }
            .overlay(alignment: .topTrailing) {
                if let b = summary.badge, let tint = Color(hex: summary.badgeColor) {
                    // a tab hanging from the top edge of the outline (inside the card, so the list never clips it)
                    Text(b).font(.caption2.weight(.bold)).lineLimit(1).foregroundStyle(.white)
                        .padding(.horizontal, 10).padding(.top, 2).padding(.bottom, 3)
                        .background(tint, in: UnevenRoundedRectangle(bottomLeadingRadius: 8, bottomTrailingRadius: 8))
                        .padding(.trailing, 18).accessibilityHidden(true)
                }
            }
    }
}

/// "Đang đồng bộ n/N phiếu" while a run pushes records.
struct SyncProgressBar: View {
    let progress: SyncProgress

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Đang đồng bộ \(min(progress.done + 1, progress.total))/\(progress.total) phiếu").font(.footnote).monospacedDigit().secondaryText()
            ProgressView(value: Double(progress.done), total: Double(max(progress.total, 1)))
        }
        .padding(.horizontal).padding(.bottom, 6)
        .accessibilityElement(children: .combine)
    }
}
