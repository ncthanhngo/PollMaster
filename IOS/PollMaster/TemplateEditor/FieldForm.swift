import SwiftUI
import PollMasterCore

/// Opens the form that matches the node at `path`: heading, table, or field / column.
struct ItemFormView: View {
    let model: TemplateEditorModel
    let path: DocPath

    var body: some View {
        Group {
            if let o = model.node(path)?.object {
                switch o["kind"]?.string {
                case "heading": HeadingForm(model: model, path: path)
                case "table": TableForm(model: model, path: path)
                default: FieldForm(model: model, path: path)
                }
            } else {
                ContentUnavailableView("Phần tử này không còn", systemImage: "questionmark.square.dashed")
            }
        }
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct HeadingForm: View {
    let model: TemplateEditorModel
    let path: DocPath

    var body: some View {
        Form {
            Section("Tiêu đề nhóm") {
                IdField(model: model, path: path)
                CaptionedField(caption: "Tiêu đề", text: model.str(path, "title"))
                CaptionedField(caption: "Ghi chú (không bắt buộc)", text: model.str(path, "note"), lines: 1...4)
            }
            JSONLink(model: model, path: path)
        }
        .navigationTitle("Tiêu đề nhóm")
    }
}

/// A text box with its name above it: a placeholder alone disappears once the box is filled.
struct CaptionedField: View {
    let caption: String
    let text: Binding<String>
    var lines: ClosedRange<Int> = 1...1

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(caption).font(.caption).foregroundStyle(.secondary)
            TextField(caption, text: text, axis: .vertical).lineLimit(lines)
        }
    }
}

/// "Sửa JSON" row: what the form does not cover (formulas, conditions, stopwatch…) is edited there.
struct JSONLink: View {
    let model: TemplateEditorModel
    let path: DocPath
    var title = "Sửa JSON nâng cao"

    var body: some View {
        Section {
            Button(title, systemImage: "curlybraces") { model.path.append(.json(path)) }.frame(minHeight: 44)
        } footer: { Text("Công thức, điều kiện hiện, tra cứu, các phần… chỉ sửa được ở đây.") }
    }
}

/// A field of the header or of a section, or a column of a table (`path` ends in `columns[k]`).
struct FieldForm: View {
    let model: TemplateEditorModel
    let path: DocPath
    var isColumn: Bool { if case .key("columns")? = path.dropLast().last { return true }; return false }
    var isHeader: Bool { path.first == .key("header") }
    var type: String { model.text(path, "type").isEmpty ? "text" : model.text(path, "type") }

    private var typeChoices: [(type: String, name: String)] {
        EditorLabels.fieldTypes.filter { t in
            t.type == type || (t.type != "computed" && t.type != "signature" && !(isColumn && t.type == "sessionTime"))
        }
    }

    var body: some View {
        Form {
            Section(isColumn ? "Cột" : "Trường") {
                IdField(model: model, path: path)
                CaptionedField(caption: "Nhãn", text: model.str(path, "label"), lines: 1...3)
                Picker("Kiểu", selection: Binding(get: { type }, set: { model.set(path, "type", .string($0)) })) {
                    ForEach(typeChoices, id: \.type) { Text($0.name).tag($0.type) }
                }
                Toggle("Bắt buộc", isOn: model.flag(path, "required"))
                Toggle("Chỉ đọc", isOn: model.flag(path, "readonly"))
            }
            if isColumn { Section { Toggle("Dòng mới lấy giá trị dòng trước", isOn: model.flag(path, "carryOver")) } }
            if ["number", "integer", "computed"].contains(type) || type == "text" { unitSection }
            if type == "number" || type == "integer" { numberSection }
            if choice { OptionsEditor(model: model, fieldPath: path) }
            typeSection
            Section("Gợi ý cho người điền") {
                CaptionedField(caption: "Hướng dẫn (hiện dưới ô)", text: model.str(path, "hint"), lines: 1...4)
                CaptionedField(caption: "Phương pháp đo", text: model.str(path, "method"), lines: 1...3)
            }
            if isHeader && ["text", "multiline"].contains(type) && ["recordCode", nil].contains(model.object(path)["role"]?.string) {
                Section { Toggle("Mã phiếu (dùng đặt tên phiếu)", isOn: Binding(
                    get: { model.object(path)["role"]?.string == "recordCode" }, set: { model.set(path, "role", $0 ? .string("recordCode") : nil) })) }
            }
            JSONLink(model: model, path: path)
        }
        .navigationTitle(model.text(path, "label").isEmpty ? "Trường" : model.text(path, "label"))
    }

    private var choice: Bool { type == "singleChoice" || type == "multiChoice" }

    private var unitSection: some View {
        Section("Đơn vị") {
            TextField("Đơn vị (mm, kg, …)", text: model.str(path, "unit"))
        }
    }

    private var numberSection: some View {
        Section("Giới hạn và cảnh báo") {
            NumField(title: "Nhỏ nhất", value: model.num(path, "min"))
            NumField(title: "Lớn nhất", value: model.num(path, "max"))
            NumField(title: "Cảnh báo dưới", value: model.num(path, "warnMin"))
            NumField(title: "Cảnh báo trên", value: model.num(path, "warnMax"))
            if type == "number" { NumField(title: "Số chữ số thập phân", value: model.num(path, "decimals")) }
            NumField(title: "Số lần đo (samples)", value: model.num(path, "samples"))
            if model.object(path)["samples"] != nil { Toggle("Đánh dấu lần đo ngoài khoảng", isOn: Binding(
                get: { model.object(path)["flags"]?.bool ?? false }, set: { model.set(path, "flags", $0 ? .bool(true) : nil) })) }
            if type == "integer" { Toggle("Nút +/− thay vì gõ", isOn: model.flag(path, "stepper")) }
        }
    }
}
