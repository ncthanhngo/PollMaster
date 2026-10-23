import SwiftUI
import PollMasterCore

/// "+" of the records tab: project first when viewing "Tất cả dự án", then one of the project's templates.
struct NewRecordSheet: View {
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            if let p = env.currentProject { TemplatePicker(project: p, close: { dismiss() }) } else {
                List(env.activeProjects) { p in
                    NavigationLink {
                        TemplatePicker(project: p, close: { dismiss() })
                    } label: {
                        HStack(spacing: 10) {
                            ProjectDot(project: p)
                            VStack(alignment: .leading) { Text(p.name).font(.headline); Text(p.code).font(.caption.monospaced()).secondaryText() }
                        }
                    }
                }
                .overlay { if env.activeProjects.isEmpty { ContentUnavailableView("Chưa có dự án", systemImage: "folder", description: Text("Tạo dự án ở tab Dự án.")) } }
                .navigationTitle("Chọn dự án").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } } }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

private struct TemplatePicker: View {
    let project: Project
    let close: () -> Void
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        let items = env.templates(of: project)
        List(items) { t in
            Button { close(); env.startCreate(from: t, in: project) } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(t.title).font(.headline)
                    Text("\(t.id) · v\(t.version)").font(.caption).secondaryText()
                }
            }
            .buttonStyle(.plain)
        }
        .overlay {
            if items.isEmpty {
                ContentUnavailableView("Dự án chưa có mẫu", systemImage: "doc.text",
                                       description: Text("Mở dự án (tab Dự án) và thêm mẫu từ thư viện."))
            }
        }
        .navigationTitle(project.name).navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Hủy") { close() } } }
    }
}
