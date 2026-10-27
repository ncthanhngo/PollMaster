import Foundation

/// Wire types of the record sync API (backend `project-polls.md`, schema §10.2).
public struct SyncMediaInfo: Decodable, Equatable, Sendable {
    public var name: String
    public var sha256: String
    public var size: Int64

    public init(name: String, sha256: String, size: Int64 = 0) { self.name = name; self.sha256 = sha256; self.size = size }
}

public struct SyncRecord: Decodable, Equatable, Sendable {
    public var recordId: String
    public var rev: Int
    public var seq: Int?
    public var createdBy: String?
    public var createdByName: String?
    public var data: JSONValue
    public var media: [SyncMediaInfo]

    public init(recordId: String, rev: Int, seq: Int? = nil, createdBy: String? = nil, createdByName: String? = nil,
                data: JSONValue = .null, media: [SyncMediaInfo] = []) {
        self.recordId = recordId; self.rev = rev; self.seq = seq; self.createdBy = createdBy; self.createdByName = createdByName
        self.data = data; self.media = media
    }

    enum CodingKeys: String, CodingKey {
        case recordId = "record_id", rev, seq, createdBy = "created_by", createdByName = "created_by_name", data, media
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        recordId = try c.decode(String.self, forKey: .recordId)
        rev = try c.decode(Int.self, forKey: .rev)
        seq = c.lenient(Int.self, .seq)
        createdBy = c.lenient(String.self, .createdBy)?.nilIfEmpty
        createdByName = c.lenient(String.self, .createdByName)?.nilIfEmpty
        data = try c.decode(JSONValue.self, forKey: .data)
        media = c.lenient([SyncMediaInfo].self, .media) ?? []
    }
}

public struct SyncRef: Decodable, Equatable, Sendable {
    public var recordId: String
    public var rev: Int
    public init(recordId: String, rev: Int) { self.recordId = recordId; self.rev = rev }
    enum CodingKeys: String, CodingKey { case recordId = "record_id", rev }
}

/// One page of `GET poll-sync`. `ids` is filled on the first page only; `nextCursor` is nil on the last page.
public struct SyncPage: Decodable, Equatable, Sendable {
    public var serverTime: String
    public var canPost: Bool
    public var canManage: Bool
    public var records: [SyncRecord]
    public var ids: [SyncRef]
    public var nextCursor: String?

    public init(serverTime: String, canPost: Bool = true, canManage: Bool = false, records: [SyncRecord] = [], ids: [SyncRef] = [],
                nextCursor: String? = nil) {
        self.serverTime = serverTime; self.canPost = canPost; self.canManage = canManage
        self.records = records; self.ids = ids; self.nextCursor = nextCursor
    }

    enum CodingKeys: String, CodingKey {
        case serverTime = "server_time", canPost = "can_post", canManage = "can_manage", records, ids, nextCursor = "next_cursor"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        serverTime = try c.decode(String.self, forKey: .serverTime)
        canPost = c.lenient(Bool.self, .canPost) ?? false
        canManage = c.lenient(Bool.self, .canManage) ?? false
        records = c.lenient([SyncRecord].self, .records) ?? []
        ids = c.lenient([SyncRef].self, .ids) ?? []
        nextCursor = c.lenient(String.self, .nextCursor)?.nilIfEmpty
    }
}

/// Body of `PUT poll-records/:recordId`.
public struct SyncPutBody: Encodable, Equatable, Sendable {
    public var baseRev: Int
    public var title: String
    public var recordCode: String?
    public var templateTitle: String
    /// `"media/x.jpg"` -> lower-case sha256.
    public var media: [String: String]
    public var data: JSONValue

    enum CodingKeys: String, CodingKey { case baseRev = "base_rev", title, recordCode = "record_code", templateTitle = "template_title", media, data }
}

public enum SyncPutResult: Equatable, Sendable {
    case ok(SyncRecord)
    /// 409 `REV_CONFLICT`: the server copy.
    case conflict(SyncRecord)
    /// 409 `MEDIA_MISSING`: names (`media/…`) the server lacks.
    case mediaMissing([String])
}

/// A problem of one record (shown on it as "Lỗi: …"); the rest of the run goes on. Any other error ends the run (offline, session expired).
public struct SyncRecordFailure: Error, Equatable, LocalizedError, Sendable {
    public var message: String
    public init(_ message: String) { self.message = message }
    public var errorDescription: String? { message }
}

/// The ERP side of a sync run. The app implements it over HTTP; tests use a fake. Non-GET calls are only made when allowed.
public protocol SyncRemote: Sendable {
    func pull(projectId: String, since: String?, cursor: String?) async throws -> SyncPage
    func put(projectId: String, body: SyncPutBody, recordId: String) async throws -> SyncPutResult
    func putMedia(projectId: String, recordId: String, name: String, file: URL, sha256: String) async throws
    /// Writes the file to `dest`.
    func getMedia(projectId: String, recordId: String, name: String, to dest: URL) async throws
    func putPDF(projectId: String, recordId: String, file: URL) async throws
    /// 204 and 404 both return normally.
    func delete(projectId: String, recordId: String) async throws
}
