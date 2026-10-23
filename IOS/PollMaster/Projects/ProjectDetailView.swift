import SwiftUI
import PollMasterCore

struct ProjectDetailView: View {
    let projectId: String
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var addingTemplates = false
    @State private var exportingSummary = false
    @State private var share: ShareItems?
    @State private var confirmDelete = false
    @State private var removing: TemplateRef?

    private var project: Project? { env.projects.first { $0.id == projectId } }

    var body: some View {
        Group {
            if let p = project { content(p) } else {
                ContentUnavailableView("Không tìm thấy dự án", systemImage: "folder.badge.questionmark")
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { env.reload() }
    }

    private func content(_ p: Project) -> some View {
        let stats = env.stats(of: p)
        return List {
            Section {
                HStack(spacing: 10) {
                    ProjectDot(project: p, size: 16)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(p.name).font(.title3.bold())
                        Text(p.code).font(.subheadline.monospaced()).secondaryText()
                    }
                }
                if let c = p.client { LabeledContent("Khách hàng", value: c) }
                if let d = p.description { Text(d).font(.subheadline) }
                if p.archived { Label("Đã lưu trữ", systemImage: "archivebox").foregroundStyle(.orange).font(.subheadline) }
            }
            Section {
                HStack(spacing: 10) {
                    StatTile(value: "\(stats.draft)", label: "Nháp")
                    StatTile(value: "\(stats.returned)", label: "Cần sửa")
                    StatTile(value: "\(stats.completed)", label: "Hoàn thành")
                }
                .listRowBackground(Color.clear).listRowInsets(EdgeInsets())
                Text("Phiếu gần nhất: \(stats.latest.map { ISODate.format($0, "dd/MM/yyyy HH:mm") } ?? "–")").font(.caption).secondaryText()
                    .listRowBackground(Color.clear).listRowInsets(EdgeInsets(top: 4, leading: 4, bottom: 0, trailing: 4))
            }
            Section {
                Button("Mở danh sách phiếu", systemImage: "list.clipboard") { openRecords(p) }
                Button("Xuất tổng hợp (Excel)", systemImage: "tablecells") { exportingSummary = true }
                Button("Chia sẻ gói dự án", systemImage: "square.and.arrow.up") {
                    do { share = ShareItems(urls: [try env.bundleFile(for: p)]) } catch { env.errorMessage = error.localizedDescription }
                }
            }
            templatesSection(p)
            Section("Danh sách điểm") {
                NavigationLink { SitesView(projectId: p.id) } label: {
                    let sites = env.sites(forProject: p.id), visited = env.visitedCodes(forProject: p.id)
                    LabeledContent("Danh sách điểm", value: sites.isEmpty ? "Chưa có" : "\(sites.count) điểm · \(sites.filter { visited.contains($0.code) }.count) đã có phiếu")
                }
            }
            actions(p)
        }
        .navigationTitle(p.name)
        .toolbar { ToolbarItem(placement: .primaryAction) { Button("Sửa") { editing = true } } }
        .sheet(isPresented: $editing) { ProjectFormView(project: p) }
        .sheet(isPresented: $addingTemplates) { AddProjectTemplatesSheet(project: p) }
        .sheet(isPresented: $exportingSummary) { ProjectSummarySheet(project: p) }
        .sheet(item: $share) { ActivityView(items: $0.urls).presentationDetents([.medium, .large]) }
        .confirmationDialog("Gỡ mẫu \(removing?.id ?? "") khỏi dự án? Phiếu đã tạo vẫn mở được.", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible) {
            Button("Gỡ mẫu", role: .destructive) { if let r = removing { env.removeTemplate(r, from: p) }; removing = nil }
        }
        .confirmationDialog(deleteTitle(stats), isPresented: $confirmDelete, titleVisibility: .visible) {
            if stats.total == 0 { Button("Xóa dự án", role: .destructive) { if env.deleteProject(p) { dismiss() } } }
            else {
                Button(p.archived ? "Đã lưu trữ" : "Lưu trữ dự án") { env.setArchived(p, true) }
                Button("Mở danh sách phiếu") { openRecords(p) }
            }
        }
    }

    private func deleteTitle(_ s: ProjectStats) -> String {
        s.total == 0 ? "Xóa dự án này? Mẫu và danh sách điểm của dự án sẽ bị gỡ (mẫu trong thư viện vẫn còn)."
            : "Dự án còn \(s.total) phiếu nên chưa xóa được. Hãy chuyển phiếu sang dự án khác (menu phiếu > Chuyển dự án) hoặc lưu trữ dự án."
    }

    private func templatesSection(_ p: Project) -> some View {
        Section {
            ForEach(p.templates, id: \.self) { ref in
                let t = env.template(ref)
                VStack(alignment: .leading, spacing: 2) {
                    Text(t?.title ?? ref.id).font(.headline)
                    Text("\(ref.id) · v\(ref.version)\(t == nil ? " · thiếu trong thư viện" : "")").font(.caption).secondaryText()
                }
                .swipeActions { Button("Gỡ", systemImage: "minus.circle", role: .destructive) { removing = ref } }
            }
            ForEach(env.newerVersions(for: p), id: \.self) { newer in
                let old = p.templates.first { $0.id == newer.id }?.version ?? ""
                Button { env.useVersion(newer, in: p) } label: {
                    Label("\(newer.id): có bản v\(newer.version) (đang dùng v\(old)) — dùng cho phiếu mới?", systemImage: "arrow.up.circle")
                        .font(.subheadline).multilineTextAlignment(.leading)
                }
            }
            Button("Thêm mẫu từ thư viện", systemImage: "plus.circle") { addingTemplates = true }
        } header: { Text("Mẫu của dự án (\(p.templates.count))") } footer: {
            Text("Phiếu mới chỉ chọn được trong các mẫu này. Phiếu cũ giữ phiên bản mẫu của nó.")
        }
    }

    private func actions(_ p: Project) -> some View {
        // Rare, state-changing actions stay at the bottom, away from the everyday ones at the top.
        Section {
            Button(p.archived ? "Bỏ lưu trữ" : "Lưu trữ", systemImage: p.archived ? "tray.and.arrow.up" : "archivebox") {
                env.setArchived(p, !p.archived)
            }
            Button("Xóa dự án", systemImage: "trash", role: .destructive) { confirmDelete = true }
        }
    }

    private func openRecords(_ p: Project) {
        env.currentProjectId = p.id
        env.recordsShowMap = false
        env.selectedTab = .records
    }
}

/// Library templates (newest version of each id) not yet in the project.
struct AddProjectTemplatesSheet: View {
    let project: Project
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var picked: Set<String> = []

    private var candidates: [Template] {
        env.newestTemplates.filter { t in !project.templates.contains { $0.id == t.id } }
    }

    var body: some View {
        NavigationStack {
            List {
                if candidates.isEmpty {
                    ContentUnavailableView("Không còn mẫu nào để thêm", systemImage: "doc.text",
                                           description: Text("Nhập thêm mẫu ở tab Mẫu."))
                }
                ForEach(candidates) { t in
                    Button { if !picked.insert(t.key).inserted { picked.remove(t.key) } } label: {
                        HStack {
                            VStack(alignment: .leading) {
                                Text(t.title).font(.headline)
                                Text("\(t.id) · v\(t.version)").font(.caption).secondaryText()
                            }
                            Spacer()
                            Image(systemName: picked.contains(t.key) ? "checkmark.circle.fill" : "circle")
                                .foregroundStyle(picked.contains(t.key) ? Color.accentColor : .secondary)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .navigationTitle("Thêm mẫu").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Thêm") { env.addTemplates(candidates.filter { picked.contains($0.key) }, to: project); dismiss() }.disabled(picked.isEmpty)
                }
            }
        }
    }
}
