import Foundation

/// Whole-section operations: bulk "KĐĐ" and copying a section from another record.
public enum SectionOps {
    private static func items(_ t: Template, sectionId: String) -> [Item] {
        sectionId == Validator.headerSectionId ? t.header.map { .field($0) } : (t.sections.first { $0.id == sectionId }?.items ?? [])
    }

    private static func holdsNothing(_ fv: FieldValue?) -> Bool {
        guard let fv else { return true }
        return fv.flag == nil && fv.v == nil && fv.parts == nil && fv.media == nil
    }

    /// Fields of the section a bulk KĐĐ would mark: flags enabled, editable, nothing entered yet.
    public static func emptyFlaggableFields(_ t: Template, _ r: Record, sectionId: String) -> [Field] {
        items(t, sectionId: sectionId).compactMap { item in
            guard case .field(let f) = item, f.flagsEnabled, !f.isComputed, !f.readonly, f.type != .sessionTime, f.type != .signature,
                  holdsNothing(r.values[f.id]) else { return nil }
            return f
        }
    }

    /// Marks those fields `notMeasurable` with one shared reason. Returns how many were marked.
    @discardableResult
    public static func markUnmeasurable(_ t: Template, _ r: inout Record, sectionId: String, reason: String) -> Int {
        let text = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return 0 }
        let fields = emptyFlaggableFields(t, r, sectionId: sectionId)
        for f in fields {
            var fv = r.values[f.id] ?? FieldValue()
            fv.flag = .notMeasurable; fv.reason = text
            r.values[f.id] = fv
        }
        return fields.count
    }

    /// Does the section already hold data in `r` (so copying over it needs confirmation)?
    public static func hasData(_ t: Template, _ r: Record, sectionId: String) -> Bool {
        items(t, sectionId: sectionId).contains { item in
            switch item {
            case .field(let f): return !f.isComputed && !holdsNothing(r.values[f.id])
            case .table(let tb): return r.rows(of: tb).contains { !$0.isEmpty }
            case .heading: return false
            }
        }
    }

    /// Copies values and tables of one section from `source` (same template). No media, no session time.
    /// Paired tables are copied together so their row ids stay in sync. Returns the number of fields/tables copied.
    @discardableResult
    public static func copySection(_ t: Template, from source: Record, into r: inout Record, sectionId: String) -> Int {
        var copied = 0
        var done = Set<String>()
        func copyTable(_ tb: TableDef) {
            guard done.insert(tb.id).inserted else { return }
            let rows = source.rows(of: tb).filter { !$0.isEmpty }
            guard !rows.isEmpty else { return }
            if let fixed = tb.rows {
                let stored = Dictionary(source.rows(of: tb).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
                r.tables[tb.id] = fixed.map { fr in
                    guard let s = stored[fr.id] else { return Row(id: fr.id) }
                    return Row(id: fr.id, values: s.values.mapValues(TableOps.strippedOfMedia).filter { !$0.value.isBlank }, stopwatchStart: s.stopwatchStart)
                }
            } else {
                r.tables[tb.id] = rows.map { Row(id: $0.id, values: $0.values.mapValues(TableOps.strippedOfMedia).filter { !$0.value.isBlank },
                                                  stopwatchStart: $0.stopwatchStart) }
            }
            copied += 1
        }
        for item in items(t, sectionId: sectionId) {
            switch item {
            case .field(let f):
                guard !f.isComputed, f.type != .sessionTime, f.type != .signature, let sv = source.values[f.id] else { continue }
                var v = TableOps.strippedOfMedia(sv)
                v.normalize()
                guard !v.isBlank else { continue }
                r.values[f.id] = v; copied += 1
            case .table(let tb):
                copyTable(tb)
                if let partner = t.pairedTable(of: tb) { copyTable(partner) }
            case .heading: break
            }
        }
        return copied
    }

    /// Other records made from the very same template version, newest first.
    public static func candidates(for r: Record, among all: [Record]) -> [Record] {
        all.filter { $0.id != r.id && $0.templateId == r.templateId && $0.templateVersion == r.templateVersion }
            .sorted { $0.updatedAt > $1.updatedAt }
    }
}
