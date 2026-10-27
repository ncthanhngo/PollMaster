import SwiftUI
import PollMasterCore

/// Fixed-row table answered on the list itself (`inline`, schema §8): a label, the first choice column as buttons,
/// and ✎ for the remaining columns in a small sheet.
struct InlineTableView: View {
    let model: RecordEditorModel
    let table: TableDef
    let layout: InlineLayout
    @State private var editing: String?
    @Environment(\.pmTheme) private var theme

    var body: some View {
        ForEach(Array(model.rows(table.id).enumerated()), id: \.element.id) { i, row in
            let summary = TableRowSummary.make(table: table, row: row, index: i)
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text(summary.title).font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, alignment: .leading)
                    if !layout.sheetColumns.isEmpty {
                        Button { editing = row.id } label: {
                            Image(systemName: filled(row) ? "pencil.circle.fill" : "pencil")
                                .frame(minWidth: 44, minHeight: max(44, theme.minTouch))
                        }
                        .buttonStyle(.bordered).accessibilityLabel("Sửa thêm")
                    }
                }
                let b = model.rowBinding(table: table.id, row: row.id, column: layout.choice.id)
                ScalarInput(field: layout.choice, value: Binding(get: { b.wrappedValue.v }, set: { var n = b.wrappedValue; n.v = $0; b.wrappedValue = n }),
                            other: Binding(get: { b.wrappedValue.other }, set: { var n = b.wrappedValue; n.other = $0; b.wrappedValue = n }), quick: true)
                    .disabled(model.isLocked)
            }
            .cardBox(padding: 10)
        }
        .sheet(item: Binding(get: { editing.map(RowRef.init) }, set: { editing = $0?.id })) { ref in
            InlineRowSheet(model: model, table: table, layout: layout, rowId: ref.id)
        }
    }

    private struct RowRef: Identifiable { let id: String }

    private func filled(_ row: Row) -> Bool { layout.sheetColumns.contains { row.values[$0.id]?.isBlank == false } }
}

private struct InlineRowSheet: View {
    let model: RecordEditorModel
    let table: TableDef
    let layout: InlineLayout
    let rowId: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.pmTheme) private var theme

    var body: some View {
        let row = model.rows(table.id).first { $0.id == rowId } ?? Row(id: rowId)
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(layout.sheetColumns) { col in
                        FieldCard(field: col, fv: model.rowBinding(table: table.id, row: rowId, column: col.id),
                                  scope: model.scope(table: table, row: row), showCode: false)
                    }
                }
                .padding()
            }
            .background(theme.screenBackground)
            .navigationTitle(TableRowSummary.make(table: table, row: row, index: 0).title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}
