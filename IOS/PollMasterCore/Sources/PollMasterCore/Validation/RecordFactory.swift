import Foundation

public enum RecordFactory {
    /// New draft with defaults applied, sessionTime started and fixed table rows created.
    public static func make(template t: Template, now: Date = Date(), surveyorName: String? = nil) -> Record {
        var r = Record(templateId: t.id, templateVersion: t.version, createdAt: now)
        for f in t.allFields {
            if f.type == .sessionTime { r.values[f.id] = FieldValue(v: SessionTime(start: now).json) }
            else if let d = f.default {
                var v = d
                if d.string == "$today" { v = .string(ISODate.dayString(now)) }
                else if d.string == "$now" { v = .string(ISODate.string(now)) }
                r.values[f.id] = FieldValue(v: v)
            }
        }
        if let name = surveyorName, !name.isEmpty, t.field("HD-surveyor") != nil {
            r.values["HD-surveyor"] = FieldValue(v: .string(name))
        }
        for tb in t.allTables {
            r.tables[tb.id] = (tb.rows ?? []).map { Row(id: $0.id) }
        }
        return r
    }

    /// Copy of values/tables only: no media, no signatures, fresh id, draft.
    public static func duplicate(_ source: Record, template: Template? = nil, now: Date = Date()) -> Record {
        var r = Record(templateId: source.templateId, templateVersion: source.templateVersion, createdAt: now,
                       values: source.values.mapValues(strippedOfMedia).filter { !$0.value.isBlank }, tables: source.tables.mapValues { $0.map(strippedRow) })
        r.updatedAt = now
        for f in template?.allFields ?? [] where f.type == .sessionTime { r.values[f.id] = FieldValue(v: SessionTime(start: now).json) }
        return r
    }

    private static func strippedOfMedia(_ v: FieldValue) -> FieldValue { TableOps.strippedOfMedia(v) }

    private static func strippedRow(_ row: Row) -> Row {
        Row(id: row.id, values: row.values.mapValues(strippedOfMedia), stopwatchStart: row.stopwatchStart)
    }

    /// Mark finished: status, completedAt, end of sessionTime fields.
    public static func complete(_ r: inout Record, template t: Template, now: Date = Date()) {
        r.status = .completed
        r.returned = false
        r.completedAt = now
        r.updatedAt = now
        for f in t.allFields where f.type == .sessionTime {
            guard var st = SessionTime(r.values[f.id]?.v) else { continue }
            st.end = now
            r.values[f.id]?.v = st.json
        }
    }

    /// Checker hands a completed record back: draft again, flagged `returned`. False when it is not completed.
    @discardableResult
    public static func returnForFix(_ r: inout Record, now: Date = Date()) -> Bool {
        guard r.status == .completed else { return false }
        r.status = .draft
        r.returned = true
        r.completedAt = nil
        r.updatedAt = now
        return true
    }

    /// Sets (or clears, for blank text) the checker note of a record field.
    public static func setReview(_ r: inout Record, fieldId: String, text: String?, now: Date = Date()) {
        var fv = r.values[fieldId] ?? FieldValue()
        fv.review = text
        fv.normalize()
        r.values[fieldId] = fv.isBlank ? nil : fv
        r.updatedAt = now
    }

    public static func reopen(_ r: inout Record, now: Date = Date()) {
        r.status = .draft
        r.completedAt = nil
        r.updatedAt = now
    }
}
