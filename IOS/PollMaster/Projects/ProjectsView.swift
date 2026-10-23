import SwiftUI
import UniformTypeIdentifiers
import PollMasterCore

extension UTType {
    /// `.pmproject`, declared in Info.plist (project.yml).
    static let pollMasterProject = UTType(exportedAs: "com.evselab.pollmaster.project", conformingTo: .zip)
}

struct ProjectRoute: Hashable { let id: String }

/// "Dự án" tab: active projects first, "Đã lưu trữ" collapsed at the end.
struct ProjectsView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var importing = false
    @State private var showArchived = false

    private var active: [Project] { env.activeProjects }
    private var archived: [Project] { env.projects.filter(\.archived) }

    var body: some View {
        @Bindable var env = env
        NavigationStack(path: $env.projectsPath) {
            List {
                if env.projects.isEmpty {
                    ContentUnavailableView("Chưa có dự án", systemImage: "folder",
                                           description: Text("Nhấn + để tạo dự án hoặc nhập gói dự án."))
                }
                ForEach(active) { p in
                    NavigationLink(value: ProjectRoute(id: p.id)) { ProjectCard(project: p) }
                }
                if !archived.isEmpty {
                    Section {
                        DisclosureGroup("Đã lưu trữ (\(archived.count))", isExpanded: $showArchived) {
                            ForEach(archived) { p in
                                NavigationLink(value: ProjectRoute(id: p.id)) { ProjectCard(project: p) }
                            }
                        }
                    }
                }
            }
            .listRowSpacing(10)
            .navigationTitle("Dự án")
            .navigationDestination(for: ProjectRoute.self) { ProjectDetailView(projectId: $0.id) }
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { AppearanceToggleButton() }
                ToolbarItem(placement: .primaryAction) {
                    Menu {
                        Button("Dự án mới", systemImage: "folder.badge.plus") { env.showNewProject = true }
                        Button("Nhập gói dự án", systemImage: "square.and.arrow.down") { importing = true }
                    } label: { Image(systemName: "plus") }.accessibilityLabel("Thêm dự án")
                }
            }
            .sheet(isPresented: $env.showNewProject) { ProjectFormView(project: nil) }
            .projectBundlePicker(isPresented: $importing)
            .onAppear { env.reload() }
        }
    }
}

struct ProjectCard: View {
    let project: Project
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        let stats = env.stats(of: project)
        HStack(alignment: .top, spacing: 12) {
            RoundedRectangle(cornerRadius: 2).fill(project.tint).frame(width: 4).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 6) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(project.name).font(.headline).lineLimit(2)
                    Text([project.code, project.client].compactMap { $0 }.joined(separator: " · ")).font(.subheadline).secondaryText().lineLimit(1)
                }
                HStack(spacing: 6) {
                    StatusPill(text: "\(project.templates.count) mẫu", neutral: true)
                    StatusPill(text: "\(env.sites(forProject: project.id).count) điểm", neutral: true)
                    if project.archived { StatusPill(text: "Đã lưu trữ", neutral: true) }
                }
                ProjectCounts(stats: stats)
                Text(stats.latestText).font(.caption2).secondaryText()
            }
        }
        .padding(.vertical, 6)
        .opacity(project.archived ? 0.7 : 1)
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
