import SwiftUI
import PollMasterCore

/// Name, short name, colour, subtitle, instructions and (new templates) the code.
struct TemplateInfoForm: View {
    let model: TemplateEditorModel
    private let root: DocPath = []

    var body: some View {
        Form {
            Section("Tên mẫu") {
                TextField("Tên mẫu", text: model.str(root, "title"))
                TextField("Phụ đề (không bắt buộc)", text: model.str(root, "subtitle"), axis: .vertical).lineLimit(1...3)
            }
            Section {
                TextField(model.text(root, "id"), text: Binding(get: { model.text(root, "shortName") },
                                                                  set: { model.set(root, "shortName", .string(String($0.prefix(12)))) }))
            } header: { Text("Tên viết tắt") } footer: { Text("Hiện trên viền thẻ của các phiếu. Để trống thì dùng mã mẫu.") }
            Section("Màu phiếu") {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 5), spacing: 12) {
                    ForEach(Project.palette, id: \.self) { hex in
                        let on = model.text(root, "color").uppercased() == hex.uppercased()
                        Circle().fill(Color(hex: hex) ?? .gray).frame(width: 40, height: 40)
                            .overlay { if on { Image(systemName: "checkmark").font(.headline).foregroundStyle(.white) } }
                            .frame(minWidth: 44, minHeight: 44).contentShape(Rectangle())
                            .onTapGesture { model.set(root, "color", .string(hex)) }
                            .accessibilityAddTraits(on ? [.isButton, .isSelected] : .isButton).accessibilityLabel("Màu \(hex)")
                    }
                }
            }
            Section { TemplateBadge(text: model.text(root, "shortName").isEmpty ? model.text(root, "id") : model.text(root, "shortName"),
                                    hex: model.text(root, "color")) } header: { Text("Xem trước thẻ") }
            Section {
                TextField("Hướng dẫn cho người điền", text: model.str(root, "instructions"), axis: .vertical).lineLimit(3...12)
            } header: { Text("Hướng dẫn") } footer: { Text("Hiện ở nút \"Hướng dẫn mẫu\" trong phiếu.") }
            codeSection
            JSONLink(model: model, path: root, title: "Sửa JSON cả mẫu")
        }
        .navigationTitle("Thông tin mẫu").navigationBarTitleDisplayMode(.inline)
    }

    @ViewBuilder private var codeSection: some View {
        Section {
            if model.isNew {
                LabeledContent("Mã mẫu") {
                    TextField("MA-MAU", text: Binding(get: { model.text(root, "id") }, set: { model.set(root, "id", .string($0.uppercased())) }))
                        .multilineTextAlignment(.trailing).font(.body.monospaced()).textInputAutocapitalization(.characters).autocorrectionDisabled()
                }
            } else {
                LabeledContent("Mã mẫu") { LockedValue(text: model.draft.templateId) }
            }
            LabeledContent("Phiên bản", value: model.isNew ? "1.0 khi phát hành" : "v\(model.draft.baseVersion ?? "") → bản mới khi phát hành")
        } header: { Text("Mã và phiên bản") } footer: {
            Text(model.isNew ? "Chữ in hoa, số và dấu gạch ngang. Đổi được cho tới lần phát hành đầu tiên." : "Mã mẫu không đổi khi sửa. Phiên bản mới do máy cấp lúc phát hành.")
        }
    }
}

/// Signature boxes at the end of the record.
struct SignaturesView: View {
    let model: TemplateEditorModel
    private let list: DocPath = ["signatures"]
    private var items: [JSONValue] { model.list(list) }

    var body: some View {
        List {
            Section {
                if items.isEmpty { Text("Mẫu chưa có ô chữ ký.").font(.footnote).secondaryText() }
                ForEach(items.indices, id: \.self) { i in
                    let p = list + [.index(i)]
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 0) {
                            TextField("Tên ô chữ ký", text: model.str(p, "label"))
                            arrow("arrow.up", "Lên", i > 0) { model.move(list, i, by: -1) }
                            arrow("arrow.down", "Xuống", i < items.count - 1) { model.move(list, i, by: 1) }
                            arrow("trash", "Xoá", true) { model.apply(.delete(path: list, index: i)) }
                        }
                        Text(model.text(p, "id")).font(.caption.monospaced()).secondaryText()
                    }
                }
                Button("Thêm ô chữ ký", systemImage: "plus.circle.fill") { model.apply(.newItem(path: list, index: nil, kind: "signature", type: nil)) }.frame(minHeight: 44)
            }
        }
        .navigationTitle("Chữ ký").navigationBarTitleDisplayMode(.inline)
    }

    private func arrow(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 40, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.borderless).disabled(!on).accessibilityLabel(label)
    }
}
