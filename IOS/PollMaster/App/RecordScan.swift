import Foundation
import UIKit
import PollMasterCore

/// What a pass over the disk produces. Built off the main actor, then assigned to `AppEnvironment` in one go.
struct LibraryScan: @unchecked Sendable {      // UIImage avatars are read-only after creation
    var templates: [Template]
    var projects: [Project]
    var avatars: [String: UIImage]
    var sites: [String: [Site]]
    var summaries: [RecordSummary]
}

enum RecordScan {
    /// Templates, active projects (with avatars and planned sites) and a summary of every record of those projects.
    static func library(templates: TemplateStore, projects projectStore: ProjectStore, records: RecordStore) -> LibraryScan {
        let templates = templates.list()
        let byKey = Dictionary(templates.map { ($0.key, $0) }, uniquingKeysWith: { a, _ in a })
        // ERP projects this account sees; the others stay on the device, hidden with their records.
        let projects = projectStore.list().filter { !$0.archived }
        let avatars = Dictionary(uniqueKeysWithValues: projects.compactMap { p in
            projectStore.avatarURL(p.id).flatMap { UIImage(contentsOfFile: $0.path) }.map { (p.id, $0) }
        })
        let visible = Set(projects.map(\.id))
        let sites = Dictionary(uniqueKeysWithValues: projects.map { ($0.id, projectStore.sites($0.id).load()) })
        let summaries = records.listItems().compactMap { item -> RecordSummary? in
            guard let r = item.record else {
                return RecordSummary(id: item.id, title: item.id, subtitle: item.error ?? "", updatedAt: item.modifiedAt,
                                     status: .draft, progress: RequiredProgress(done: 0, total: 0), loadError: item.error ?? "Không đọc được")
            }
            guard let pid = r.projectId, visible.contains(pid) else { return nil }
            return summary(r, template: byKey["\(r.templateId)@\(r.templateVersion)"], store: records)
        }
        return LibraryScan(templates: templates, projects: projects, avatars: avatars, sites: sites, summaries: summaries)
    }

    /// Row of the records list for a record of a visible project.
    static func summary(_ r: Record, template t: Template?, store: RecordStore) -> RecordSummary {
        let pid = r.projectId ?? ""
        guard let t else {
            return RecordSummary(id: r.id, title: "Phiếu mới · " + ISODate.format(r.createdAt, "dd/MM/yyyy HH:mm"),
                                 subtitle: "\(r.templateId) (thiếu mẫu)", updatedAt: r.updatedAt, status: r.status,
                                 progress: RequiredProgress(done: 0, total: 0), projectId: pid, templateId: r.templateId, returned: r.returned,
                                 needsBackup: r.needsBackup)
        }
        var visited = Set<String>()
        for f in t.allFields where f.lookup != nil { if let code = r.values[f.id]?.v?.string, !code.isEmpty { visited.insert(code) } }
        let gps = t.allFields.filter { $0.type == .gps }.compactMap { GPSValue(r.values[$0.id]?.v) }.first
        return RecordSummary(id: r.id, title: RecordNaming.title(t, r), subtitle: RecordInfo.subtitle(t, r), updatedAt: r.updatedAt,
                             status: r.status, progress: Validator.progress(t, r), projectId: pid, templateId: r.templateId, returned: r.returned,
                             needsBackup: r.needsBackup,
                             visitedSites: visited, thumbnail: RecordInfo.firstPhoto(t, r).map { store.fileURL(r.id, $0.file) },
                             coordinate: gps.map { GPSCoordinate(lat: $0.lat, lng: $0.lng) })
    }
}
