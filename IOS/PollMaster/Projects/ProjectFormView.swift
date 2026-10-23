import SwiftUI
import PollMasterCore

/// Create (project == nil) or edit a project. The code follows the name until the user edits it.
struct ProjectFormView: View {
    let project: Project?
    var onSaved: (Project) -> Void = { _ in }
    @Environment(AppEnvironment.self) private var env
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var code = ""
    @State private var codeEdited = false
    @State private var client = ""
    @State private var details = ""
    @State private var color = Project.palette[0]

    private var taken: Set<String> { env.projectStore.codes(excluding: project?.id) }
    private var codeProblem: String? {
        let c = code.trimmingCharacters(in: .whitespaces).uppercased()
        if !ProjectCode.isValid(c) { return "Mã chỉ gồm A-Z, 0-9 và dấu -, tối đa \(ProjectCode.maxLength) ký tự." }
        if taken.contains(where: { $0.uppercased() == c }) { return "Mã \(c) đã có dự án khác dùng." }
        return nil
    }
    private var canSave: Bool { !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && codeProblem == nil }

    var body: some View {
        NavigationStack {
            Form {
                Section("Tên dự án") {
                    TextField("Tên dự án (bắt buộc)", text: $name)
                        .onChange(of: name) { _, v in if !codeEdited { code = ProjectCode.generate(from: v, taken: taken) } }
                }
                Section {
                    TextField("Mã dự án", text: $code)
                        .textInputAutocapitalization(.characters).autocorrectionDisabled()
                        .font(.body.monospaced())
                        .onChange(of: code) { old, new in
                            let upper = new.uppercased()
                            if upper != new { code = upper }
                            if old != new && new != ProjectCode.generate(from: name, taken: taken) { codeEdited = true }
                        }
                } header: { Text("Mã") } footer: {
                    if let p = codeProblem, !code.isEmpty { Text(p).foregroundStyle(.red) }
                    else { Text("Tự sinh từ tên, dùng trong tên tệp xuất và thư mục sao lưu.") }
                }
                Section("Thông tin thêm") {
                    TextField("Khách hàng", text: $client)
                    TextField("Mô tả", text: $details, axis: .vertical).lineLimit(2...5)
                }
                Section("Màu nhận diện") {
                    HStack(spacing: 14) {
                        ForEach(Project.palette, id: \.self) { hex in
                            Circle().fill(Color(hex: hex) ?? .gray).frame(width: 34, height: 34)
                                .overlay { if color == hex { Image(systemName: "checkmark").font(.footnote.bold()).foregroundStyle(.white) } }
                                .onTapGesture { color = hex }
                                .accessibilityLabel("Màu \(hex)").accessibilityAddTraits(color == hex ? .isSelected : [])
                        }
                    }
                }
            }
            .navigationTitle(project == nil ? "Dự án mới" : "Sửa dự án")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Hủy") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("Lưu") { save() }.disabled(!canSave) }
            }
            .onAppear(perform: load)
        }
    }

    private func load() {
        guard let p = project else { color = Project.palette[env.projects.count % Project.palette.count]; return }
        name = p.name; code = p.code; codeEdited = true; client = p.client ?? ""; details = p.description ?? ""; color = p.color ?? color
    }

    private func save() {
        var p = project ?? Project(code: code, name: name)
        p.name = name; p.code = code; p.client = client; p.description = details; p.color = color
        guard let saved = env.saveProject(p, isNew: project == nil) else { return }
        onSaved(saved)
        dismiss()
    }
}
