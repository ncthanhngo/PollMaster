import SwiftUI
import PollMasterCore

/// Pick the project the records tab follows: search, "Tất cả dự án", the ERP projects by kind, import bundle.
struct ProjectSwitcherSheet: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""
    @State private var importing = false

    private func shown(_ kind: String) -> [Project] {
        env.projects(ofKind: kind).filter { query.isEmpty || $0.name.localizedCaseInsensitiveContains(query) || $0.code.localizedCaseInsensitiveContains(query) }
    }

    var body: some View {
        NavigationStack {
            List {
                if query.isEmpty {
                    Button { pick(nil) } label: {
                        row(icon: AnyView(IconBadge(systemImage: "square.stack.3d.up", tint: .gray, size: 36)), title: "Tất cả dự án", subtitle: nil,
                            stats: env.summaries.reduce(into: ProjectStats()) { s, r in s.addSummary(r) }, selected: env.currentProjectId == nil)
                    }
                }
                ForEach(projectKindSections, id: \.kind) { s in
                    let list = shown(s.kind)
                    if !list.isEmpty {
                        Section(s.title) {
                            ForEach(list) { p in
                                Button { pick(p.id) } label: {
                                    row(icon: AnyView(ProjectAvatar(project: p, size: 36)), title: p.name, subtitle: p.code, stats: env.stats(of: p),
                                        selected: env.currentProjectId == p.id)
                                }
                            }
                        }
                    }
                }
                Section {
                    Button("Nhập gói dự án", systemImage: "square.and.arrow.down") { importing = true }
                }
            }
            .buttonStyle(.plain)
            .searchable(text: $query, prompt: "Tìm dự án")
            .refreshable { await env.syncProjects() }
            .navigationTitle("Chọn dự án").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
            .projectBundlePicker(isPresented: $importing)
        }
        .presentationDetents([.medium, .large])
    }

    private func pick(_ id: String?) { env.currentProjectId = id; dismiss() }

    private func row(icon: AnyView, title: String, subtitle: String?, stats: ProjectStats, selected: Bool) -> some View {
        HStack(spacing: 10) {
            icon
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.headline)
                if let subtitle { Text(subtitle).font(.footnote).secondaryText() }
                ProjectCounts(stats: stats)
            }
            Spacer()
            if selected { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
        }
        .contentShape(Rectangle())
    }
}

extension ProjectStats {
    mutating func addSummary(_ s: RecordSummary) {
        if s.status == .completed { completed += 1 } else if s.returned { returned += 1 } else { draft += 1 }
    }
}
