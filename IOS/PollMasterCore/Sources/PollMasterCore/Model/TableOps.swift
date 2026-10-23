import Foundation

/// Row operations on repeat tables, keeping `pairedWith` partners in sync (same row id in both tables).
public enum TableOps {
    /// Copy of a cell without media (media never travels to another row/record).
    static func strippedOfMedia(_ v: FieldValue) -> FieldValue {
        var c = v
        c.media = nil; c.review = nil
        if let arr = c.v?.array, arr.contains(where: { $0.object?["file"] != nil }) { c.v = nil }   // photo/video/audio lists
        if c.v?.object?["file"] != nil { c.v = nil }                                              // signature field
        return c
    }

    /// New row for `table`: `carryOver` columns start with the previous row's value (value, parts, other; no media/note).
    static func newRow(_ table: TableDef, id: String, previous: Row?) -> Row {
        var row = Row(id: id)
        guard let previous else { return row }
        for col in table.columns where col.carryOver {
            guard let prev = previous.values[col.id], prev.flag != .notMeasurable else { continue }
            let c = FieldValue(v: prev.v, flag: prev.flag, other: prev.other, parts: prev.parts)
            let cleaned = strippedOfMedia(c)
            if !cleaned.isBlank { row.values[col.id] = cleaned }
        }
        return row
    }

    private static func tables(_ t: Template, _ table: TableDef) -> [TableDef] {
        [table] + (t.pairedTable(of: table).map { [$0] } ?? [])
    }

    /// Appends a row (and its twin in the paired table). Returns the shared id.
    @discardableResult
    public static func addRow(_ r: inout Record, _ t: Template, table: TableDef, id: String = UUID().uuidString) -> String {
        for tb in tables(t, table) {
            r.tables[tb.id, default: []].append(newRow(tb, id: id, previous: r.tables[tb.id]?.last))
        }
        return id
    }

    /// Duplicates a row right after the original, in both paired tables, under a fresh shared id.
    @discardableResult
    public static func duplicateRow(_ r: inout Record, _ t: Template, table: TableDef, rowId: String) -> String? {
        guard !table.isFixed, r.tables[table.id]?.contains(where: { $0.id == rowId }) == true else { return nil }
        let newId = UUID().uuidString
        for tb in tables(t, table) {
            guard var rows = r.tables[tb.id] else { continue }
            if let i = rows.firstIndex(where: { $0.id == rowId }) {
                rows.insert(Row(id: newId, values: rows[i].values.mapValues(strippedOfMedia).filter { !$0.value.isBlank }), at: i + 1)
            } else if tb.id != table.id {
                rows.append(Row(id: newId))      // partner never had this row: keep ids in sync anyway
            }
            r.tables[tb.id] = rows
        }
        return newId
    }

    /// Removes the row from the table and its paired table. Returns the removed rows (to delete their media).
    @discardableResult
    public static func deleteRow(_ r: inout Record, _ t: Template, table: TableDef, rowId: String) -> [Row] {
        var removed: [Row] = []
        for tb in tables(t, table) {
            removed += (r.tables[tb.id] ?? []).filter { $0.id == rowId }
            r.tables[tb.id]?.removeAll { $0.id == rowId }
        }
        return removed
    }

    /// Makes sure a stored row with this id exists in a repeat table and in its partner (used when opening old data).
    public static func ensureRow(_ r: inout Record, _ t: Template, table: TableDef, rowId: String) {
        guard !table.isFixed else { return }
        for tb in tables(t, table) where r.tables[tb.id]?.contains(where: { $0.id == rowId }) != true {
            r.tables[tb.id, default: []].append(Row(id: rowId))
        }
    }
}
