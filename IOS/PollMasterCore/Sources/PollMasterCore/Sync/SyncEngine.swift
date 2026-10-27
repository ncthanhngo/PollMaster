import Foundation

/// Connections the engine needs from the app: the open editors and the PDF maker. Defaults suit tests and headless use.
public struct SyncHooks: Sendable {
    /// A record open in an editor is never overwritten by a pull; its sync is retried after the editor closes.
    public var isOpen: @Sendable (String) async -> Bool
    /// Applies `change` to the current copy of a record (the open editor's, else the stored one) and saves it; `change` returns false to
    /// leave it alone. Returns the saved record, nil when it is gone. Nil hook = work on the stored file.
    public var update: (@Sendable (String, @Sendable (inout Record) -> Bool) async -> Record?)?
    /// PDF of a record for the web view (best effort).
    public var makePDF: (@Sendable (Record) async -> URL?)?
    public var progress: @Sendable (_ done: Int, _ total: Int) -> Void

    public init(isOpen: @escaping @Sendable (String) async -> Bool = { _ in false },
                update: (@Sendable (String, @Sendable (inout Record) -> Bool) async -> Record?)? = nil,
                makePDF: (@Sendable (Record) async -> URL?)? = nil,
                progress: @escaping @Sendable (Int, Int) -> Void = { _, _ in }) {
        self.isOpen = isOpen; self.update = update; self.makePDF = makePDF; self.progress = progress
    }
}

/// What one project's run did. `failures` maps record id to a short sentence for the user.
public struct SyncReport: Equatable, Sendable {
    public var created = 0, overwritten = 0, pushed = 0, conflicts = 0, deletedLocal = 0, deletedRemote = 0
    public var failures: [String: String] = [:]
    /// The server refused the project as a whole (no access).
    public var projectError: String?
    /// Something was left for later (open record, unreadable copy, media): the pull cursor stays where it was.
    public var incomplete = false

    public init() {}
}

/// The record sync of schema §10 / phase 05: pull -> decide -> push -> pending deletes -> media. One run per project at a time.
public struct SyncEngine: Sendable {
    let records: RecordStore
    let projects: ProjectStore
    let templates: TemplateStore
    let pending: PendingDeletes
    let remote: any SyncRemote
    let hooks: SyncHooks
    let now: @Sendable () -> Date

    public init(root: URL, remote: any SyncRemote, hooks: SyncHooks = SyncHooks(), now: @escaping @Sendable () -> Date = { Date() }) {
        records = RecordStore(root: root); projects = ProjectStore(root: root); templates = TemplateStore(root: root)
        pending = PendingDeletes(root: root); self.remote = remote; self.hooks = hooks; self.now = now
    }

    /// Removes what the account no longer owns: clean records of another account (`lastUserId` differs from `me`) and clean records
    /// of projects the account lost. Returns the removed ids.
    @discardableResult
    public func prune(me: String, lastUserId: String?, lostProjects: Set<String>) async -> [String] {
        let all = records.list()
        var ids = Set<String>()
        if let last = lastUserId, last != me { ids.formUnion(SyncPlanner.staleAfterAccountSwitch(local: all.map(SyncPlanner.Local.init), me: me)) }
        for p in lostProjects {
            ids.formUnion(SyncPlanner.lostWithProject(local: all.filter { $0.projectId == p }.map(SyncPlanner.Local.init)))
        }
        var removed: [String] = []
        for id in ids.sorted() where await !hooks.isOpen(id) {
            if (try? records.delete(id)) != nil { removed.append(id) }
        }
        return removed
    }

    /// Syncs the projects one after the other. A record-level problem lands in the report; an error that stops everything (offline,
    /// expired session) is thrown.
    public func sync(projects ids: [String], me: String) async throws -> [String: SyncReport] {
        var byProject = Dictionary(grouping: records.list(), by: { $0.projectId ?? "" })
        var out: [String: SyncReport] = [:]
        for pid in ids {
            try Task.checkCancellation()
            out[pid] = try await syncProject(pid, me: me, local: byProject.removeValue(forKey: pid) ?? [])
        }
        return out
    }

    func syncProject(_ pid: String, me: String, local: [Record]) async throws -> SyncReport {
        var rep = SyncReport()
        guard let project = projects.load(pid), !project.archived else { return rep }
        let pull: Pulled
        do { pull = try await pullAll(project) } catch let f as SyncRecordFailure { rep.projectError = f.message; return rep }
        if project.canPost != pull.canPost || project.canManage != pull.canManage {
            var p = project
            p.canPost = pull.canPost; p.canManage = pull.canManage
            try projects.save(p)
        }
        let waiting = Set(pending.load().filter { $0.projectId == pid }.map(\.recordId))
        var byId = Dictionary(local.map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        let plan = SyncPlanner.plan(local: local.map(SyncPlanner.Local.init), changed: pull.records.map { SyncRef(recordId: $0.recordId, rev: $0.rev) },
                                    ids: pull.ids, deleted: waiting, me: me, canPost: pull.canPost, canManage: pull.canManage)
        let served = Dictionary(pull.records.map { ($0.recordId, $0) }, uniquingKeysWith: { _, b in b })
        var toPush: [String] = []
        for (id, action) in plan.sorted(by: { $0.key < $1.key }) {
            if action == .push { toPush.append(id); continue }
            if await hooks.isOpen(id) { rep.incomplete = true; continue }
            do {
                switch action {
                case .create, .overwrite:
                    guard let s = served[id] else { continue }
                    var r = try SyncApply.record(from: s, projectId: pid)
                    if let old = byId[id] { r = SyncApply.overwrite(old, with: r) }
                    try records.save(r)
                    records.pruneMedia(r)
                    byId[id] = r
                    if action == .create { rep.created += 1 } else { rep.overwritten += 1 }
                    if await !fetchMedia(pid, r, info: s.media, includeVideo: false) { rep.incomplete = true }
                case .conflict:
                    byId[id]?.syncConflict = true
                    _ = await update(id) { $0.syncConflict = true; return true }
                    rep.conflicts += 1
                case .deleteLocal:
                    try records.delete(id)
                    byId[id] = nil
                    rep.deletedLocal += 1
                case .push: break
                }
            } catch let f as SyncRecordFailure { rep.failures[id] = f.message; rep.incomplete = true }
        }
        for (i, id) in toPush.enumerated() {
            try Task.checkCancellation()
            hooks.progress(i, toPush.count)
            do {
                switch try await push(id, project: project) {
                case .pushed: rep.pushed += 1
                case .conflict: rep.conflicts += 1
                case .replaced: rep.overwritten += 1
                case .skipped: break
                }
            } catch let f as SyncRecordFailure { rep.failures[id] = f.message }
        }
        if !toPush.isEmpty { hooks.progress(toPush.count, toPush.count) }
        for item in pending.load() where item.projectId == pid {
            do {
                try await remote.delete(projectId: pid, recordId: item.recordId)
                try? pending.remove(item)
                rep.deletedRemote += 1
            } catch let f as SyncRecordFailure { rep.failures[item.recordId] = f.message }
        }
        for r in byId.values where r.serverRev != nil && !r.isSyncDirty && !r.syncConflict {
            if await !fetchMedia(pid, r, includeVideo: false) { rep.incomplete = true; break }
        }
        if !rep.incomplete, var p = projects.load(pid) {
            p.recordsSyncedAt = pull.serverTime
            try projects.save(p)
        }
        return rep
    }

    struct Pulled { var serverTime: String; var canPost: Bool; var canManage: Bool; var records: [SyncRecord]; var ids: [SyncRef] }

    /// Every page of `poll-sync` since the project's cursor. `ids` and `server_time` come from the first page.
    func pullAll(_ project: Project) async throws -> Pulled {
        var page = try await remote.pull(projectId: project.id, since: project.recordsSyncedAt, cursor: nil)
        var out = Pulled(serverTime: page.serverTime, canPost: page.canPost, canManage: page.canManage, records: page.records, ids: page.ids)
        var seen = Set<String>()
        while let next = page.nextCursor, seen.insert(next).inserted {
            try Task.checkCancellation()
            page = try await remote.pull(projectId: project.id, since: project.recordsSyncedAt, cursor: next)
            out.records += page.records
        }
        return out
    }

    func update(_ id: String, _ change: @escaping @Sendable (inout Record) -> Bool) async -> Record? {
        if let hook = hooks.update { return await hook(id, change) }
        guard var r = records.load(id) else { return nil }
        if change(&r) { try? records.save(r) }
        return r
    }
}
