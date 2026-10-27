import Foundation

/// The pure decisions of a sync run (schema §10, ux-spec N). Same rules on iOS and Android: both run `shared/sync-test-vectors.json`.
public enum SyncPlanner {
    public enum Action: String, Sendable { case create, overwrite, conflict, push, deleteLocal }

    /// What the decisions need to know about a record on the device.
    public struct Local: Equatable, Sendable {
        public var id: String
        public var createdBy: String?
        public var serverRev: Int?
        public var conflict: Bool
        public var dirty: Bool

        public init(id: String, createdBy: String? = nil, serverRev: Int? = nil, conflict: Bool = false, dirty: Bool) {
            self.id = id; self.createdBy = createdBy; self.serverRev = serverRev; self.conflict = conflict; self.dirty = dirty
        }

        public init(_ r: Record) {
            self.init(id: r.id, createdBy: r.createdBy, serverRev: r.serverRev, conflict: r.syncConflict, dirty: r.isSyncDirty)
        }
    }

    /// One action per record id; ids not in the result need nothing. `changed` = records the pull returned, `ids` = every record the
    /// server has in the project, `deleted` = ids waiting in pending-deletes (never brought back).
    public static func plan(local: [Local], changed: [SyncRef], ids: [SyncRef], deleted: Set<String>,
                            me: String, canPost: Bool, canManage: Bool) -> [String: Action] {
        let changedRev = Dictionary(changed.map { ($0.recordId, $0.rev) }, uniquingKeysWith: max)
        let onServer = Set(ids.map(\.recordId))
        var out: [String: Action] = [:]
        for l in local {
            if l.conflict { continue }                                  // waits for the user's choice
            if let mine = l.serverRev {
                if let theirs = changedRev[l.id], theirs > mine { out[l.id] = l.dirty ? .conflict : .overwrite; continue }
                if !onServer.contains(l.id) { out[l.id] = l.dirty ? .conflict : .deleteLocal; continue }
            }
            let mayEdit = canPost && (l.createdBy == nil || l.createdBy == me || canManage)
            if mayEdit, l.serverRev == nil || l.dirty { out[l.id] = .push }
        }
        let have = Set(local.map(\.id))
        for id in changedRev.keys where !have.contains(id) && !deleted.contains(id) { out[id] = .create }
        return out
    }

    /// Clean records of another account: removed from the device when a different account signs in (they are pulled again if visible).
    /// Dirty ones stay, untouched and never pushed.
    public static func staleAfterAccountSwitch(local: [Local], me: String) -> [String] {
        local.filter { !$0.dirty && $0.createdBy != nil && $0.createdBy != me }.map(\.id)
    }

    /// Clean records of a project the account can no longer see.
    public static func lostWithProject(local: [Local]) -> [String] { local.filter { !$0.dirty }.map(\.id) }

    // MARK: comparing with the server copy

    private static let bookkeeping: Set<String> = ["updatedAt", "lastPosition", "backupAt", "erpUploadedAt", "serverRev", "syncConflict",
                                                   "createdBy", "createdByName", "seq"]

    /// True when two record.json contents are the same apart from bookkeeping keys (and keys holding null).
    public static func sameContent(_ a: JSONValue, _ b: JSONValue) -> Bool { canonical(a, top: true) == canonical(b, top: true) }

    private static func canonical(_ v: JSONValue, top: Bool = false) -> JSONValue {
        switch v {
        case .object(let o):
            return .object(o.filter { !(top && bookkeeping.contains($0.key)) && !$0.value.isNull }.mapValues { canonical($0) })
        case .array(let a): return .array(a.map { canonical($0) })
        default: return v
        }
    }

    /// Videos are downloaded only when the record is opened; photos, audio and signatures at once.
    public static func isVideo(_ name: String) -> Bool {
        ["mp4", "mov", "m4v", "avi", "mkv", "webm"].contains((name as NSString).pathExtension.lowercased())
    }
}
