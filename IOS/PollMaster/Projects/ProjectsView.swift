import SwiftUI
import UniformTypeIdentifiers
import PollMasterCore

extension UTType {
    /// `.pmproject`, declared in Info.plist (project.yml).
    static let pollMasterProject = UTType(exportedAs: "com.evselab.pollmaster.project", conformingTo: .zip)
}

struct ProjectRoute: Hashable { let id: String }

/// Section titles of the ERP project kinds, in list order.
let projectKindSections: [(kind: String, title: String)] = [("company", "Dự án công ty"), ("internal", "Nội bộ")]

/// "Dự án" tab: the ERP projects of the signed-in account, "Dự án công ty" then "Nội bộ" (like the ERP). Pull to refresh.
struct ProjectsView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var importing = false

    var body: some View {
        @Bindable var env = env
        NavigationStack(path: $env.projectsPath) {
            List {
                if let e = env.syncError { Label(e, systemImage: "wifi.exclamationmark").font(.footnote).foregroundStyle(.orange) }
                if env.projects.isEmpty {
                    ContentUnavailableView(env.syncing ? "Đang tải dự án…" : "Chưa có dự án", systemImage: "folder",
                                           description: Text("Dự án lấy từ ERP: chỉ hiện dự án bạn tham gia. Kéo xuống để tải lại."))
                }
                ForEach(projectKindSections, id: \.kind) { s in
                    let list = env.projects(ofKind: s.kind)
                    if !list.isEmpty {
                        Section("\(s.title) (\(list.count))") {
                            ForEach(list) { p in
                                NavigationLink(value: ProjectRoute(id: p.id)) { ProjectCard(project: p) }
                            }
                        }
                    }
                }
            }
            .listRowSpacing(10)
            .refreshable { await env.syncProjects() }
            .navigationTitle("Dự án")
            .navigationDestination(for: ProjectRoute.self) { ProjectDetailView(projectId: $0.id) }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { AppearanceToggleButton() }
                ToolbarItem(placement: .primaryAction) {
                    Button("Nhập gói dự án", systemImage: "square.and.arrow.down") { importing = true }
                }
            }
            .projectBundlePicker(isPresented: $importing)
        }
    }
}

struct ProjectCard: View {
    let project: Project
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        let stats = env.stats(of: project)
        // three single lines: name · code, client, templates, sites · counts and last activity
        HStack(spacing: 12) {
            ProjectAvatar(project: project, size: 44)
            VStack(alignment: .leading, spacing: 3) {
                Text(project.name).font(.body.weight(.semibold)).lineLimit(1).minimumScaleFactor(0.85)
                // drop the less useful parts instead of cutting a word in half
                let head = [project.code, project.client].compactMap { $0 }
                let extra = ["\(project.templates.count) mẫu", "\(env.sites(forProject: project.id).count) điểm"]
                ViewThatFits(in: .horizontal) {
                    Text((head + extra).joined(separator: " · ")).lineLimit(1)
                    Text(head.joined(separator: " · ")).lineLimit(1)
                    Text(project.code).lineLimit(1)
                }
                .font(.footnote).secondaryText()
                HStack(spacing: 4) {
                    ProjectCounts(stats: stats)
                    if let latest = stats.latest {
                        Text("· \(ISODate.format(latest, "dd/MM"))").font(.caption).secondaryText().fixedSize()
                    }
                }
            }
        }
        .padding(.vertical, 4)
    }
}

extension View {
    /// File picker for a `.pmproject`; the chosen file opens the import preview.
    func projectBundlePicker(isPresented: Binding<Bool>) -> some View { modifier(BundlePicker(isPresented: isPresented)) }
}

private struct BundlePicker: ViewModifier {
    @Binding var isPresented: Bool
    @Environment(AppEnvironment.self) private var env

    func body(content: Content) -> some View {
        content.fileImporter(isPresented: $isPresented, allowedContentTypes: [.pollMasterProject, .zip, .data]) { result in
            switch result {
            case .success(let url): env.openIncoming(url)
            case .failure(let e): env.errorMessage = e.localizedDescription
            }
        }
    }
}
