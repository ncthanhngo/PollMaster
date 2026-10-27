import Foundation
import PollMasterCore

/// Keeping templates, projects and record summaries current without reading the disk on the main actor.
extension AppEnvironment {
    /// Rescans the disk in the background; the lists are replaced when the result arrives.
    func reload() { Task { await refresh() } }

    /// Same, for the caller that can wait. A request during a running scan makes it run once more (its result may be stale).
    func refresh() async {
        if scanning { rescanNeeded = true; return }
        flushEditors()
        scanning = true
        defer { scanning = false }
        repeat {
            flushEditors()
            rescanNeeded = false
            let (templates, projects, records, me) = (templateStore, projectStore, recordStore, currentUserId)
            let scan = await Task.detached(priority: .userInitiated) {
                RecordScan.library(templates: templates, projects: projects, records: records, me: me)
            }.value
            if !rescanNeeded { apply(scan) }
        } while rescanNeeded
    }

    /// Launch (and DEBUG launch aids): the first lists must exist before the first screen.
    func reloadNow() { apply(RecordScan.library(templates: templateStore, projects: projectStore, records: recordStore, me: currentUserId)) }

    func flushEditors() { editors.values.forEach { $0.flush() } }

    private func apply(_ scan: LibraryScan) {
        templates = scan.templates
        projects = scan.projects
        projectAvatars = scan.avatars
        siteCache = scan.sites
        reloadDrafts()
        if let id = currentProjectId, !projects.contains(where: { $0.id == id }) { currentProjectId = nil }
        summaries = scan.summaries
        rebuildDerived()
        refreshEditorAccess()
    }

    /// Per-project counts and visited site codes follow from the summaries.
    private func rebuildDerived() {
        var visited: [String: Set<String>] = [:], stats: [String: ProjectStats] = [:]
        for s in summaries where s.loadError == nil {
            stats[s.projectId, default: ProjectStats()].add(status: s.status, returned: s.returned, updatedAt: s.updatedAt)
            if !s.visitedSites.isEmpty { visited[s.projectId, default: []].formUnion(s.visitedSites) }
        }
        visitedByProject = visited; projectStats = stats
    }

    /// Creates or refreshes the list row of one record (it was saved, created, moved) without rescanning the folder.
    func upsert(_ r: Record) {
        if scanning { rescanNeeded = true }
        guard let pid = r.projectId, let p = projects.first(where: { $0.id == pid }),
              !RecordScan.isLeftover(r, me: currentUserId, manages: p.canManage) else { return removeSummary(r.id) }
        let s = RecordScan.summary(r, template: templates.first { $0.id == r.templateId && $0.version == r.templateVersion }, store: recordStore)
        if let i = summaries.firstIndex(where: { $0.id == r.id }) { summaries[i] = s } else { summaries.append(s) }
        summaries.sort { $0.updatedAt > $1.updatedAt }
        rebuildDerived()
    }

    func removeSummary(_ id: String) {
        if scanning { rescanNeeded = true }
        summaries.removeAll { $0.id == id }
        rebuildDerived()
    }
}
