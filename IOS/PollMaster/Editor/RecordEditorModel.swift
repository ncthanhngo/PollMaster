import Foundation
import Observation
import SwiftUI
import PollMasterCore

/// Editing session for one record: values, derived data (computed/issues/progress) and debounced autosave.
@MainActor @Observable
final class RecordEditorModel {
    let template: Template
    private(set) var record: Record
    private(set) var computed: [String: JSONValue] = [:]
    private(set) var issues: [ValidationIssue] = []
    private(set) var progress = RequiredProgress(done: 0, total: 0)
    var showAllErrors = false
    var saveError: String?
    private var touched: Set<String> = []
    private var dirty = false
    private var saveTask: Task<Void, Never>?

    @ObservationIgnored let store: RecordStore
    @ObservationIgnored let location: LocationService
    @ObservationIgnored let settings: AppSettings

    init(template: Template, record: Record, store: RecordStore, location: LocationService, settings: AppSettings) {
        self.template = template; self.record = record; self.store = store; self.location = location; self.settings = settings
        refresh()
    }

    var isLocked: Bool { record.status == .completed }
    var title: String { RecordNaming.title(template, record) }

    /// Section list for the table of contents: header first, then template sections.
    var sectionTitles: [(id: String, title: String)] {
        [(Validator.headerSectionId, Validator.headerSectionTitle)] + template.sections.map { ($0.id, $0.title) }
    }

    func items(ofSection index: Int) -> [Item] {
        index == 0 ? template.header.map { .field($0) } : template.sections[index - 1].items
    }

    func sectionNote(_ index: Int) -> String? { index == 0 ? nil : template.sections[index - 1].note }

    func issueCount(section id: String) -> Int { issues.filter { $0.sectionId == id }.count }

    // MARK: mutation

    func setValue(_ id: String, _ value: FieldValue) {
        var v = value; v.normalize()
        record.values[id] = v.isBlank ? nil : v
        touched.insert(id)
        changed()
    }

    func binding(_ id: String) -> Binding<FieldValue> {
        Binding(get: { self.record.value(id) }, set: { self.setValue(id, $0) })
    }

    func rowBinding(table: String, row: String, column: String) -> Binding<FieldValue> {
        Binding(get: { self.rows(table).first { $0.id == row }?.values[column] ?? FieldValue() },
                set: { new in
                    self.mutateRow(table, row) { r in
                        var v = new; v.normalize()
                        r.values[column] = v.isBlank ? nil : v
                    }
                    self.touched.insert("\(table)/\(row)/\(column)")
                })
    }

    /// Rows as displayed (fixed tables: every template row, stored or not).
    func rows(_ tableId: String) -> [Row] {
        template.table(tableId).map { record.rows(of: $0) } ?? record.rows(tableId)
    }

    func mutateRow(_ table: String, _ rowId: String, _ body: (inout Row) -> Void) {
        var rows = record.tables[table] ?? []
        if let i = rows.firstIndex(where: { $0.id == rowId }) {
            body(&rows[i])
        } else if template.table(table)?.rows?.contains(where: { $0.id == rowId }) == true {
            var row = Row(id: rowId)          // fixed row not stored yet
            body(&row)
            rows.append(row)
        } else { return }
        record.tables[table] = rows
        changed()
    }

    func addRow(_ table: String) -> String {
        let row = Row()
        record.tables[table, default: []].append(row)
        changed()
        return row.id
    }

    func duplicateRow(_ table: String, _ rowId: String) {
        guard var rows = record.tables[table], let i = rows.firstIndex(where: { $0.id == rowId }) else { return }
        var copy = rows[i]
        copy = Row(id: UUID().uuidString, values: copy.values.mapValues { v in
            var c = v; c.media = nil
            if c.v?.array?.contains(where: { $0.object?["file"] != nil }) == true { c.v = nil }
            return c
        })
        rows.insert(copy, at: i + 1)
        record.tables[table] = rows
        changed()
    }

    func deleteRow(_ table: String, _ rowId: String) {
        let removed = record.rows(table).first { $0.id == rowId }
        record.tables[table]?.removeAll { $0.id == rowId }
        changed()
        if let removed { removeMediaFiles(of: removed.values.values) }
    }

    func setSignature(_ id: String, _ entry: SignatureEntry?) {
        record.signatures[id] = entry
        changed()
    }

    func complete() {
        RecordFactory.complete(&record, template: template)
        changed(); flush()
    }

    func reopen() {
        RecordFactory.reopen(&record)
        changed(); flush()
    }

    private func removeMediaFiles(of values: some Sequence<FieldValue>) {
        let svc = mediaService
        for v in values { (v.media ?? []).forEach(svc.remove); v.mediaInV.forEach(svc.remove) }
    }

    // MARK: derived state & saving

    private func changed() {
        record.updatedAt = Date()
        refresh()
        dirty = true
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    private func refresh() {
        computed = ComputedEngine(template: template, record: record).computeAll()
        issues = Validator.validate(template, record)
        progress = Validator.progress(template, record)
    }

    func flush() {
        saveTask?.cancel()
        guard dirty else { return }
        do {
            try store.save(record)
            dirty = false
        } catch { saveError = "Không lưu được phiếu: \(error.localizedDescription)" }
    }

    // MARK: field scope

    func errorText(key: String, fieldId: String, row: String?, column: String?) -> String? {
        guard showAllErrors || touched.contains(key) else { return nil }
        let match = issues.filter { $0.fieldId == fieldId && $0.rowId == row && $0.columnId == column }
        return match.isEmpty ? nil : match.map(\.message).joined(separator: ". ")
    }

    func touch(_ key: String) { touched.insert(key) }

    var mediaService: MediaService {
        let t = template, rid = record.id
        return MediaService(store: store, recordId: rid, namer: { [weak self] fid, seq in
            guard let self else { return "\(fid)_\(seq)" }
            return RecordNaming.mediaName(t, self.record, fieldId: fid, seq: seq)
        }, quality: settings.photoQuality)
    }

    /// Scope for fields of the record body (`row == nil`) or of one table row.
    func scope(table: TableDef? = nil, row: Row? = nil, focus: FocusState<String?>.Binding? = nil) -> FieldScope {
        let rowId = row?.id
        return FieldScope(
            tableId: table?.id,
            lookup: { [unowned self] id in
                if let rowId, let table, let r = self.record.rows(of: table).first(where: { $0.id == rowId }), let v = r.values[id] { return v }
                return self.record.values[id]
            },
            errorText: { [unowned self] field in
                let key = table.map { "\($0.id)/\(rowId ?? "")/\(field.id)" } ?? field.id
                return self.errorText(key: key, fieldId: table?.id ?? field.id, row: rowId, column: table == nil ? nil : field.id)
            },
            touch: { [unowned self] field in self.touch(table.map { "\($0.id)/\(rowId ?? "")/\(field.id)" } ?? field.id) },
            computedValue: { [unowned self] id in self.computed[id] },
            media: mediaService,
            location: location,
            locked: isLocked,
            focus: focus)
    }
}
