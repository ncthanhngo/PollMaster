import SwiftUI
import PollMasterCore

extension TemplateEditorModel {
    /// Option values the base version has for the field with this code (their codes stay, like the field's).
    func baseOptionValues(fieldId: String) -> Set<String> {
        guard let b = base?.root.object, !fieldId.isEmpty else { return [] }
        func opts(_ n: JSONValue) -> Set<String> { Set((n.object?["options"]?.array ?? []).compactMap { $0.object?["value"]?.string }) }
        var found: [JSONValue] = (b["header"]?.array ?? [])
        for s in b["sections"]?.array ?? [] { for i in s.object?["items"]?.array ?? [] { found.append(i); found += i.object?["columns"]?.array ?? [] } }
        return found.filter { $0.object?["id"]?.string == fieldId }.reduce(into: Set<String>()) { $0.formUnion(opts($1)) }
    }
}

/// Choices of a singleChoice / multiChoice field or column: label, value (made from the label until it is typed), "Khác", ↑ ↓, delete.
struct OptionsEditor: View {
    let model: TemplateEditorModel
    /// The field or column the options belong to.
    let fieldPath: DocPath
    private var listPath: DocPath { fieldPath + ["options"] }
    private var options: [JSONValue] { model.list(listPath) }

    var body: some View {
        Section {
            ForEach(options.indices, id: \.self) { i in OptionRow(model: model, fieldPath: fieldPath, index: i, count: options.count) }
            Button("Thêm lựa chọn", systemImage: "plus.circle.fill") { model.apply(.newItem(path: listPath, index: nil, kind: "option", type: nil)) }
                .frame(minHeight: 44)
        } header: { Text("Lựa chọn (\(options.count))") } footer: {
            Text("Giá trị lưu trong phiếu được tạo từ nhãn. Lựa chọn có ở bản gốc giữ nguyên giá trị.")
        }
    }
}

private struct OptionRow: View {
    let model: TemplateEditorModel
    let fieldPath: DocPath
    let index: Int
    let count: Int
    @State private var locked: Bool
    @Environment(\.dynamicTypeSize) private var size

    private var listPath: DocPath { fieldPath + ["options"] }
    private var path: DocPath { listPath + [.index(index)] }

    init(model: TemplateEditorModel, fieldPath: DocPath, index: Int, count: Int) {
        self.model = model; self.fieldPath = fieldPath; self.index = index; self.count = count
        let value = model.text(fieldPath + ["options", .index(index)], "value")
        _locked = State(initialValue: !model.isNew && model.baseOptionValues(fieldId: model.text(fieldPath, "id")).contains(value))
    }

    private var label: Binding<String> {
        Binding(get: { model.text(path, "label") }, set: { new in
            let old = model.text(path, "label"), value = model.text(path, "value")
            let others = Set(model.list(listPath).compactMap { $0.object?["value"]?.string }).subtracting([value])
            let auto = value.isEmpty || value == TemplateIds.optionValue(label: old, taken: others)
            if !locked, auto, !new.trimmingCharacters(in: .whitespaces).isEmpty {
                model.apply(.set(path: path, key: "label", value: .string(new)), .set(path: path, key: "value", value: .string(TemplateIds.optionValue(label: new, taken: others))))
            } else { model.set(path, "label", .string(new)) }
        })
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 0) {
                TextField("Nhãn", text: label)
                if !size.isAccessibilitySize { controls }
            }
            if size.isAccessibilitySize { HStack(spacing: 0) { controls; Spacer() } }
            if locked {
                Label(model.text(path, "value"), systemImage: "lock.fill").font(.caption.monospaced()).secondaryText()
            } else {
                HStack {
                    Text("Giá trị").font(.caption).secondaryText()
                    TextField("giá trị", text: model.str(path, "value")).font(.caption.monospaced()).textInputAutocapitalization(.never).autocorrectionDisabled()
                }
            }
            Toggle("Khác (ghi thêm)", isOn: model.flag(path, "withText")).font(.caption)
        }
        .padding(.vertical, 2)
    }

    private var controls: some View {
        HStack(spacing: 0) {
            btn("arrow.up", "Lên", index > 0) { model.move(listPath, index, by: -1) }
            btn("arrow.down", "Xuống", index < count - 1) { model.move(listPath, index, by: 1) }
            btn("trash", "Xoá lựa chọn", true) { model.apply(.delete(path: listPath, index: index)) }
        }
    }

    private func btn(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 40, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.borderless).disabled(!on).accessibilityLabel(label)
    }
}
