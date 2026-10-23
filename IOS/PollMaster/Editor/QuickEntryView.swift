import SwiftUI
import PollMasterCore

/// "Ghi lượt": stopwatch on top, one-tap grids for choices, then numbers/notes, video last. Spans both paired tables.
struct QuickEntryView: View {
    let model: RecordEditorModel
    let table: TableDef
    let rowId: String
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @FocusState private var focused: String?

    /// A column shown once even when both paired tables have it; edits go to every table that owns it.
    private struct Entry: Identifiable {
        var field: Field
        var tables: [TableDef]
        var id: String { field.id }
    }

    private var tables: [TableDef] {
        let all = [table] + (model.template.pairedTable(of: table).map { [$0] } ?? [])
        return all.sorted { ($0.stopwatch != nil ? 0 : 1) < ($1.stopwatch != nil ? 0 : 1) }
    }
    private var stopwatchTable: TableDef? { tables.first { $0.stopwatch != nil } }

    private var entries: [Entry] {
        let hidden = Set(stopwatchTable?.stopwatch?.columns ?? [])
        var out: [Entry] = []
        // Columns of the log table (no stopwatch) come first, so Mã KB precedes O/T as in the spec and on Android.
        for t in tables.filter({ $0.stopwatch == nil }) + tables.filter({ $0.stopwatch != nil }) {
            for c in t.columns where !c.readonly && !hidden.contains(c.id) {
                if let i = out.firstIndex(where: { $0.field.id == c.id && $0.field.label == c.label && $0.field.type == c.type }) {
                    out[i].tables.append(t)
                } else { out.append(Entry(field: c, tables: [t])) }
            }
        }
        func rank(_ f: Field) -> Int { [.singleChoice, .yesNo].contains(f.type) ? 0 : (f.type == .video ? 2 : 1) }
        return out.enumerated().sorted { (rank($0.element.field), $0.offset) < (rank($1.element.field), $1.offset) }.map(\.element)
    }

    private var stopwatchRow: (table: TableDef, row: Row)? {
        guard let sw = stopwatchTable, let row = model.rows(sw.id).first(where: { $0.id == rowId }) else { return nil }
        return (sw, row)
    }

    private var nextButton: some View {
        Button { next() } label: {
            Label("Lượt tiếp theo", systemImage: "arrow.right.circle.fill").frame(maxWidth: .infinity, minHeight: max(36, theme.minTouch))
        }
        .buttonStyle(.borderedProminent)
    }

    private var index: Int { model.rows(table.id).firstIndex { $0.id == rowId } ?? 0 }

    private func binding(_ e: Entry) -> Binding<FieldValue> {
        Binding(get: { model.rowBinding(table: e.tables[0].id, row: rowId, column: e.field.id).wrappedValue },
                set: { new in e.tables.forEach { model.rowBinding(table: $0.id, row: rowId, column: e.field.id).wrappedValue = new } })
    }

    var body: some View {
        PinnedStopwatchScroll(model: model, stopwatch: stopwatchRow) {
            if model.isLocked { LockedBanner(model: model) }
            FieldGroupCard(data: entries) { e in entryView(e) }
            if !model.isLocked && !table.isFixed && !theme.oneHand { nextButton.padding(.top, 8) }
        }
        .safeAreaInset(edge: .bottom) {
            if theme.oneHand && !model.isLocked && !table.isFixed {
                nextButton.padding(.horizontal).padding(.vertical, 8).background(.bar)
            }
        }
        .background(theme.screenBackground)
        .navigationTitle("\(table.rowLabel ?? "Dòng") \(index + 1)")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Xong") { focused = nil } } }
        .onAppear { model.ensureRow(table.id, rowId) }
        .onDisappear { model.flush(); Task { await model.backup() } }
    }

    @ViewBuilder private func entryView(_ e: Entry) -> some View {
        let t = e.tables[0]
        let row = model.rows(t.id).first { $0.id == rowId } ?? Row(id: rowId)
        let scope = model.scope(table: t, row: row, focus: $focused)
        let b = binding(e)
        switch e.field.type {
        case .singleChoice, .yesNo:
            VStack(alignment: .leading, spacing: 6) {
                (Text(e.field.label).fontWeight(.semibold) + (e.field.required ? Text(" *").foregroundColor(theme.alert) : Text(""))).font(.body)
                ScalarInput(field: e.field, value: Binding(get: { b.wrappedValue.v }, set: { b.wrappedValue.v = $0 }),
                            other: Binding(get: { b.wrappedValue.other }, set: { b.wrappedValue.other = $0 }), quick: true)
                    .disabled(model.isLocked)
            }
            .fieldContainer()
        case .video:
            VStack(alignment: .leading, spacing: 6) {
                MediaInput(field: e.field, video: true, fieldValue: b, scope: scope, captureTitle: "Quay video lượt này").disabled(model.isLocked)
            }
            .fieldContainer()
        default:
            FieldCard(field: e.field, fv: b, scope: scope, showCode: false)
        }
    }

    private func next() {
        focused = nil
        let id = model.addRow(table.id)
        env.recordsPath.removeLast()
        env.recordsPath.append(.row(record: model.record.id, table: table.id, row: id))
    }
}
