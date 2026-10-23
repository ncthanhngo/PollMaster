import Foundation
import Observation
import PollMasterCore

struct RecordSummary: Identifiable, Equatable {
    var id: String
    var title: String
    var templateTitle: String
    var updatedAt: Date
    var status: RecordStatus
    var progress: RequiredProgress
    /// Set when record.json could not be read ("Lỗi đọc"); such entries can only be deleted.
    var loadError: String?
}

enum ImportOutcome { case installed(Template), needsOverwrite(Template, Data) }

@MainActor @Observable
final class AppEnvironment {
    let root: URL
    let templateStore: TemplateStore
    let recordStore: RecordStore
    let settings = AppSettings()
    let location = LocationService()

    var templates: [Template] = []
    var summaries: [RecordSummary] = []
    var selectedTab: AppTab = .records
    var recordsPath: [Route] = []
    var errorMessage: String?
    @ObservationIgnored private var editors: [String: RecordEditorModel] = [:]

    init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        root = support.appendingPathComponent("PollMaster", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        templateStore = TemplateStore(root: root)
        recordStore = RecordStore(root: root)
        installBundledTemplates()
        reload()
    }

    // MARK: templates

    /// Install shared templates on first launch (and when a newer bundled version appears).
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
        summaries = recordStore.listItems().map { item in
            guard let r = item.record else {
                return RecordSummary(id: item.id, title: item.id, templateTitle: item.error ?? "", updatedAt: item.modifiedAt,
                                     status: .draft, progress: RequiredProgress(done: 0, total: 0), loadError: item.error ?? "Không đọc được")
            }
            let t = byKey["\(r.templateId)@\(r.templateVersion)"]
            return RecordSummary(id: r.id, title: t.map { RecordNaming.title($0, r) } ?? r.templateId,
                                 templateTitle: t?.title ?? "\(r.templateId) (thiếu mẫu)", updatedAt: r.updatedAt,
                                 status: r.status, progress: t.map { Validator.progress($0, r) } ?? RequiredProgress(done: 0, total: 0))
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

    func createRecord(from t: Template) {
        let r = RecordFactory.make(template: t, surveyorName: settings.surveyorName)
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
        let m = RecordEditorModel(template: t, record: r, store: recordStore, location: location, settings: settings)
        editors[id] = m
        return m
    }

    func usedStorage() -> Int64 { RecordStore.directorySize(root) }
}
