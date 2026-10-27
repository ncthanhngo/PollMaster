import Foundation
import Observation
import SwiftUI
import PollMasterCore

/// Editing session for one record: values, derived data (computed/issues/progress) and debounced autosave.
@MainActor @Observable
final class RecordEditorModel {
    let template: Template
    // Derived values are written only by the model itself (`refresh()` in RecordEditorModel+Derived.swift); views just read.
    private(set) var record: Record
    var computed: [String: JSONValue] = [:]
    var issues: [ValidationIssue] = []
    var progress = RequiredProgress(done: 0, total: 0)
    var sectionProgress: [String: RequiredProgress] = [:]
    var sectionStats: [String: SectionStats] = [:]
    var warnings: [ReviewFinding] = []
    var reviewNotes: [ReviewFinding] = []
    /// Red error marks stay hidden until the user pressed "Kiểm tra" for the first time (this session).
    var showAllErrors = false
    /// Completed record opened by the checker: read-only, notes allowed.
    var reviewMode = false
    var saveError: String?
    var backupError: String?
    private(set) var lastSaved: Date?
    /// Warnings the user accepted when leaving the field ("Giữ nguyên"), by field key.
    private var acceptedWarnings: Set<String> = []
    private var touched: Set<String> = []
    @ObservationIgnored var dirty = false
    /// Editor stack closed: later changes (a photo finishing, a backup stamp) are saved at once, never debounced.
    @ObservationIgnored var closed = false
    /// Record deleted / moved / wiped: nothing of this model may be written any more.
    @ObservationIgnored var discarded = false
    @ObservationIgnored var saveTask: Task<Void, Never>?
    /// Validation, progress, stats and warnings are recomputed shortly after the last edit (`refresh()` forces it).
    @ObservationIgnored var refreshTask: Task<Void, Never>?
    @ObservationIgnored var derivedStale = false
    @ObservationIgnored var backupTask: Task<Bool, Never>?
    /// Distance (m) of each lookup-filled GPS field to its planned site, computed once per refresh.
    var plannedDistances: [String: Double] = [:]

    @ObservationIgnored let store: RecordStore
    @ObservationIgnored let location: LocationService
    @ObservationIgnored let settings: AppSettings
    @ObservationIgnored let backups: BackupService
    @ObservationIgnored let sites: () -> [Site]
    /// Project of a record (by `projectId`), for backup naming.
    @ObservationIgnored let project: (String?) -> Project?
    /// Called with the record after every successful save (the records list keeps its summary current).
    @ObservationIgnored var onSaved: ((Record) -> Void)?

    init(template: Template, record: Record, store: RecordStore, location: LocationService, settings: AppSettings,
         backups: BackupService, sites: @escaping () -> [Site], project: @escaping (String?) -> Project?) {
        self.template = template; self.record = record; self.store = store; self.location = location; self.settings = settings
        self.backups = backups; self.sites = sites; self.project = project
        lastSaved = record.updatedAt
        refresh()
    }

    var isLocked: Bool { record.status == .completed }
    var canEdit: Bool { !isLocked }
    var title: String { RecordNaming.title(template, record) }

    /// Remembered without touching `updatedAt` (it is not an edit).
    func setPosition(section: String, field: String) {
        let new = LastPosition(section: section, field: field)
        guard record.lastPosition != new else { return }
        record.lastPosition = new
        scheduleSave()
    }

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
        guard let def = template.table(table) else { return }
        if !def.isFixed { TableOps.ensureRow(&record, template, table: def, rowId: rowId) }
        var rows = record.tables[table] ?? []
        if let i = rows.firstIndex(where: { $0.id == rowId }) {
            body(&rows[i])
        } else if def.rows?.contains(where: { $0.id == rowId }) == true {
            var row = Row(id: rowId)          // fixed row not stored yet
            body(&row)
            rows.append(row)
        } else { return }
        record.tables[table] = rows
        changed()
    }

    /// Repairs a missing twin row of a paired table before it is shown (data from older versions).
    func ensureRow(_ table: String, _ rowId: String) {
        guard let def = template.table(table), !def.isFixed else { return }
        let ids = [def.id] + (template.pairedTable(of: def).map { [$0.id] } ?? [])
        guard ids.contains(where: { record.tables[$0]?.contains { $0.id == rowId } != true }) else { return }
        TableOps.ensureRow(&record, template, table: def, rowId: rowId)
        changed()
    }

    /// New row (and its twin in a paired table), `carryOver` columns prefilled from the previous row.
    func addRow(_ table: String) -> String {
        guard let def = template.table(table) else { return "" }
        let id = TableOps.addRow(&record, template, table: def)
        changed()
        return id
    }

    func duplicateRow(_ table: String, _ rowId: String) {
        guard let def = template.table(table), TableOps.duplicateRow(&record, template, table: def, rowId: rowId) != nil else { return }
        changed()
    }

    func deleteRow(_ table: String, _ rowId: String) {
        guard let def = template.table(table) else { return }
        let removed = TableOps.deleteRow(&record, template, table: def, rowId: rowId)
        changed()
        removeMediaFiles(of: removed.flatMap { $0.values.values })
    }

    func setSignature(_ id: String, _ entry: SignatureEntry?) {
        record.signatures[id] = entry
        changed()
    }

    func complete() {
        refresh()
        guard issues.isEmpty else { return }
        RecordFactory.complete(&record, template: template)
        reviewMode = false
        changed(); flush()
        if settings.autoBackup { Task { await backup() } }
    }

    func reopen() {
        RecordFactory.reopen(&record)
        reviewMode = false
        changed(); flush()
    }

    /// Checker hands the record back to the surveyor.
    func returnForFix() {
        guard RecordFactory.returnForFix(&record) else { return }
        reviewMode = false
        changed(); flush()
    }

    /// Bulk KĐĐ for the empty flaggable fields of a section; returns the count.
    @discardableResult
    func markUnmeasurable(section id: String, reason: String) -> Int {
        let n = SectionOps.markUnmeasurable(template, &record, sectionId: id, reason: reason)
        if n > 0 { changed() }
        return n
    }

    @discardableResult
    func copySection(_ id: String, from source: Record) -> Int {
        let n = SectionOps.copySection(template, from: source, into: &record, sectionId: id)
        if n > 0 { changed() }
        return n
    }

    func applyLookup(_ lookup: Lookup, site: Site, overwrite: Bool) {
        SiteLookup.apply(SiteLookup.fillValues(lookup, site: site), to: &record, overwrite: overwrite)
        changed()
    }

    // MARK: derived state & saving

    private func changed() {
        record.updatedAt = Date()
        computed = ComputedEngine(template: template, record: record).computeAll()    // values on screen stay exact
        derivedStale = true
        scheduleRefresh()
        scheduleSave()
    }

    private func scheduleSave() {
        dirty = true
        if closed { flush(); return }
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    /// Stamps a finished backup of `snapshot`: `backupAt` is the snapshot's time when the record changed meanwhile, so it still counts as unsaved.
    func markBackedUp(snapshot: Record) {
        guard !discarded else { return }
        if closed {
            // the stored record may have been deleted or moved since: reconcile with it instead of writing our copy
            guard let stamped = BackupReconcile.stamped(snapshot: snapshot, current: store.load(record.id), editorOpen: false),
                  (try? store.save(stamped)) != nil else { return }
            onSaved?(stamped)
            return
        }
        record.backupAt = BackupService.backupTime(snapshot: snapshot.updatedAt, currentUpdatedAt: record.updatedAt)
        dirty = true
        flush()
    }

    func flush() {
        saveTask?.cancel()
        guard !discarded else { return }
        if derivedStale { refresh() }
        guard dirty else { return }
        if closed {      // deleted or moved to another project meanwhile: that wins
            guard let stored = store.load(record.id), stored.projectId == record.projectId else { dirty = false; return }
        }
        do {
            try store.save(record)
            dirty = false
            lastSaved = Date()
            onSaved?(record)
        } catch { saveError = "Không lưu được phiếu: \(error.localizedDescription)" }
    }

    /// Editor stack closed: save what is pending; the model is dropped by the owner.
    func close() { flush(); closed = true }

    /// Forget unsaved edits and stop every pending task (local data is about to be wiped).
    func discard() {
        saveTask?.cancel(); refreshTask?.cancel(); backupTask?.cancel()
        discarded = true
        dirty = false
        onSaved = nil
    }

    private func removeMediaFiles(of values: some Sequence<FieldValue>) {
        let svc = mediaService
        for v in values { (v.media ?? []).forEach(svc.remove); v.mediaInV.forEach(svc.remove) }
    }

    // MARK: warnings the user already accepted

    func isAccepted(_ key: String) -> Bool { acceptedWarnings.contains(key) }
    func accept(_ key: String) { acceptedWarnings.insert(key) }

    // MARK: field scope

    func errorText(key: String, fieldId: String, row: String?, column: String?) -> String? {
        guard showAllErrors || touched.contains(key) else { return nil }
        let match = issues.filter { $0.fieldId == fieldId && $0.rowId == row && $0.columnId == column }
        return match.isEmpty ? nil : match.map(\.message).joined(separator: ". ")
    }

    func touch(_ key: String) { touched.insert(key) }

    var mediaService: MediaService {
        let t = template, snapshot = record     // value copies: the service also runs off the main actor
        return MediaService(store: store, recordId: snapshot.id, namer: { fid, seq in
            RecordNaming.mediaName(t, snapshot, fieldId: fid, seq: seq)
        }, quality: settings.photoQuality)
    }
}
