import Foundation
import PollMasterCore

/// The record sync calls of the ERP backend (`project-polls.md`), over `ERPClient`'s token handling.
struct ERPSyncRemote: SyncRemote {
    let client: ERPClient
    private static let pageSize = "50"

    private struct Envelope: Decodable { var data: JSONValue?; var error: String?; var err_key: String? }

    private func path(_ pid: String, _ rest: String) -> String { "projects/\(pid)/\(rest)" }

    /// A record-level answer (not found / refused / too big) becomes a `SyncRecordFailure`; anything else stops the run.
    private func fail(_ status: Int, _ data: Data, _ fallback: String) -> Error {
        let message = (try? JSONDecoder().decode(Envelope.self, from: data))?.error
        if [400, 403, 404, 413].contains(status) { return SyncRecordFailure(ERPMessage.readable(message) ?? fallback) }
        return ERPError.server(ERPMessage.readable(message) ?? "Máy chủ ERP báo lỗi \(status).")
    }

    private func decode<T: Decodable>(_ type: T.Type, _ data: Data) throws -> T {
        guard let e = try? JSONDecoder().decode(ERPEnvelope<T>.self, from: data), let v = e.data else { throw ERPError.badResponse }
        return v
    }

    func pull(projectId: String, since: String?, cursor: String?) async throws -> SyncPage {
        var q = ["limit": Self.pageSize]
        if let since { q["since"] = since }
        if let cursor { q["cursor"] = cursor }
        let (data, status) = try await client.rawAuthorized(await client.request(path(projectId, "poll-sync"), query: q))
        guard status == 200 else { throw fail(status, data, "Không tìm thấy dự án trên ERP hoặc tài khoản không có quyền xem phiếu.") }
        return try decode(SyncPage.self, data)
    }

    func put(projectId: String, body: SyncPutBody, recordId: String) async throws -> SyncPutResult {
        let json = try JSONCoding.makeEncoder(pretty: false).encode(body)
        var r = await client.request(path(projectId, "poll-records/\(recordId)"), method: "PUT", body: json)
        r.timeoutInterval = 60
        let (data, status) = try await client.rawAuthorized(r)
        switch status {
        case 200: return .ok(try decode(SyncRecord.self, data))
        case 409:
            let e = try? JSONDecoder().decode(Envelope.self, from: data)
            if e?.err_key == "REV_CONFLICT", let cur = e?.data?.object?["current"],
               let bytes = try? JSONEncoder().encode(cur), let s = try? JSONDecoder().decode(SyncRecord.self, from: bytes) { return .conflict(s) }
            if e?.err_key == "MEDIA_MISSING", let list = e?.data?.object?["missing"]?.array { return .mediaMissing(list.compactMap(\.string)) }
            throw ERPError.badResponse
        default: throw fail(status, data, "Không có quyền sửa phiếu này hoặc phiếu không hợp lệ.")
        }
    }

    func putMedia(projectId: String, recordId: String, name: String, file: URL, sha256: String) async throws {
        var r = await client.request(path(projectId, "poll-records/\(recordId)/media/\(name)"), method: "PUT")
        r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        r.setValue(sha256, forHTTPHeaderField: "X-Content-SHA256")
        r.timeoutInterval = 300
        let (data, status) = try await client.rawAuthorized(r, bodyFile: file)
        guard (200..<300).contains(status) else { throw fail(status, data, status == 413 ? "Tệp \(name) lớn hơn 95 MB." : "Không gửi được tệp \(name).") }
    }

    func getMedia(projectId: String, recordId: String, name: String, to dest: URL) async throws {
        var r = await client.request(path(projectId, "poll-records/\(recordId)/media/\(name)"))
        r.timeoutInterval = 300
        let (data, status) = try await client.rawAuthorized(r, downloadTo: dest)
        guard status == 200 else { throw fail(status, data, "Không tải được tệp \(name).") }
    }

    func putPDF(projectId: String, recordId: String, file: URL) async throws {
        var r = await client.request(path(projectId, "poll-records/\(recordId)/pdf"), method: "PUT")
        r.setValue("application/pdf", forHTTPHeaderField: "Content-Type")
        r.timeoutInterval = 120
        let (data, status) = try await client.rawAuthorized(r, bodyFile: file)
        guard (200..<300).contains(status) else { throw fail(status, data, "Không gửi được PDF.") }
    }

    func delete(projectId: String, recordId: String) async throws {
        let (data, status) = try await client.rawAuthorized(await client.request(path(projectId, "poll-records/\(recordId)"), method: "DELETE"))
        guard (200..<300).contains(status) || status == 404 else { throw fail(status, data, "Không xoá được phiếu trên máy chủ.") }
    }
}
