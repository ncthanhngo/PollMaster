import CryptoKit
import Foundation

enum PushOutcome { case pushed, conflict, replaced, skipped }

/// Largest media file the server takes (Cloudflare caps a request at 100 MB).
let maxMediaBytes: Int64 = 95 * 1024 * 1024

extension SyncEngine {
    private struct LocalMedia { var name: String; var url: URL; var sha: String }

    /// Pushes one record (schema §10.2): PUT record, upload the media the server lacks, PUT again, then remember the answer.
    /// `force` ("Giữ bản của tôi"): on a revision conflict the push is repeated on top of the server's revision.
    func push(_ id: String, project: Project, force: Bool = false) async throws -> PushOutcome {
        guard let record = records.load(id) else { return .skipped }
        guard let template = templates.load(id: record.templateId, version: record.templateVersion) else {
            throw SyncRecordFailure("Thiếu mẫu \(record.templateId) \(record.templateVersion) trên máy.")
        }
        let startedAt = now()
        let files = try await localMedia(project.id, record)
        var body = SyncPutBody(baseRev: record.serverRev ?? 0, title: RecordNaming.title(template, record),
                               recordCode: RecordNaming.rawRecordCode(template, record), templateTitle: template.title,
                               media: Dictionary(files.map { ($0.name, $0.sha) }, uniquingKeysWith: { a, _ in a }), data: try SyncApply.pushData(record))
        var result = try await remote.put(projectId: project.id, body: body, recordId: id)
        if force, case .conflict(let current) = result {
            body.baseRev = current.rev
            result = try await remote.put(projectId: project.id, body: body, recordId: id)
        }
        if case .mediaMissing(let names) = result {
            for name in names {
                guard let f = files.first(where: { $0.name == name }) else { throw SyncRecordFailure("Thiếu tệp \(name) trên máy.") }
                try await remote.putMedia(projectId: project.id, recordId: id, name: Self.flatName(name), file: f.url, sha256: f.sha)
            }
            result = try await remote.put(projectId: project.id, body: body, recordId: id)
        }
        switch result {
        case .ok(let s):
            let saved = await update(id) { SyncApply.pushed(&$0, projectId: project.id, response: s, at: startedAt) }
            // the web view only needs a PDF of a finished survey
            if let saved, saved.status == .completed, let make = hooks.makePDF, let pdf = await make(saved) { try? await remote.putPDF(projectId: project.id, recordId: id, file: pdf) }
            return .pushed
        case .conflict(let current): return try await resolveConflict(current, id: id, project: project, startedAt: startedAt)
        case .mediaMissing(let names): throw SyncRecordFailure("Máy chủ chưa nhận được tệp \(names.first ?? "")")
        }
    }

    /// 409 `REV_CONFLICT`. A record never synced here (sent by an older build) is matched with the server copy; otherwise the user decides.
    private func resolveConflict(_ current: SyncRecord, id: String, project: Project, startedAt: Date) async throws -> PushOutcome {
        let pid = project.id
        let fresh = records.load(id)
        if let local = fresh, local.serverRev == nil {
            if SyncPlanner.sameContent(try SyncApply.pushData(local), current.data) {
                _ = await update(id) { SyncApply.pushed(&$0, projectId: pid, response: current, at: startedAt) }
                return .pushed
            }
            if local.erpUploadedAt != nil, !local.isSyncDirty {       // untouched since the old upload: the server copy wins
                guard await !hooks.isOpen(id) else { return .skipped }
                let server = try SyncApply.record(from: current, projectId: pid)
                try records.save(SyncApply.overwrite(local, with: server))
                records.pruneMedia(server)
                _ = await fetchMedia(pid, server, info: current.media, includeVideo: false)
                return .replaced
            }
        }
        _ = await update(id) { $0.syncConflict = true; return true }
        return .conflict
    }

    // MARK: media

    /// Name inside `media/`, as used in URLs.
    static func flatName(_ rel: String) -> String { String(rel.dropFirst("media/".count)) }

    private static func isUploadable(_ rel: String) -> Bool {
        rel.hasPrefix("media/") && !flatName(rel).isEmpty && !flatName(rel).contains("/")
    }

    /// Existing media files of the record with their hashes; files missing here are fetched from the server first.
    private func localMedia(_ pid: String, _ record: Record) async throws -> [LocalMedia] {
        var out: [LocalMedia] = []
        for rel in Set(RecordStore.mediaFiles(of: record)).sorted() where Self.isUploadable(rel) {
            let url = records.fileURL(record.id, rel)
            if !FileManager.default.fileExists(atPath: url.path) {
                do { try await download(pid, record.id, rel, expected: nil) }
                catch is SyncRecordFailure { throw SyncRecordFailure("Chưa có tệp \(Self.flatName(rel)) trên máy.") }
            }
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            if size > maxMediaBytes { throw SyncRecordFailure("Tệp \(Self.flatName(rel)) lớn hơn 95 MB.") }
            guard let sha = try? Self.sha256(of: url) else { throw SyncRecordFailure("Không đọc được tệp \(Self.flatName(rel)).") }
            out.append(LocalMedia(name: rel, url: url, sha: sha))
        }
        return out
    }

    /// Downloads the record's media that are missing here (or differ from `info`). Videos only with `includeVideo`. Never throws:
    /// returns false when something is left for the next run (offline, error).
    @discardableResult
    func fetchMedia(_ pid: String, _ record: Record, info: [SyncMediaInfo] = [], includeVideo: Bool) async -> Bool {
        let want = Dictionary(info.map { ($0.name, $0.sha256.lowercased()) }, uniquingKeysWith: { a, _ in a })
        for rel in Set(RecordStore.mediaFiles(of: record)).sorted() where Self.isUploadable(rel) {
            if !includeVideo && SyncPlanner.isVideo(rel) { continue }
            let dest = records.fileURL(record.id, rel)
            if FileManager.default.fileExists(atPath: dest.path) {
                guard let sha = want[rel], (try? Self.sha256(of: dest)) != sha else { continue }
            }
            do { try await download(pid, record.id, rel, expected: want[rel]) }
            catch is SyncRecordFailure { continue }       // gone or refused: the next run tries again, others go on
            catch { return false }                         // offline: stop
        }
        return true
    }

    /// Opening a record: its videos (and anything else missing) come down now.
    public func downloadMedia(projectId: String, recordId: String) async {
        guard let r = records.load(recordId) else { return }
        await fetchMedia(projectId, r, includeVideo: true)
    }

    private func download(_ pid: String, _ recordId: String, _ rel: String, expected: String?) async throws {
        let dest = records.fileURL(recordId, rel), tmp = dest.appendingPathExtension("part")
        try FileManager.default.createDirectory(at: dest.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: tmp)
        defer { try? FileManager.default.removeItem(at: tmp) }
        try await remote.getMedia(projectId: pid, recordId: recordId, name: Self.flatName(rel), to: tmp)
        if let expected, (try? Self.sha256(of: tmp)) != expected { throw SyncRecordFailure("Tệp \(Self.flatName(rel)) tải về bị lỗi.") }
        if FileManager.default.fileExists(atPath: dest.path) { _ = try FileManager.default.replaceItemAt(dest, withItemAt: tmp) }
        else { try FileManager.default.moveItem(at: tmp, to: dest) }
    }

    static func sha256(of url: URL) throws -> String {
        let h = try FileHandle(forReadingFrom: url)
        defer { try? h.close() }
        var hasher = SHA256()
        while let chunk = try h.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }
}
