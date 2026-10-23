import SwiftUI
import PollMasterCore

/// Form for one table row: optional stopwatch, then a card per column ("Ghi lượt" for quick-entry tables).
struct RowFormView: View {
    let model: RecordEditorModel
    let tableId: String
    let rowId: String
    @FocusState private var focused: String?
    @Environment(\.pmTheme) private var theme

    private var table: TableDef? { model.template.table(tableId) }
    private var rowIndex: Int { model.rows(tableId).firstIndex { $0.id == rowId } ?? 0 }

    var body: some View {
        if let table, let row = model.rows(tableId).first(where: { $0.id == rowId }) {
            if table.quickEntry {
                QuickEntryView(model: model, table: table, rowId: rowId)
            } else {
                form(table, row)
            }
        } else {
            ContentUnavailableView("Không tìm thấy dòng", systemImage: "questionmark.folder")
        }
    }

    private func form(_ table: TableDef, _ row: Row) -> some View {
        let summary = TableRowSummary.make(table: table, row: row, index: rowIndex)
        let editable = table.columns.filter { !$0.readonly }
        return ScrollView {
            VStack(alignment: .leading, spacing: 8) {
                if model.isLocked { LockedBanner(model: model) }
                if let sw = table.stopwatch {
                    StopwatchBlock(model: model, table: table, row: row, columns: sw.columns)
                }
                ForEach(table.columns) { col in
                    if col.readonly, let fixed = fixedText(table, col) {
                        FixedValueCard(field: col, text: fixed)
                    } else {
                        FieldCard(field: col, fv: model.rowBinding(table: tableId, row: rowId, column: col.id),
                                  scope: model.scope(table: table, row: row, focus: $focused), showCode: false)
                    }
                }
            }
            .padding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(theme.screenBackground)
        .navigationTitle(summary.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Button("Tiếp") { advance(table, row) }
                if let f = focused, FocusOrder.isSigned(key: f, fields: editable) {
                    Button("±") { NotificationCenter.default.post(name: .pmToggleSign, object: nil) }
                }
                Spacer()
                Button("Xong") { focused = nil }
            }
        }
        .modifier(WarnOnLeave(model: model, focused: $focused) { key in
            guard let f = editable.first(where: { $0.id == FocusOrder.baseId(key) }) else { return nil }
            return (f, row.values[f.id], "\(tableId)/\(rowId)/\(f.id)")
        })
        .onDisappear { model.flush(); Task { await model.backup() } }
    }

    private func fixedText(_ table: TableDef, _ col: Field) -> String? {
        guard let v = table.rows?.first(where: { $0.id == rowId })?.fixed[col.id] else { return nil }
        return v.string ?? v.double.map { ValueFormatter.number($0) }
    }

    private func advance(_ table: TableDef, _ row: Row) {
        let cols = table.columns.filter { !$0.readonly }
        let keys = FocusOrder.keys(for: cols, lookup: { row.values[$0] })
        guard let cur = focused, let i = keys.firstIndex(of: cur), i + 1 < keys.count else { focused = nil; return }
        focused = keys[i + 1]
    }
}

struct FixedValueCard: View {
    let field: Field
    let text: String
    var body: some View {
        HStack {
            Text(field.label).font(.subheadline).secondaryText()
            Spacer()
            Text(text).font(.subheadline.weight(.semibold))
        }
        .cardBox()
    }
}
