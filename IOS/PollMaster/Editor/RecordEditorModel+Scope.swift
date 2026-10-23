import SwiftUI
import PollMasterCore

extension RecordEditorModel {
    /// Distance (m) of a lookup-filled GPS field to its planned site.
    func plannedDistance(_ fieldId: String) -> Double? {
        SiteLookup.plannedDistances(template, record, sites: sites()).first { $0.fieldId == fieldId }?.meters
    }

    /// Scope for fields of the record body (`row == nil`) or of one table row.
    func scope(table: TableDef? = nil, row: Row? = nil, focus: FocusState<String?>.Binding? = nil, sectionId: String? = nil) -> FieldScope {
        let rowId = row?.id
        let lookup: (String) -> FieldValue? = { [unowned self] id in
            if let rowId, let table, let r = self.record.rows(of: table).first(where: { $0.id == rowId }), let v = r.values[id] { return v }
            return self.record.values[id]
        }
        return FieldScope(
            tableId: table?.id,
            lookup: lookup,
            errorText: { [unowned self] field in
                let key = table.map { "\($0.id)/\(rowId ?? "")/\(field.id)" } ?? field.id
                return self.errorText(key: key, fieldId: table?.id ?? field.id, row: rowId, column: table == nil ? nil : field.id)
            },
            touch: { [unowned self] field in self.touch(table.map { "\($0.id)/\(rowId ?? "")/\(field.id)" } ?? field.id) },
            computedValue: { [unowned self] id in self.computed[id] },
            media: mediaService,
            location: location,
            locked: isLocked,
            focus: focus,
            compact: settings.compactMode,
            reviewMode: reviewMode,
            sites: sites(),
            warnings: { [unowned self] field, fv in
                var out = Warnings.messages(for: field, fv, lookup: lookup)
                if table == nil, let d = self.plannedDistance(field.id), let m = Warnings.plannedDistanceMessage(d) { out.append(m) }
                return out
            },
            visit: { [unowned self] field in
                guard let sid = sectionId else { return }
                self.setPosition(section: sid, field: table?.id ?? field.id)
            },
            reference: { [unowned self] id in
                guard let f = self.template.field(id), f.isMedia else { return nil }
                return (f, self.binding(id))
            },
            photoUsers: { [unowned self] id in self.template.photoRefUsers(of: id) },
            plannedDistance: { [unowned self] field in table == nil ? self.plannedDistance(field.id) : nil },
            lookupConflicts: { [unowned self] lk, site in self.lookupConflicts(lk, site: site) },
            applyLookup: { [unowned self] lk, site, overwrite in self.applyLookup(lk, site: site, overwrite: overwrite) })
    }
}
