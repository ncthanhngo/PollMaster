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
    var templateId = ""
    var returned = false
    var needsBackup = false
    /// Sync state of the card (`RecordSyncState`): last push / pull, unsent changes, conflict flag; creator for "của …" and the edit right.
    var erpUploadedAt: Date?
    var syncDirty = false
    var syncConflict = false
    var createdBy: String?
    var createdByName: String?
    /// Codes of planned sites this record visited (a lookup field holds the code).
    var visitedSites: Set<String> = []
    var thumbnail: URL?
    /// First `gps` field holding a value (map pin).
    var coordinate: GPSCoordinate?
    /// Set when record.json could not be read ("Lỗi đọc"); such entries can only be deleted.
    var loadError: String?
    /// Template short name and "#RRGGBB" colour: the label and outline of the record's card.
    var badge: String?
    var badgeColor: String?
}

struct GPSCoordinate: Equatable { var lat: Double; var lng: Double }

struct SyncProgress: Equatable { var done: Int; var total: Int }

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
    let erp = ERPClient()
    /// Signed-in ERP user; nil shows the sign-in screen.
    var account: ERPAccount?
    var syncing = false
    /// First-sign-in wipe of the pre-ERP data is still ahead (cached at launch: read by the sign-in screen on every render).
    var localDataResetPending = false
    /// Dialog of that wipe waiting for the user's answer (`answerWipe`).
    var wipePrompt: WipePrompt?
    /// Why the last ERP project fetch failed (the cached list is shown meanwhile).
    var syncError: String?

    var templates: [Template] = []
    /// Templates being edited on this device (`template-drafts/`), newest edit first.
    var templateDrafts: [TemplateDraft] = []
    var projects: [Project] = []
    /// Photo avatars by project id, read once per reload (rows would otherwise hit the disk on every render).
    var projectAvatars: [String: UIImage] = [:]
    var summaries: [RecordSummary] = []
    var projectStats: [String: ProjectStats] = [:]
    /// Planned sites per project id, loaded on `reload()`.
    var siteCache: [String: [Site]] = [:]
    /// Codes of planned sites that already have a record (a lookup field holds the code), per project id.
    var visitedByProject: [String: Set<String>] = [:]
    /// Project shown in the records tab; nil = "Tất cả dự án". Remembered across launches.
    var currentProjectId: String? { didSet { UserDefaults.standard.set(currentProjectId, forKey: "currentProjectId") } }
    /// Records-tab project switcher sheet.
    var showProjectSwitcher = false
    var projectsPath: [ProjectRoute] = []
    /// A `.pmproject` opened from another app, waiting on the preview sheet.
    var incomingBundle: IncomingBundle?
    /// Template just imported: asks which projects should get it.
    var addToProjectsFor: Template?
    var selectedTab: AppTab = .records
    /// Closing a record's editor stack (its routes leave the path) saves and drops its editor model.
    var recordsPath: [Route] = [] { didSet { releaseClosedEditors() } }
    /// Template draft open in the editor (full screen, `TemplateEditorHost`); nil when none.
    var editingDraftId: String?
    /// "Xem trước" of a draft: a real record editor on a scratch store; its screens navigate on `previewPath` (see `editorPath`).
    var previewModel: RecordEditorModel?
    var previewPath: [Route] = []
    var errorMessage: String?
    /// Plain notice (title "Thông báo").
    var infoMessage: String?
    /// Set while the one-time "Tên người khảo sát" question is open (ux-spec L9).
    var askSurveyorFor: PendingCreate?
    /// Records tab shows the map instead of the list.
    var recordsShowMap = false
    /// Newer TestFlight build (AppEnvironment+Update.swift); `updatePrompt` asks once per build.
    var availableUpdate: AppUpdate?
    var updatePrompt: AppUpdate?
    @ObservationIgnored let network = NetworkStatus()
    /// "Đang đồng bộ n/N phiếu" while a record sync pushes; nil otherwise.
    var syncProgress: SyncProgress?
    /// Short sentence per record the last sync could not push (e.g. "Tệp P05_01.jpg lớn hơn 95 MB.").
    var recordSyncIssues: [String: String] = [:]
    @ObservationIgnored var recordSyncTask: Task<Void, Never>?
    @ObservationIgnored var queuedSyncProjects: Set<String> = []
    @ObservationIgnored var queuedSyncAll = false
    @ObservationIgnored var editors: [String: RecordEditorModel] = [:]
    /// Models closed by the last navigation change: screens still sliding out keep showing them; reopening the record reuses them.
    @ObservationIgnored var closingEditors: [String: RecordEditorModel] = [:]
    // Disk scan bookkeeping (AppEnvironment+Reload.swift), ERP session bookkeeping (AppEnvironment+ERP.swift).
    @ObservationIgnored var scanning = false
    @ObservationIgnored var rescanNeeded = false
    @ObservationIgnored var creatingRecord = false
    @ObservationIgnored var sessionGen = 0
    @ObservationIgnored var syncGen = 0
    @ObservationIgnored var syncTask: Task<Void, Never>?
    @ObservationIgnored var wipeAnswer: CheckedContinuation<WipeAnswer, Never>?
    @ObservationIgnored var updateCheckedAt: Date?

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = support.appendingPathComponent("PollMaster", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        templateStore = TemplateStore(root: root)
        recordStore = RecordStore(root: root)
        projectStore = ProjectStore(root: root)
        backups = BackupService(store: recordStore)
        currentProjectId = UserDefaults.standard.string(forKey: "currentProjectId")
        account = Self.storedAccount() ?? Self.forgetOrphanTokens()
        localDataResetPending = Self.resetPending(root: root)
        installBundledTemplates()
        reloadNow()
        network.onReconnect = { [weak self] in Task { await self?.syncProjects() } }
    }

    // MARK: templates

    /// Install shared templates on first launch and whenever a bundled version is missing; installed older versions stay.
    /// A template id never seen on this device is also added to the Phoenix projects already here.
    func installBundledTemplates() {
        let urls = Bundle.main.urls(forResourcesWithExtension: "json", subdirectory: "templates") ?? []
        let known = Set(templateStore.list().map(\.id))
        var fresh: [TemplateRef] = []
        for url in urls {
            do {
                let data = try Data(contentsOf: url)
                let t = try TemplateLoader.decode(data)
                if !templateStore.exists(id: t.id, version: t.version) {
                    try templateStore.install(data)
                    if !known.contains(t.id) { fresh.append(TemplateRef(t)) }
                }
            } catch { errorMessage = "Không cài được mẫu \(url.lastPathComponent): \(error.localizedDescription)" }
        }
        do { try ERPProjectSync.addNewTemplates(fresh, to: projectStore) }
        catch { errorMessage = "Không thêm được mẫu mới vào dự án Phoenix: \(error.localizedDescription)" }
    }

    /// Newest version of each template: what new records are created from.
    var newestTemplates: [Template] { TemplateVersion.newest(templates) }

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
        let users = projectStore.list().filter { $0.templates.contains(TemplateRef(t)) }
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
        let t = try TemplateLoader.decode(data)
        try templateStore.install(data)
        // open editors of records on this template version still hold the old definition: save them, close their screens, let them reload it
        closingEditors = closingEditors.filter { $0.value.template.id != t.id || $0.value.template.version != t.version }
        let stale = Set(editors.filter { $0.value.template.id == t.id && $0.value.template.version == t.version }.keys)
        stale.forEach { editors[$0]?.flush() }
        recordsPath.removeAll { stale.contains($0.recordId) }
        for id in stale { editors[id]?.close(); editors[id] = nil; closingEditors[id] = nil }
        reload()
    }

    func usedStorage() -> Int64 { RecordStore.directorySize(root) }
}
