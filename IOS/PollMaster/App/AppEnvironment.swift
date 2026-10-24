import Foundation
import UIKit
import Observation
import PollMasterCore

struct RecordSummary: Identifiable, Equatable {
    var id: String
    var title: String
    /// `{template.id} · {record code}`; the read error for unreadable records.
    var subtitle: String
    var updatedAt: Date
    var status: RecordStatus
    var progress: RequiredProgress
    /// Owning project; records written before projects existed belong to "Chung".
    var projectId = ""
    var returned = false
    var needsBackup = false
    var thumbnail: URL?
    /// First `gps` field holding a value (map pin).
    var coordinate: GPSCoordinate?
    /// Set when record.json could not be read ("Lỗi đọc"); such entries can only be deleted.
    var loadError: String?
}

struct GPSCoordinate: Equatable { var lat: Double; var lng: Double }

struct PendingCreate { var template: Template; var project: Project }

enum ImportOutcome { case installed(Template), needsOverwrite(Template, Data) }

@MainActor @Observable
final class AppEnvironment {
    let root: URL
    let templateStore: TemplateStore
    let recordStore: RecordStore
    let projectStore: ProjectStore
    let backups: BackupService
    let settings = AppSettings()
    let location = LocationService()

    var templates: [Template] = []
    var projects: [Project] = []
    /// Photo avatars by project id, read once per reload (rows would otherwise hit the disk on every render).
    var projectAvatars: [String: UIImage] = [:]
    var summaries: [RecordSummary] = []
    private(set) var projectStats: [String: ProjectStats] = [:]
    /// Planned sites per project id, loaded on `reload()`.
    private(set) var siteCache: [String: [Site]] = [:]
    /// Codes of planned sites that already have a record (a lookup field holds the code), per project id.
    private(set) var visitedByProject: [String: Set<String>] = [:]
    /// Project shown in the records tab; nil = "Tất cả dự án". Remembered across launches.
    var currentProjectId: String? { didSet { UserDefaults.standard.set(currentProjectId, forKey: "currentProjectId") } }
    /// Records-tab project switcher sheet.
    var showProjectSwitcher = false
    var projectsPath: [ProjectRoute] = []
    var showNewProject = false
    /// A `.pmproject` opened from another app, waiting on the preview sheet.
    var incomingBundle: IncomingBundle?
    /// Template just imported: asks which projects should get it.
    var addToProjectsFor: Template?
    var selectedTab: AppTab = .records
    var recordsPath: [Route] = []
    var errorMessage: String?
    /// Plain notice (title "Thông báo").
    var infoMessage: String?
    /// Set while the one-time "Tên người khảo sát" question is open (ux-spec L9).
    var askSurveyorFor: PendingCreate?
    /// Records tab shows the map instead of the list.
    var recordsShowMap = false
    @ObservationIgnored var editors: [String: RecordEditorModel] = [:]

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = support.appendingPathComponent("PollMaster", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        templateStore = TemplateStore(root: root)
        recordStore = RecordStore(root: root)
        projectStore = ProjectStore(root: root)
        backups = BackupService(store: recordStore)
        currentProjectId = UserDefaults.standard.string(forKey: "currentProjectId")
        installBundledTemplates()
        do { try ProjectMigration(root: root).run() } catch { errorMessage = "Không chuyển dữ liệu cũ vào dự án: \(error.localizedDescription)" }
        reload()
    }

    // MARK: templates

    /// Install shared templates on first launch and whenever a bundled version is missing; installed older versions stay.
    private func installBundledTemplates() {
        let urls = Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: "templates") ?? []
        for url in urls {
            do {
                let data = try Data(contentsOf: url)
                let t = try TemplateLoader.decode(data)
                if !templateStore.exists(id: t.id, version: t.version) { try templateStore.install(data) }
            } catch { errorMessage = "Không cài được mẫu \(url.lastPathComponent): \(error.localizedDescription)" }
        }
    }

    func reload() {
        editors.values.forEach { $0.flush() }
        templates = templateStore.list()
        let byKey = Dictionary(templates.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        projects = projectStore.list()
        projectAvatars = Dictionary(uniqueKeysWithValues: projects.compactMap { p in
            projectStore.avatarURL(p.id).flatMap { UIImage(contentsOfFile: $0.path) }.map { (p.id, $0) }
        })
        if let id = currentProjectId, !projects.contains(where: { $0.id == id }) { currentProjectId = nil }
        let general = projects.last(where: \.isGeneral)?.id ?? projects.last?.id ?? ""
        siteCache = Dictionary(uniqueKeysWithValues: projects.map { ($0.id, projectStore.sites($0.id).load()) })
        var visited: [String: Set<String>] = [:], stats: [String: ProjectStats] = [:]
        summaries = recordStore.listItems().map { item in
            guard let r = item.record else {
                return RecordSummary(id: item.id, title: item.id, subtitle: item.error ?? "", updatedAt: item.modifiedAt,
                                     status: .draft, progress: RequiredProgress(done: 0, total: 0), projectId: general, loadError: item.error ?? "Không đọc được")
            }
            let pid = r.projectId ?? general
            stats[pid, default: ProjectStats()].add(r)
            guard let t = byKey["\(r.templateId)@\(r.templateVersion)"] else {
                return RecordSummary(id: r.id, title: "Phiếu mới · " + ISODate.format(r.createdAt, "dd/MM/yyyy HH:mm"),
                                     subtitle: "\(r.templateId) (thiếu mẫu)", updatedAt: r.updatedAt, status: r.status,
                                     progress: RequiredProgress(done: 0, total: 0), projectId: pid, returned: r.returned, needsBackup: r.needsBackup)
            }
            for f in t.allFields where f.lookup != nil { if let code = r.values[f.id]?.v?.string, !code.isEmpty { visited[pid, default: []].insert(code) } }
            let gps = t.allFields.filter { $0.type == .gps }.compactMap { GPSValue(r.values[$0.id]?.v) }.first
            return RecordSummary(id: r.id, title: RecordNaming.title(t, r), subtitle: RecordInfo.subtitle(t, r), updatedAt: r.updatedAt,
                                 status: r.status, progress: Validator.progress(t, r), projectId: pid, returned: r.returned, needsBackup: r.needsBackup,
                                 thumbnail: RecordInfo.firstPhoto(t, r).map { recordStore.fileURL(r.id, $0.file) },
                                 coordinate: gps.map { GPSCoordinate(lat: $0.lat, lng: $0.lng) })
        }
        visitedByProject = visited; projectStats = stats
    }

    /// Newest version of each template: what new records are created from.
    var newestTemplates: [Template] { TemplateVersion.newest(templates) }

    // MARK: backup

    /// Backs up every readable record now (open editors through their model so no edit is lost).
    func backupAll() async -> (done: Int, failed: Int) {
        var done = 0, failed = 0
        for s in summaries where s.loadError == nil {
            if let m = editors[s.id] { await m.backup(force: true); if m.backupError == nil { done += 1 } else { failed += 1 }; continue }
            guard var r = recordStore.load(s.id), let t = templateStore.load(id: r.templateId, version: r.templateVersion) else { failed += 1; continue }
            do {
                try await backups.write(t, r, project: project(of: r))
                r.backupAt = BackupService.backupTime(snapshot: r.updatedAt, currentUpdatedAt: r.updatedAt)
                try recordStore.save(r)
                done += 1
            } catch { failed += 1 }
        }
        reload()
        return (done, failed)
    }

    /// App going to the background: save and back up whatever is open and changed.
    func backupOpenEditors() {
        editors.values.forEach { $0.flush() }
        guard settings.autoBackup else { return }
        let open = Array(editors.values)
        BackupService.whileBackgrounded {
            for m in open { await m.backup() }
        }
    }

    func template(for recordId: String) -> Template? {
        guard let r = recordStore.load(recordId) else { return nil }
        return templateStore.load(id: r.templateId, version: r.templateVersion)
    }

    func recordCount(using t: Template) -> Int {
        recordStore.list().filter { $0.templateId == t.id && $0.templateVersion == t.version }.count
    }

    func deleteTemplate(_ t: Template) {
        let n = recordCount(using: t)
        guard n == 0 else { errorMessage = TemplateError.inUse(count: n).localizedDescription; return }
        let users = projects.filter { $0.templates.contains(TemplateRef(t)) }
        guard users.isEmpty else {
            errorMessage = "Còn \(users.count) dự án dùng mẫu này (\(users.map(\.code).joined(separator: ", "))). Gỡ mẫu khỏi dự án trước."; return
        }
        do { try templateStore.delete(t); reload() } catch { errorMessage = error.localizedDescription }
    }

    func importTemplate(_ data: Data) throws -> ImportOutcome {
        let t = try TemplateLoader.decode(data)
        if templateStore.exists(id: t.id, version: t.version) { return .needsOverwrite(t, data) }
        try templateStore.install(data)
        reload()
        return .installed(t)
    }

    func overwriteTemplate(_ data: Data) throws {
        try templateStore.install(data)
        reload()
    }

    // MARK: records

    /// Entry point of every "create record" button: asks for the surveyor name once when Cài đặt has none, then creates.
    func startCreate(from t: Template, in project: Project) {
        if settings.surveyorName.isEmpty, !settings.surveyorAsked, t.field("HD-surveyor") != nil { askSurveyorFor = PendingCreate(template: t, project: project) }
        else { createRecord(from: t, in: project) }
    }

    func answerSurveyor(_ name: String) {
        guard let pending = askSurveyorFor else { return }
        askSurveyorFor = nil
        settings.surveyorAsked = true
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmed.isEmpty { settings.surveyorName = trimmed }
        createRecord(from: pending.template, in: pending.project)
    }

    func createRecord(from t: Template, in project: Project) {
        var r = RecordFactory.make(template: t, surveyorName: settings.surveyorName, projectId: project.id)
        let mine = recordStore.list().filter { ($0.projectId ?? "") == project.id }
        if let code = t.recordCodeField, r.values[code.id]?.v?.isBlank != false,
           let proposed = RecordCodeProposal.propose(for: t, among: mine) {
            r.values[code.id] = FieldValue(v: .string(proposed))
        }
        do {
            try recordStore.save(r)
            reload()
            open(r.id)
        } catch { errorMessage = "Không tạo được phiếu: \(error.localizedDescription)" }
    }

    func open(_ recordId: String) {
        selectedTab = .records
        recordsPath = [.editor(recordId)]
    }

    func duplicate(_ id: String) {
        guard let r = recordStore.load(id) else { return }
        let t = templateStore.load(id: r.templateId, version: r.templateVersion)
        do { try recordStore.save(RecordFactory.duplicate(r, template: t)); reload() }
        catch { errorMessage = "Không nhân bản được: \(error.localizedDescription)" }
    }

    func delete(_ id: String) {
        editors[id] = nil
        do { try recordStore.delete(id); reload() } catch { errorMessage = "Không xóa được: \(error.localizedDescription)" }
    }

    func editor(for id: String) -> RecordEditorModel? {
        if let m = editors[id] { return m }
        guard let r = recordStore.load(id), let t = templateStore.load(id: r.templateId, version: r.templateVersion) else { return nil }
        let m = RecordEditorModel(template: t, record: r, store: recordStore, location: location, settings: settings, backups: backups,
                              sites: { [unowned self] in self.sites(forProject: r.projectId) },
                              project: { [unowned self] pid in self.project(id: pid) })
        editors[id] = m
        return m
    }

    func usedStorage() -> Int64 { RecordStore.directorySize(root) }
}
