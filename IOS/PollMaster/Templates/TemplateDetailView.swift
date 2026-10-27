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
    @State private var editing = false
    @State private var discarding = false

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
            if env.canDraft(template) { Section { editContentRows } footer: { Text("Sửa nhãn, trường, bảng… rồi phát hành thành phiên bản mới. Nháp lưu trên máy, sửa được khi không có mạng.") } }
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
                            ProjectAvatar(project: p, size: 28)
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
        .sheet(isPresented: $editing) { TemplateEditSheet(template: template) }
        .confirmationDialog("Bỏ bản nháp?", isPresented: $discarding, titleVisibility: .visible) {
            Button("Bỏ bản nháp", role: .destructive) { if let d = env.draft(forTemplateId: template.id) { env.discardDraft(d.draftId) } }
        } message: { Text("Mọi thay đổi chưa phát hành sẽ mất.") }
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Sửa tên và màu", systemImage: "pencil") {
                    if env.canEditTemplate(template) { editing = true } else { env.infoMessage = AppEnvironment.templateNeedsManage }
                }
                .opacity(env.canEditTemplate(template) ? 1 : 0.45)
            }
        }
        .navigationTitle(template.id)
        .navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private var editContentRows: some View {
        if let d = env.draft(forTemplateId: template.id) {
            Button { env.resume(d) } label: {
                Label("Tiếp tục nháp (\(env.editedAtText(d)))", systemImage: "square.and.pencil").frame(minHeight: 44, alignment: .leading)
            }
            Button("Bỏ nháp", systemImage: "trash", role: .destructive) { discarding = true }.frame(minHeight: 44)
        } else {
            Button { env.editContent(of: template) } label: { Label("Sửa nội dung", systemImage: "square.and.pencil").frame(minHeight: 44, alignment: .leading) }
        }
    }

    private var summary: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 12) {
                IconBadge(systemImage: "doc.text.fill", size: 44)
                VStack(alignment: .leading, spacing: 4) {
                    Text(template.title).font(.headline).lineLimit(2).minimumScaleFactor(0.85)
                    if let s = template.subtitle { Text(s).font(.footnote).secondaryText().lineLimit(2) }
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
            IconBadge(systemImage: icon, size: 28)
            Text(title).font(.subheadline).lineLimit(1).minimumScaleFactor(0.92)
            Spacer(minLength: 8)
            Text(detail).font(.caption).secondaryText().monospacedDigit().fixedSize()
        }
    }
}

/// Name, short name and colour of a template: the short name and colour mark the cards of its records.
struct TemplateEditSheet: View {
    let template: Template
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var title = ""
    @State private var shortName = ""
    @State private var color = ""
    @State private var error: String?
    @State private var confirming = false
    @State private var saving = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Tên mẫu") { TextField("Tên mẫu", text: $title) }
                Section {
                    TextField(template.id, text: $shortName)
                        .onChange(of: shortName) { if shortName.count > 12 { shortName = String(shortName.prefix(12)) } }
                } header: { Text("Tên viết tắt") } footer: { Text("Hiện trên viền thẻ của các phiếu tạo từ mẫu này. Để trống thì dùng mã mẫu.") }
                Section("Màu phiếu") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 12) {
                        ForEach(Project.palette, id: \.self) { hex in
                            Circle().fill(Color(hex: hex) ?? .gray).frame(width: 36, height: 36)
                                .overlay { if hex == color { Image(systemName: "checkmark").font(.headline).foregroundStyle(.white) } }
                                .onTapGesture { color = hex }
                                .accessibilityAddTraits(hex == color ? [.isButton, .isSelected] : .isButton)
                                .accessibilityLabel("Màu \(hex)")
                        }
                    }
                    .padding(.vertical, 4)
                }
                Section { TemplateBadge(text: shortName.trimmingCharacters(in: .whitespaces).isEmpty ? template.id : shortName, hex: color) }
                    header: { Text("Xem trước") }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }
            .navigationTitle("Sửa mẫu").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Lưu") { if env.account == nil { save() } else { confirming = true } }
                        .disabled(saving || title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .confirmationDialog("Áp dụng cho mọi dự án dùng mẫu này", isPresented: $confirming, titleVisibility: .visible) {
                Button("Lưu", action: save)
                Button("Hủy", role: .cancel) {}
            } message: { Text("Tên, viết tắt và màu được lưu trên máy chủ cho cả tổ chức; máy khác thấy sau lần đồng bộ kế tiếp.") }
            .onAppear { title = template.title; shortName = template.shortName ?? ""; color = template.badgeColor }
        }
    }

    private func save() {
        saving = true; error = nil
        Task {
            defer { saving = false }
            do { try await env.editTemplate(template, title: title, shortName: shortName, color: color); dismiss() }
            catch is URLError { error = "Không kết nối được máy chủ ERP. Thử lại khi có mạng." }
            catch { self.error = TemplateText.errorLines(error).joined(separator: "\n") }
        }
    }
}

/// The template's short name on a pill in its colour (record cards, template edit preview).
struct TemplateBadge: View {
    let text: String
    let hex: String
    var body: some View {
        Text(text).font(.caption2.weight(.bold)).lineLimit(1).foregroundStyle(.white)
            .padding(.horizontal, 8).padding(.vertical, 3)
            .background(Color(hex: hex) ?? .gray, in: Capsule())
    }
}
