import SwiftUI
import PhotosUI
import PollMasterCore

/// Create (project == nil) or edit a project. The code follows the name until the user edits it.
struct ProjectFormView: View {
    @Environment(\.pmTheme) private var theme
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
    @State private var photoItem: PhotosPickerItem?
    @State private var photo: UIImage?
    @State private var dropPhoto = false

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
                Section { avatarPicker }.listRowBackground(Color.clear)
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
                    if let p = codeProblem, !code.isEmpty { Text(p).foregroundStyle(theme.alert) }
                    else { Text("Tự sinh từ tên, dùng trong tên tệp xuất và thư mục sao lưu.") }
                }
                Section("Thông tin thêm") {
                    TextField("Khách hàng", text: $client)
                    TextField("Mô tả", text: $details, axis: .vertical).lineLimit(2...5)
                }
                Section("Màu nhận diện") {
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12), count: 5), spacing: 12) {
                        ForEach(Project.palette, id: \.self) { hex in
                            Circle().fill(Color(hex: hex) ?? .gray).frame(width: 40, height: 40)
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

    private var hasPhoto: Bool { photo != nil || (!dropPhoto && project.map { env.projectAvatars[$0.id] != nil } == true) }

    /// Big avatar preview (photo, else initials on the chosen colour) with "Chọn ảnh" / "Bỏ ảnh".
    private var avatarPicker: some View {
        VStack(spacing: 12) {
            Group {
                if let photo { Image(uiImage: photo).resizable().scaledToFill() }
                else if hasPhoto, let p = project, let img = env.projectAvatars[p.id] { Image(uiImage: img).resizable().scaledToFill() }
                else {
                    Group {
                        if name.trimmingCharacters(in: .whitespaces).isEmpty { Image(systemName: "folder.fill").font(.system(size: 34)) }
                        else { Text(Project.initials(of: name)).font(.system(size: 34, weight: .bold, design: .rounded)) }
                    }
                    .foregroundStyle(.white)
                    .frame(maxWidth: .infinity, maxHeight: .infinity).background(Color(hex: color) ?? .gray)
                }
            }
            .frame(width: 88, height: 88)
            .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
            HStack(spacing: 10) {
                PhotosPicker(selection: $photoItem, matching: .images) {
                    Label(hasPhoto ? "Đổi ảnh" : "Chọn ảnh", systemImage: "photo")
                }
                .buttonStyle(.bordered)
                if hasPhoto {
                    Button("Bỏ ảnh", systemImage: "xmark", role: .destructive) { photo = nil; photoItem = nil; dropPhoto = true }
                        .buttonStyle(.bordered)
                }
            }
            .font(.subheadline.weight(.semibold))
        }
        .frame(maxWidth: .infinity)
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            Task {
                if let data = try? await item.loadTransferable(type: Data.self), let img = UIImage(data: data) { photo = img; dropPhoto = false }
            }
        }
    }

    private func load() {
        guard let p = project else { color = Project.nextColor(used: env.projects.compactMap(\.color)); return }
        name = p.name; code = p.code; codeEdited = true; client = p.client ?? ""; details = p.description ?? ""; color = p.color ?? color
    }

    private func save() {
        var p = project ?? Project(code: code, name: name)
        p.name = name; p.code = code; p.client = client; p.description = details; p.color = color
        guard let saved = env.saveProject(p, isNew: project == nil) else { return }
        if let photo { env.setAvatar(photo, for: saved) } else if dropPhoto { env.setAvatar(nil, for: saved) }
        onSaved(saved)
        dismiss()
    }
}
