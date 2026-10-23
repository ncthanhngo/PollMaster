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
    private(set) var sectionProgress: [String: RequiredProgress] = [:]
    private(set) var sectionStats: [String: SectionStats] = [:]
    private(set) var warnings: [ReviewFinding] = []
    private(set) var reviewNotes: [ReviewFinding] = []
    /// Red error marks stay hidden until the user pressed "Kiểm tra" for the first time (this session).
    var showAllErrors = false
    /// Completed record opened by the checker: read-only, notes allowed.
    var reviewMode = false
    var saveError: String?
    var backupError: String?
    private(set) var lastSaved: Date?
    /// Warnings the user accepted when leaving the field ("Giữ nguyên"), by field key.
    private var acceptedWarnings: Set<String> = []
    private var backingUp = false
    private var touched: Set<String> = []
    private var dirty = false
    private var saveTask: Task<Void, Never>?

    @ObservationIgnored let store: RecordStore
    @ObservationIgnored let location: LocationService
    @ObservationIgnored let settings: AppSettings
    @ObservationIgnored let backups: BackupService
    @ObservationIgnored let sites: () -> [Site]
    /// Project of a record (by `projectId`), for backup naming.
    @ObservationIgnored let project: (String?) -> Project?

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

    /// Section list for the table of contents: header first, then template sections.
    var sectionTitles: [(id: String, title: String)] {
        [(Validator.headerSectionId, Validator.headerSectionTitle)] + template.sections.map { ($0.id, $0.title) }
    }

    func items(ofSection index: Int) -> [Item] {
        index == 0 ? template.header.map { .field($0) } : template.sections[index - 1].items
    }

    func sectionNote(_ index: Int) -> String? { index == 0 ? nil : template.sections[index - 1].note }

    func issueCount(section id: String) -> Int { issues.filter { $0.sectionId == id }.count }

    func sectionIndex(of id: String) -> Int? { sectionTitles.firstIndex { $0.id == id } }

    /// Index of the section (0 = header) holding the field or table `id`.
    func sectionIndex(containing id: String) -> Int? {
        if template.header.contains(where: { $0.id == id }) { return 0 }
        return template.sections.firstIndex { $0.items.contains { $0.id == id } }.map { $0 + 1 }
    }

    /// "Tiếp tục" target: the last position if it still exists in the template.
    var resumeTarget: (index: Int, fieldId: String, label: String)? {
        guard let p = record.lastPosition, let i = sectionIndex(of: p.section) else { return nil }
        let label = template.field(p.field)?.label ?? template.table(p.field)?.title
        guard let label else { return nil }
        return (i, p.field, label)
    }

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

    func emptyFlaggableCount(section id: String) -> Int { SectionOps.emptyFlaggableFields(template, record, sectionId: id).count }

    func otherRecords() -> [Record] { SectionOps.candidates(for: record, among: store.list()) }

    func hasData(section id: String) -> Bool { SectionOps.hasData(template, record, sectionId: id) }

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

    func lookupConflicts(_ lookup: Lookup, site: Site) -> [String] {
        SiteLookup.fillValues(lookup, site: site).keys.filter { record.values[$0]?.v?.isBlank == false }.sorted()
    }

    private func removeMediaFiles(of values: some Sequence<FieldValue>) {
        let svc = mediaService
        for v in values { (v.media ?? []).forEach(svc.remove); v.mediaInV.forEach(svc.remove) }
    }

    // MARK: derived state & saving

    private func changed() {
        record.updatedAt = Date()
        refresh()
        scheduleSave()
    }

    private func scheduleSave() {
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
        sectionProgress = Validator.sectionProgress(template, record)
        sectionStats = Dictionary(uniqueKeysWithValues: sectionTitles.map {
            ($0.id, OutlineStats.compute(template, record, sectionId: $0.id, progress: sectionProgress[$0.id]))
        })
        warnings = Warnings.all(template, record, sites: sites())
        reviewNotes = Warnings.reviewNotes(template, record)
    }

    // MARK: backup

    /// Writes the folder backup of the current state; `force` also when nothing changed since the last one.
    func backup(force: Bool = false) async {
        guard force || (settings.autoBackup && record.needsBackup), !backingUp else { return }
        flush()
        backingUp = true; backupError = nil
        defer { backingUp = false }
        let snapshot = record
        do {
            try await backups.write(template, snapshot, project: project(snapshot.projectId))
            record.backupAt = BackupService.backupTime(snapshot: snapshot.updatedAt, currentUpdatedAt: record.updatedAt)
            scheduleSave()
        } catch { backupError = "Không sao lưu được: \(error.localizedDescription)" }
    }

    // MARK: warnings the user already accepted

    func isAccepted(_ key: String) -> Bool { acceptedWarnings.contains(key) }
    func accept(_ key: String) { acceptedWarnings.insert(key) }

    func flush() {
        saveTask?.cancel()
        guard dirty else { return }
        do {
            try store.save(record)
            dirty = false
            lastSaved = Date()
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
}
