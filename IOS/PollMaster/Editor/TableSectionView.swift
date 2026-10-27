import SwiftUI
import PollMasterCore

/// A table inside a section: row cards + add button (or "Ghi lượt mới" for quick-entry tables).
struct TableSectionView: View {
    @Environment(\.pmTheme) private var theme
    let model: RecordEditorModel
    let table: TableDef
    let sectionId: String
    @State private var deleting: Row?

    private var rows: [Row] { model.record.rows(of: table) }
    private var canAdd: Bool { !table.isFixed && !model.isLocked }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(table.title).font(.headline).padding(.top, 8)
            if let n = table.note { Text(n).font(.footnote).secondaryText() }
            if canAdd { AddRowButton(model: model, table: table, sectionId: sectionId) }
            if rows.isEmpty {
                Text(table.isFixed ? "Bảng chưa có dòng nào." : "Chưa có \(table.itemName) nào — bấm \(table.addTitle)")
                    .font(.subheadline).secondaryText().frame(maxWidth: .infinity).padding(.vertical, 14)
                    .cardBox()
            }
            if let layout = table.inlineLayout {
                InlineTableView(model: model, table: table, layout: layout)
            } else {
                ForEach(Array(rows.enumerated()), id: \.element.id) { i, row in
                    RowCard(model: model, table: table, row: row, index: i, sectionId: sectionId, onDelete: { deleting = row })
                }
            }
            ForEach(model.issues.filter { $0.fieldId == table.id && $0.rowId == nil }) { issue in
                if model.showAllErrors { Label(issue.message, systemImage: "exclamationmark.circle").font(.caption).foregroundStyle(theme.alert) }
            }
        }
        .confirmationDialog(table.pairedWith == nil ? "Xóa dòng này?" : "Xóa lượt này ở cả hai bảng?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                            titleVisibility: .visible) {
            Button("Xóa", role: .destructive) { if let r = deleting { model.deleteRow(table.id, r.id) }; deleting = nil }
        }
    }

}

extension TableDef {
    /// Lower-case row noun: "lượt", "người", "dòng".
    var itemName: String { quickEntry ? (rowLabel ?? "Lượt").lowercased() : "dòng" }
    /// Text of the add button: "Ghi lượt mới" (quick entry) or "Thêm dòng".
    var addTitle: String { quickEntry ? "Ghi \(itemName) mới" : "Thêm dòng" }
}

/// Adds a row and opens its form.
struct AddRowButton: View {
    let model: RecordEditorModel
    let table: TableDef
    let sectionId: String
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme

    var body: some View {
        Button {
            let id = model.addRow(table.id)
            model.setPosition(section: sectionId, field: table.id)
            env.recordsPath.append(.row(record: model.record.id, table: table.id, row: id))
        } label: {
            Label(table.addTitle, systemImage: table.quickEntry ? "stopwatch" : "plus.circle.fill")
                .frame(maxWidth: .infinity, minHeight: max(table.quickEntry ? 36 : 24, theme.minTouch))
        }
        .buttonStyle(.borderedProminent)
    }
}

private struct RowCard: View {
    let model: RecordEditorModel
    let table: TableDef
    let row: Row
    let index: Int
    let sectionId: String
    let onDelete: () -> Void
    @Environment(\.pmTheme) private var theme

    var body: some View {
        let partner = model.template.pairedTable(of: table).flatMap { p in model.rows(p.id).first { $0.id == row.id }.map { (p, $0) } }
        let s = TableRowSummary.make(table: table, row: row, index: index, partner: partner)
        HStack(alignment: .top) {
            NavigationLink(value: Route.row(record: model.record.id, table: table.id, row: row.id)) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(s.title).font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                    Text(s.detail.isEmpty ? "Chưa nhập" : s.detail).font(.caption).secondaryText().lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .simultaneousGesture(TapGesture().onEnded { model.setPosition(section: sectionId, field: table.id) })
            if !table.isFixed && !model.isLocked {
                Menu {
                    Button("Nhân bản", systemImage: "plus.square.on.square") { model.duplicateRow(table.id, row.id) }
                    Button("Xóa", systemImage: "trash", role: .destructive, action: onDelete)
                } label: { Image(systemName: "ellipsis.circle").font(.title3).frame(minWidth: 28, minHeight: 28).hitSlop(8) }
            }
        }
        .cardBox()
    }
}

enum TableRowSummary {
    /// Title ("Lượt 3" or fixed-row labels) and the first filled columns as detail (paired tables show both sides).
    static func make(table: TableDef, row: Row, index: Int, partner: (TableDef, Row)? = nil) -> (title: String, detail: String) {
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
        var cells: [(Field, FieldValue?)] = table.columns.filter { !$0.readonly }.map { ($0, row.values[$0.id]) }
        if let (pt, pr) = partner {
            let have = Set(cells.map(\.0.id))
            cells += pt.columns.filter { !$0.readonly && !have.contains($0.id) }.map { ($0, pr.values[$0.id]) }
        }
        let detail = cells.compactMap { c, v -> String? in
            let t = ValueFormatter.display(c, v)
            return t.isEmpty ? nil : "\(c.label): \(t)"
        }.prefix(partner == nil ? 3 : 4).joined(separator: " · ")
        return (title, detail)
    }
}

extension String {
    var nilIfBlank: String? { isEmpty ? nil : self }
}
