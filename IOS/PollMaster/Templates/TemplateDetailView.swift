import SwiftUI
import PollMasterCore

extension Template {
    /// Fields plus table columns, as shown in the library.
    var displayFieldCount: Int { allFields.count + allTables.reduce(0) { $0 + $1.columns.count } }
}

struct TemplateDetailView: View {
    let template: Template
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme

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
            Section { summary }
                .listRowBackground(Color.clear).listRowInsets(EdgeInsets())
            if !isNewest {
                Section {
                    Label("Phiên bản cũ: chỉ dùng cho các phiếu đã tạo. Phiếu mới được tạo từ phiên bản mới nhất.", systemImage: "clock.arrow.circlepath")
                        .font(.footnote).foregroundStyle(theme.warning)
                }
            }
            if let i = template.instructions {
                Section { DisclosureGroup("Hướng dẫn") { Text(i).font(.footnote) } }
            }
            Section("Các mục") {
                sectionRow(icon: "person.text.rectangle", title: Validator.headerSectionTitle, detail: "\(template.header.count) trường")
                ForEach(template.sections) { s in
                    sectionRow(icon: s.tables.isEmpty ? "list.bullet.rectangle" : "tablecells", title: s.title,
                               detail: "\(s.fields.count) trường" + (s.tables.isEmpty ? "" : " · \(s.tables.count) bảng"))
                }
            }
            if !projects.isEmpty {
                Section("Dùng trong dự án") {
                    ForEach(projects) { p in
                        HStack(spacing: 10) {
                            ProjectDot(project: p)
                            Text(p.name)
                            Spacer()
                            Text(p.code).font(.caption).secondaryText()
                        }
                    }
                }
            }
        }
        .bottomAction {
            Button(action: create) { Label("Tạo phiếu mới", systemImage: "plus").frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent).controlSize(.large).disabled(projects.isEmpty)
            if projects.isEmpty {
                Text("Chưa có dự án nào dùng mẫu này. Mở dự án (tab Dự án) và thêm mẫu từ thư viện.").font(.footnote).secondaryText()
                    .multilineTextAlignment(.center)
            }
        }
        .confirmationDialog("Tạo phiếu trong dự án", isPresented: $choosing, titleVisibility: .visible) {
            ForEach(projects) { p in Button("\(p.name) (\(p.code))") { env.startCreate(from: template, in: p) } }
        }
        .navigationTitle(template.id)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                IconBadge(systemImage: "doc.text.fill", size: 56)
                VStack(alignment: .leading, spacing: 4) {
                    Text(template.title).font(.title3.weight(.bold))
                    if let s = template.subtitle { Text(s).font(.subheadline).secondaryText() }
                    HStack(spacing: 6) {
                        Text(template.id).font(.caption.weight(.medium)).secondaryText()
                        StatusPill(text: "v\(template.version)", color: .accentColor)
                    }
                }
            }
            HStack(spacing: 10) {
                StatTile(value: "\(template.sections.count + 1)", label: "Mục")
                StatTile(value: "\(template.displayFieldCount)", label: "Trường")
                StatTile(value: "\(template.allTables.count)", label: "Bảng")
            }
        }
        .padding(.vertical, 8)
    }

    private func sectionRow(icon: String, title: String, detail: String) -> some View {
        HStack(spacing: 12) {
            IconBadge(systemImage: icon, size: 32)
            Text(title).font(.subheadline)
            Spacer(minLength: 8)
            Text(detail).font(.caption).secondaryText().monospacedDigit()
        }
    }
}
