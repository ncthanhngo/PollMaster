import SwiftUI
import PollMasterCore

struct TemplateDetailView: View {
    let template: Template
    @Environment(AppEnvironment.self) private var env

    @State private var choosing = false

    private var isNewest: Bool { env.newestTemplates.contains { $0.key == template.key } }
    /// Active projects that use exactly this template version.
    private var projects: [Project] { env.activeProjects.filter { $0.templates.contains(TemplateRef(template)) } }

    private func create() {
        if let c = env.currentProject, projects.contains(c) { env.startCreate(from: template, in: c) }
        else if projects.count == 1 { env.startCreate(from: template, in: projects[0]) }
        else { choosing = true }
    }

    var body: some View {
        List {
            Section {
                if let s = template.subtitle { Text(s).font(.subheadline) }
                Text("\(template.id) · v\(template.version)").font(.caption).secondaryText()
            }
            if let i = template.instructions {
                Section("Hướng dẫn") { Text(i).font(.footnote) }
            }
            Section("Các mục") {
                Text("\(Validator.headerSectionTitle) (\(template.header.count) trường)")
                ForEach(template.sections) { s in
                    Text("\(s.title) (\(s.fields.count) trường\(s.tables.isEmpty ? "" : ", \(s.tables.count) bảng"))")
                }
            }
            Section {
                if !isNewest {
                    Text("Phiên bản cũ: chỉ dùng cho các phiếu đã tạo. Phiếu mới được tạo từ phiên bản mới nhất.").font(.footnote).secondaryText()
                }
                Button(action: create) { Text("Tạo phiếu mới").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent).controlSize(.large).disabled(projects.isEmpty)
                if projects.isEmpty {
                    Text("Chưa có dự án nào dùng mẫu này. Mở dự án (tab Dự án) và thêm mẫu từ thư viện.").font(.footnote).secondaryText()
                }
            }
        }
        .confirmationDialog("Tạo phiếu trong dự án", isPresented: $choosing, titleVisibility: .visible) {
            ForEach(projects) { p in Button("\(p.name) (\(p.code))") { env.startCreate(from: template, in: p) } }
        }
        .navigationTitle(template.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}
