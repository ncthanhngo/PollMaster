import SwiftUI
import PollMasterCore

/// Table properties, its columns (each opens the field form) and, for fixed tables, the fixed rows.
struct TableForm: View {
    let model: TemplateEditorModel
    let path: DocPath
    @State private var deleting: Int?
    private var columnsPath: DocPath { path + ["columns"] }
    private var columns: [JSONValue] { model.list(columnsPath) }
    private var isFixed: Bool { model.object(path)["rows"] != nil }

    var body: some View {
        Form {
            Section("Bảng") {
                IdField(model: model, path: path)
                TextField("Tên bảng", text: model.str(path, "title"))
                TextField("Ghi chú (không bắt buộc)", text: model.str(path, "note"), axis: .vertical).lineLimit(1...4)
                Toggle("Dòng cố định (người điền không thêm dòng)", isOn: Binding(get: { isFixed }, set: { model.set(path, "rows", $0 ? .array([]) : nil) }))
            }
            if !isFixed {
                Section("Dòng lặp") {
                    TextField("Tên mỗi dòng (VD: Lần, Vị trí)", text: model.str(path, "rowLabel"))
                    NumField(title: "Số dòng tối thiểu", value: model.num(path, "minRows"))
                    Toggle("Bắt buộc có dòng", isOn: model.flag(path, "required"))
                    Toggle("Nhập nhanh (mỗi dòng một lần chạm)", isOn: model.flag(path, "quickEntry"))
                    Toggle("Nhập ngay trong danh sách", isOn: model.flag(path, "inline"))
                }
            }
            columnsSection
            if isFixed { FixedRowsSection(model: model, tablePath: path) }
            JSONLink(model: model, path: path)
        }
        .navigationTitle(model.text(path, "title").isEmpty ? "Bảng" : model.text(path, "title"))
        .confirmationDialog("Xoá cột này?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("Xoá cột", role: .destructive) { if let i = deleting { model.apply(.delete(path: columnsPath, index: i)) } }
        } message: {
            if let i = deleting, !model.references(afterDeleting: columnsPath, at: i).isEmpty { Text("Cột đang được dùng trong công thức hoặc điều kiện ở nơi khác.") }
            else { Text("Dữ liệu cột này trong các phiếu đã tạo vẫn được giữ.") }
        }
    }

    private var columnsSection: some View {
        Section {
            ForEach(columns.indices, id: \.self) { i in
                let o = columns[i].object ?? [:], p = columnsPath + [.index(i)]
                EditorRow(icon: EditorLabels.nodeIcon(o), code: o["id"]?.string, title: o["label"]?.string ?? "", subtitle: EditorLabels.nodeKind(o),
                          canUp: i > 0, canDown: i < columns.count - 1, issue: model.severity(under: p),
                          open: { model.path.append(.item(p)) }, up: { model.move(columnsPath, i, by: -1) }, down: { model.move(columnsPath, i, by: 1) }) {
                    Button("Sửa", systemImage: "pencil") { model.path.append(.item(p)) }
                    Button("Nhân bản", systemImage: "plus.square.on.square") { model.apply(.duplicate(path: columnsPath, index: i)) }
                    Button("Sửa JSON", systemImage: "curlybraces") { model.path.append(.json(p)) }
                    Button("Xoá", systemImage: "trash", role: .destructive) { deleting = i }
                }
            }
            Menu {
                ForEach(EditorLabels.fieldTypes.filter { !["computed", "signature", "sessionTime"].contains($0.type) }, id: \.type) { t in
                    Button(t.name) {
                        if model.apply(.newItem(path: columnsPath, index: nil, kind: "field", type: t.type)) { model.path.append(.item(columnsPath + [.index(columns.count - 1)])) }
                    }
                }
            } label: { Label("Thêm cột", systemImage: "plus.circle.fill").frame(minHeight: 44) }
        } header: { Text("Cột (\(columns.count))") }
    }
}

/// Fixed rows of a table: each holds the fixed value of every read-only column.
private struct FixedRowsSection: View {
    let model: TemplateEditorModel
    let tablePath: DocPath
    private var rowsPath: DocPath { tablePath + ["rows"] }
    private var rows: [JSONValue] { model.list(rowsPath) }
    private var readonlyColumns: [JSONValue] { model.list(tablePath + ["columns"]).filter { $0.object?["readonly"]?.bool == true } }

    var body: some View {
        Section {
            ForEach(rows.indices, id: \.self) { i in FixedRowView(model: model, rowsPath: rowsPath, index: i, count: rows.count, columns: readonlyColumns) }
            Button("Thêm dòng cố định", systemImage: "plus.circle.fill") { model.apply(.newItem(path: rowsPath, index: nil, kind: "row", type: nil)) }.frame(minHeight: 44)
        } header: { Text("Dòng cố định (\(rows.count))") } footer: {
            if readonlyColumns.isEmpty { Text("Đặt cột là \"Chỉ đọc\" để nhập giá trị cố định cho từng dòng.") }
        }
    }
}

private struct FixedRowView: View {
    let model: TemplateEditorModel
    let rowsPath: DocPath
    let index: Int
    let count: Int
    let columns: [JSONValue]
    @State private var open = false
    private var path: DocPath { rowsPath + [.index(index)] }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 0) {
                Button { open.toggle() } label: {
                    HStack {
                        Image(systemName: open ? "chevron.down" : "chevron.right").font(.caption.weight(.bold)).frame(width: 18)
                        Text("Dòng \(index + 1)").font(.subheadline.weight(.medium))
                        Text(model.text(path, "id")).font(.caption.monospaced()).secondaryText()
                        Spacer(minLength: 0)
                    }.frame(minHeight: 44).contentShape(Rectangle())
                }.buttonStyle(.plain)
                btn("arrow.up", "Lên", index > 0) { model.move(rowsPath, index, by: -1) }
                btn("arrow.down", "Xuống", index < count - 1) { model.move(rowsPath, index, by: 1) }
                btn("trash", "Xoá dòng", true) { model.apply(.delete(path: rowsPath, index: index)) }
            }
            if open {
                ForEach(columns.indices, id: \.self) { k in
                    let c = columns[k].object ?? [:], cid = c["id"]?.string ?? ""
                    TextField(c["label"]?.string ?? cid, text: value(cid, numeric: ["number", "integer"].contains(c["type"]?.string ?? "")))
                }
            }
        }
    }

    private func value(_ column: String, numeric: Bool) -> Binding<String> {
        Binding(get: {
            let v = model.object(path)["fixed"]?.object?[column]
            return v?.string ?? v?.double.map { NumField.format($0) } ?? ""
        }, set: { new in
            if model.object(path)["fixed"] == nil { model.set(path, "fixed", .object([:])) }
            let s = new.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: ".")
            model.set(path + ["fixed"], column, numeric ? (Double(s).flatMap { NumField.format($0) == s ? JSONValue.number($0) : nil } ?? .string(new)) : .string(new))
        })
    }

    private func btn(_ icon: String, _ label: String, _ on: Bool, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: icon).frame(width: 40, height: 44).contentShape(Rectangle()) }
            .buttonStyle(.borderless).disabled(!on).accessibilityLabel(label)
    }
}
