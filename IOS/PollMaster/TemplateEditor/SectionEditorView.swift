import SwiftUI
import PollMasterCore

/// One section: its code, title and note, then its items.
struct SectionEditorView: View {
    let model: TemplateEditorModel
    let index: Int
    private var path: DocPath { ["sections", .index(index)] }

    var body: some View {
        List {
            if model.node(path) == nil {
                ContentUnavailableView("Mục này không còn", systemImage: "questionmark.folder")
            } else {
                Section("Thông tin mục") {
                    IdField(model: model, path: path)
                    TextField("Tên mục", text: model.str(path, "title"))
                    TextField("Ghi chú (không bắt buộc)", text: model.str(path, "note"), axis: .vertical).lineLimit(1...4)
                }
                ItemListSections(model: model, listPath: path + ["items"], sectionIndex: index)
            }
        }
        .navigationTitle(model.text(path, "title").isEmpty ? "Mục" : model.text(path, "title"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// The header fields of the record.
struct HeaderEditorView: View {
    let model: TemplateEditorModel
    var body: some View {
        List { ItemListSections(model: model, listPath: ["header"], sectionIndex: nil) }
            .navigationTitle("Đầu biên bản").navigationBarTitleDisplayMode(.inline)
    }
}

private struct PendingDelete: Identifiable {
    let id = UUID()
    let index: Int
    let name: String
    let refs: [TemplateIssue]
}

/// Rows of `listPath` (header or a section's items) with ↑ ↓, the ⋯ menu and "+ Thêm".
struct ItemListSections: View {
    let model: TemplateEditorModel
    let listPath: DocPath
    /// nil for the header (it has no "move to section").
    let sectionIndex: Int?
    @State private var deleting: PendingDelete?
    @State private var moving: Int?

    private var items: [JSONValue] { model.list(listPath) }

    var body: some View {
        Section {
            if items.isEmpty { Text("Chưa có phần tử nào.").font(.footnote).secondaryText() }
            ForEach(items.indices, id: \.self) { i in row(i) }
            addMenu
        } header: { Text("\(sectionIndex == nil ? "Trường" : "Nội dung") (\(items.count))") }
        .confirmationDialog(deleting.map { "Xoá \"\($0.name)\"?" } ?? "", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible, presenting: deleting) { d in
            Button("Xoá", role: .destructive) { model.apply(.delete(path: listPath, index: d.index)) }
        } message: { d in
            if d.refs.isEmpty { Text("Phiếu đã tạo giữ nguyên dữ liệu.") }
            else { Text("Đang được dùng ở:\n" + d.refs.prefix(6).map { "• " + model.place(of: $0.path) }.joined(separator: "\n") + (d.refs.count > 6 ? "\n… và \(d.refs.count - 6) nơi nữa" : "")) }
        }
        .confirmationDialog("Chuyển sang mục…", isPresented: Binding(get: { moving != nil }, set: { if !$0 { moving = nil } }), titleVisibility: .visible) {
            let sections = model.list(["sections"])
            ForEach(sections.indices.filter { $0 != sectionIndex }, id: \.self) { s in
                Button("\(sections[s].object?["id"]?.string ?? "") · \(sections[s].object?["title"]?.string ?? "")") {
                    if let from = sectionIndex, let i = moving { model.apply(.moveItem(fromSection: from, index: i, toSection: s)) }
                }
            }
        }
    }

    private func row(_ i: Int) -> some View {
        let o = items[i].object ?? [:], p = listPath + [.index(i)]
        let name = o["label"]?.string ?? o["title"]?.string ?? ""
        return EditorRow(icon: EditorLabels.nodeIcon(o), code: o["id"]?.string, title: name, subtitle: EditorLabels.nodeKind(o),
                         canUp: i > 0, canDown: i < items.count - 1, issue: model.severity(under: p),
                         open: { model.path.append(.item(p)) }, up: { model.move(listPath, i, by: -1) }, down: { model.move(listPath, i, by: 1) }) {
            Button("Sửa", systemImage: "pencil") { model.path.append(.item(p)) }
            Button("Nhân bản", systemImage: "plus.square.on.square") { model.apply(.duplicate(path: listPath, index: i)) }
            if sectionIndex != nil, model.list(["sections"]).count > 1 { Button("Chuyển sang mục…", systemImage: "arrow.right.square") { moving = i } }
            Button("Sửa JSON", systemImage: "curlybraces") { model.path.append(.json(p)) }
            Button("Xoá", systemImage: "trash", role: .destructive) { deleting = PendingDelete(index: i, name: name, refs: model.references(afterDeleting: listPath, at: i)) }
        }
    }

    private var addMenu: some View {
        Menu {
            Menu("Trường", systemImage: "textformat") {
                ForEach(EditorLabels.fieldTypes.filter { $0.type != "computed" && $0.type != "signature" }, id: \.type) { t in
                    Button(t.name) { add(kind: "field", type: t.type) }
                }
            }
            if sectionIndex != nil {
                Button("Tiêu đề nhóm", systemImage: "text.alignleft") { add(kind: "heading", type: nil) }
                Button("Bảng", systemImage: "tablecells") { add(kind: "table", type: nil) }
            }
        } label: { Label("Thêm", systemImage: "plus.circle.fill").frame(minHeight: 44) }
    }

    private func add(kind: String, type: String?) {
        guard model.apply(.newItem(path: listPath, index: nil, kind: kind, type: type)) else { return }
        model.path.append(.item(listPath + [.index(items.count - 1)]))
    }
}
