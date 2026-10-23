import SwiftUI
import PollMasterCore

/// A table inside a section: row cards + add button.
struct TableSectionView: View {
    let model: RecordEditorModel
    let table: TableDef
    @Environment(AppEnvironment.self) private var env
    @State private var deleting: Row?

    private var rows: [Row] { model.record.rows(of: table) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(table.title).font(.headline).padding(.top, 8)
            if let n = table.note { Text(n).font(.footnote).foregroundStyle(.secondary) }
            ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                RowCard(model: model, table: table, row: row, index: i, onDelete: { deleting = row })
            }
            if !table.isFixed && !model.isLocked {
                Button {
                    let id = model.addRow(table.id)
                    env.recordsPath.append(.row(record: model.record.id, table: table.id, row: id))
                } label: { Label("Thêm dòng", systemImage: "plus.circle.fill").frame(maxWidth: .infinity) }
                    .buttonStyle(.borderedProminent)
            }
            ForEach(model.issues.filter { $0.fieldId == table.id && $0.rowId == nil }) { issue in
                if model.showAllErrors { Label(issue.message, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(.red) }
            }
        }
        .confirmationDialog("Xóa dòng này?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("Xóa", role: .destructive) { if let r = deleting { model.deleteRow(table.id, r.id) }; deleting = nil }
        }
    }
}

private struct RowCard: View {
    let model: RecordEditorModel
    let table: TableDef
    let row: Row
    let index: Int
    let onDelete: () -> Void

    var body: some View {
        let s = TableRowSummary.make(table: table, row: row, index: index)
        HStack(alignment: .top) {
            NavigationLink(value: Route.row(record: model.record.id, table: table.id, row: row.id)) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(s.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Text(s.detail.isEmpty ? "Chưa nhập" : s.detail).font(.caption).foregroundStyle(.secondary).lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            if !table.isFixed && !model.isLocked {
                Menu {
                    Button("Nhân bản", systemImage: "plus.square.on.square") { model.duplicateRow(table.id, row.id) }
                    Button("Xóa", systemImage: "trash", role: .destructive, action: onDelete)
                } label: { Image(systemName: "ellipsis.circle").font(.title3) }
            }
        }
        .padding(12)
        .background(Color(.secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 12))
    }
}

enum TableRowSummary {
    /// Title ("Lượt 3" or fixed-row labels) and up to 3 filled columns as detail.
    static func make(table: TableDef, row: Row, index: Int) -> (title: String, detail: String) {
        let fixed = table.rows?.first { $0.id == row.id }?.fixed ?? [:]
        func fixedText(_ c: Field) -> String? {
            guard let v = fixed[c.id] else { return nil }
            return v.string ?? v.double.map { ValueFormatter.number($0) }
        }
        let title: String
        if table.isFixed {
            title = table.columns.filter(\.readonly).compactMap(fixedText).joined(separator: " · ").nilIfBlank ?? row.id
        } else {
            title = "\(table.rowLabel ?? "Dòng") \(index + 1)"
        }
        let detail = table.columns.filter { !$0.readonly }.compactMap { c -> String? in
            let t = ValueFormatter.display(c, row.values[c.id])
            return t.isEmpty ? nil : "\(c.label): \(t)"
        }.prefix(3).joined(separator: " · ")
        return (title, detail)
    }
}

extension String {
    var nilIfBlank: String? { isEmpty ? nil : self }
}
