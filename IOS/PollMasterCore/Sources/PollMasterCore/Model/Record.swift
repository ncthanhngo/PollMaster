import Foundation

public enum RecordStatus: String, Codable, Sendable { case draft, completed }
public enum FieldFlag: String, Codable, Sendable { case estimate, notMeasurable }

extension KeyedDecodingContainer {
    /// Missing key or wrongly typed value -> nil (one bad value must not lose the rest of the record).
    func lenient<T: Decodable>(_ type: T.Type, _ key: Key) -> T? { (try? decodeIfPresent(type, forKey: key)) ?? nil }
}

public struct Media: Codable, Equatable, Sendable, Hashable {
    public var file: String
    public var takenAt: Date
    public var lat: Double?
    public var lng: Double?

    public init(file: String, takenAt: Date = Date(), lat: Double? = nil, lng: Double? = nil) {
        self.file = file; self.takenAt = takenAt; self.lat = lat; self.lng = lng
    }

    enum CodingKeys: String, CodingKey { case file, takenAt, lat, lng }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        file = c.lenient(String.self, .file) ?? ""
        takenAt = c.lenient(Date.self, .takenAt) ?? Date()
        lat = c.lenient(Double.self, .lat); lng = c.lenient(Double.self, .lng)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(file, forKey: .file); try c.encode(takenAt, forKey: .takenAt)
        try c.encode(lat, forKey: .lat); try c.encode(lng, forKey: .lng) // explicit null per schema
    }

    public var fileName: String { (file as NSString).lastPathComponent }
}

extension Media: Identifiable { public var id: String { file } }

public struct FieldValue: Codable, Equatable, Sendable {
    public var v: JSONValue?
    public var flag: FieldFlag?
    public var reason: String?
    public var note: String?
    public var other: [String: String]?
    public var parts: [String: JSONValue]?
    public var media: [Media]?
    /// Checker's note (schema §7.4).
    public var review: String?

    public init(v: JSONValue? = nil, flag: FieldFlag? = nil, reason: String? = nil, note: String? = nil,
                other: [String: String]? = nil, parts: [String: JSONValue]? = nil, media: [Media]? = nil, review: String? = nil) {
        self.v = v; self.flag = flag; self.reason = reason; self.note = note
        self.other = other; self.parts = parts; self.media = media; self.review = review
    }

    enum CodingKeys: String, CodingKey { case v, flag, reason, note, other, parts, media, review }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        v = c.lenient(JSONValue.self, .v)
        flag = c.lenient(FieldFlag.self, .flag)
        reason = c.lenient(String.self, .reason)
        note = c.lenient(String.self, .note)
        other = c.lenient([String: String].self, .other)
        parts = c.lenient([String: JSONValue].self, .parts)
        media = c.lenient([Media].self, .media)
        review = c.lenient(String.self, .review)
        normalize()
    }

    /// Drop empty keys as the schema asks ("bỏ key khi rỗng").
    public mutating func normalize() {
        if v?.isEmpty == true { v = nil }
        if reason?.isEmpty == true { reason = nil }
        if note?.isEmpty == true { note = nil }
        other = other?.filter { !$0.value.isEmpty }
        if other?.isEmpty == true { other = nil }
        parts = parts?.filter { !$0.value.isEmpty }
        if parts?.isEmpty == true { parts = nil }
        if media?.isEmpty == true { media = nil }
        if review?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true { review = nil }
    }

    public func encode(to encoder: Encoder) throws {
        var n = self; n.normalize()
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encodeIfPresent(n.v, forKey: .v); try c.encodeIfPresent(n.flag, forKey: .flag)
        try c.encodeIfPresent(n.reason, forKey: .reason); try c.encodeIfPresent(n.note, forKey: .note)
        try c.encodeIfPresent(n.other, forKey: .other); try c.encodeIfPresent(n.parts, forKey: .parts)
        try c.encodeIfPresent(n.media, forKey: .media); try c.encodeIfPresent(n.review, forKey: .review)
    }

    /// Nothing at all stored (no value, flag, note, parts, media).
    public var isBlank: Bool {
        var n = self; n.normalize()
        return n.v == nil && n.flag == nil && n.reason == nil && n.note == nil && n.other == nil && n.parts == nil && n.media == nil && n.review == nil
    }

    /// Element `i` of a samples value.
    public func sample(_ i: Int) -> JSONValue {
        guard let a = v?.array, i < a.count else { return .null }
        return a[i]
    }

    public mutating func setSample(_ i: Int, count: Int, to value: JSONValue?) {
        var a = v?.array ?? []
        while a.count < count { a.append(.null) }
        a[i] = value ?? .null
        v = a.allSatisfy { $0.isNull } ? nil : .array(a)
    }

    /// Media list stored in `v` for photo/video fields.
    public var mediaInV: [Media] {
        get {
            // Item by item: one unreadable entry must not hide the whole list.
            (v?.array ?? []).compactMap { item in
                guard item.object != nil, let data = try? JSONCoding.makeEncoder().encode(item) else { return nil }
                return try? JSONCoding.makeDecoder().decode(Media.self, from: data)
            }
        }
        set {
            guard !newValue.isEmpty, let data = try? JSONCoding.makeEncoder().encode(newValue),
                  let value = try? JSONCoding.makeDecoder().decode(JSONValue.self, from: data) else { v = nil; return }
            v = value
        }
    }
}

public struct Row: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var values: [String: FieldValue]
    public var stopwatchStart: Date?

    public init(id: String = UUID().uuidString, values: [String: FieldValue] = [:], stopwatchStart: Date? = nil) {
        self.id = id; self.values = values; self.stopwatchStart = stopwatchStart
    }

    enum CodingKeys: String, CodingKey { case id, values, stopwatchStart }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        values = c.lenient([String: FieldValue].self, .values) ?? [:]
        stopwatchStart = c.lenient(Date.self, .stopwatchStart)
    }

    /// A row is non-empty when at least one column has content.
    public var isEmpty: Bool { values.values.allSatisfy { $0.isBlank } }
}

public struct SignatureEntry: Codable, Equatable, Sendable {
    public var file: String
    public var name: String
    public var signedAt: Date?

    public init(file: String, name: String, signedAt: Date? = Date()) {
        self.file = file; self.name = name; self.signedAt = signedAt
    }

    enum CodingKeys: String, CodingKey { case file, name, signedAt }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        file = c.lenient(String.self, .file) ?? ""
        name = c.lenient(String.self, .name) ?? ""
        signedAt = c.lenient(Date.self, .signedAt)
    }
}

/// Timestamp strings exactly as read from record.json, so exports can copy them verbatim. Never affects equality.
public struct RawTimes: Equatable, Sendable {
    public var created: String?
    public var updated: String?
    public var completed: String?
    public static func == (_: RawTimes, _: RawTimes) -> Bool { true }
}

/// Where the user last worked (section id + field id or table id).
public struct LastPosition: Codable, Equatable, Sendable {
    public var section: String
    public var field: String
    public init(section: String, field: String) { self.section = section; self.field = field }

    enum CodingKeys: String, CodingKey { case section, field }
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        section = try c.decode(String.self, forKey: .section)
        field = try c.decode(String.self, forKey: .field)
    }
}

public struct Record: Codable, Equatable, Sendable, Identifiable {
    public var schemaVersion: Int = 1
    public var id: String
    public var templateId: String
    public var templateVersion: String
    public var status: RecordStatus
    public var createdAt: Date
    public var updatedAt: Date
    public var completedAt: Date?
    public var values: [String: FieldValue]
    public var tables: [String: [Row]]
    public var signatures: [String: SignatureEntry]
    /// Handed back by the checker for corrections (status is draft again); cleared on completion.
    public var returned: Bool = false
    public var lastPosition: LastPosition?
    /// Last automatic backup; the record counts as unsaved when `updatedAt` is later.
    public var backupAt: Date?
    /// Last successful "Gửi lên ERP" (schema §9.6); bookkeeping, never bumps `updatedAt`.
    public var erpUploadedAt: Date?
    public var rawTimes = RawTimes()
    /// Owning project (schema §9.1); nil only for records written before projects existed (read as "Chung").
    public var projectId: String?
    /// Number among the project's records of the same template ("01 - …" in the title); nil for records made before numbering.
    public var seq: Int?
    /// ERP `user.id` of the creator (schema §10.1); nil = not on the server yet (the signed-in account is the creator).
    public var createdBy: String?
    public var createdByName: String?
    /// `rev` of the server copy this one is based on; nil = never on the server.
    public var serverRev: Int?
    /// Device and server both changed (or the server deleted a record still edited here); waits for the user's choice.
    public var syncConflict: Bool = false

    enum CodingKeys: String, CodingKey {
        case schemaVersion, id, templateId, templateVersion, status, createdAt, updatedAt, completedAt, values, tables, signatures
        case returned, lastPosition, backupAt, erpUploadedAt, projectId, seq, createdBy, createdByName, serverRev, syncConflict
    }

    /// Every key except `id` is optional on read (Android omits keys).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        schemaVersion = c.lenient(Int.self, .schemaVersion) ?? 1
        templateId = c.lenient(String.self, .templateId) ?? ""
        templateVersion = c.lenient(String.self, .templateVersion) ?? ""
        status = c.lenient(RecordStatus.self, .status) ?? .draft
        let now = Date()
        completedAt = c.lenient(Date.self, .completedAt)
        createdAt = c.lenient(Date.self, .createdAt) ?? c.lenient(Date.self, .updatedAt) ?? now
        updatedAt = c.lenient(Date.self, .updatedAt) ?? createdAt
        values = c.lenient([String: FieldValue].self, .values) ?? [:]
        tables = c.lenient([String: [Row]].self, .tables) ?? [:]
        signatures = c.lenient([String: SignatureEntry].self, .signatures) ?? [:]
        returned = c.lenient(Bool.self, .returned) ?? false
        lastPosition = c.lenient(LastPosition.self, .lastPosition)
        backupAt = c.lenient(Date.self, .backupAt)
        erpUploadedAt = c.lenient(Date.self, .erpUploadedAt)
        projectId = c.lenient(String.self, .projectId)?.nilIfEmpty
        seq = c.lenient(Int.self, .seq)
        createdBy = c.lenient(String.self, .createdBy)?.nilIfEmpty
        createdByName = c.lenient(String.self, .createdByName)?.nilIfEmpty
        serverRev = c.lenient(Int.self, .serverRev)
        syncConflict = c.lenient(Bool.self, .syncConflict) ?? false
        rawTimes = RawTimes(created: c.lenient(String.self, .createdAt), updated: c.lenient(String.self, .updatedAt),
                            completed: c.lenient(String.self, .completedAt))
    }

    public init(id: String = UUID().uuidString, templateId: String, templateVersion: String, status: RecordStatus = .draft,
                createdAt: Date = Date(), values: [String: FieldValue] = [:], tables: [String: [Row]] = [:],
                signatures: [String: SignatureEntry] = [:], projectId: String? = nil) {
        self.projectId = projectId
        self.id = id; self.templateId = templateId; self.templateVersion = templateVersion; self.status = status
        self.createdAt = createdAt; self.updatedAt = createdAt; self.completedAt = nil
        self.values = values; self.tables = tables; self.signatures = signatures
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(schemaVersion, forKey: .schemaVersion); try c.encode(id, forKey: .id)
        try c.encode(templateId, forKey: .templateId); try c.encode(templateVersion, forKey: .templateVersion)
        try c.encode(status, forKey: .status)
        try c.encode(createdAt, forKey: .createdAt); try c.encode(updatedAt, forKey: .updatedAt)
        try c.encodeIfPresent(completedAt, forKey: .completedAt)
        try c.encode(values, forKey: .values); try c.encode(tables, forKey: .tables); try c.encode(signatures, forKey: .signatures)
        if returned { try c.encode(true, forKey: .returned) }
        try c.encodeIfPresent(lastPosition, forKey: .lastPosition)
        try c.encodeIfPresent(backupAt, forKey: .backupAt)
        try c.encodeIfPresent(erpUploadedAt, forKey: .erpUploadedAt)
        try c.encodeIfPresent(projectId, forKey: .projectId)
        try c.encodeIfPresent(seq, forKey: .seq)
        try c.encodeIfPresent(createdBy, forKey: .createdBy); try c.encodeIfPresent(createdByName, forKey: .createdByName)
        try c.encodeIfPresent(serverRev, forKey: .serverRev)
        if syncConflict { try c.encode(true, forKey: .syncConflict) }
    }

    /// Edited since the last automatic backup (or never backed up).
    public var needsBackup: Bool { backupAt.map { updatedAt > $0 } ?? true }

    /// Sent to the ERP before and edited since ("Có thay đổi chưa gửi"); never sent is false.
    public var hasUnsentErpChanges: Bool { erpUploadedAt.map { updatedAt > $0 } ?? false }

    /// Has changes the server does not know yet (schema §10.1): never uploaded, or edited after the last upload / pull.
    public var isSyncDirty: Bool { erpUploadedAt.map { updatedAt > $0 } ?? true }

    public func value(_ id: String) -> FieldValue { values[id] ?? FieldValue() }
    public func rows(_ tableId: String) -> [Row] { tables[tableId] ?? [] }

    /// Rows as shown/computed/exported. Fixed tables always follow `template.rows` (in order), merged with the stored
    /// row of the same id; stored rows with unknown ids are ignored. Free tables return the stored rows.
    public func rows(of table: TableDef) -> [Row] {
        guard let fixed = table.rows else { return rows(table.id) }
        let stored = Dictionary(rows(table.id).map { ($0.id, $0) }, uniquingKeysWith: { a, _ in a })
        return fixed.map { stored[$0.id] ?? Row(id: $0.id) }
    }
}

/// Typed helpers for sessionTime / gps values stored as JSON objects.
public struct SessionTime: Equatable, Sendable {
    public var start: Date
    public var end: Date?
    public init(start: Date, end: Date? = nil) { self.start = start; self.end = end }

    public init?(_ json: JSONValue?) {
        guard let o = json?.object, let s = o["start"]?.string, let start = ISODate.parse(s) else { return nil }
        self.start = start
        self.end = o["end"]?.string.flatMap(ISODate.parse)
    }

    public var json: JSONValue {
        .object(["start": .string(ISODate.string(start)), "end": end.map { .string(ISODate.string($0)) } ?? .null])
    }
}

public struct GPSValue: Equatable, Sendable {
    public var lat: Double
    public var lng: Double
    public var accuracy: Double?
    public var capturedAt: Date?
    /// Original `capturedAt` text when it could not be parsed; written back unchanged.
    public var capturedAtRaw: String?

    public init(lat: Double, lng: Double, accuracy: Double? = nil, capturedAt: Date? = Date()) {
        self.lat = lat; self.lng = lng; self.accuracy = accuracy; self.capturedAt = capturedAt
    }

    public init?(_ json: JSONValue?) {
        guard let o = json?.object, let lat = o["lat"]?.double, let lng = o["lng"]?.double else { return nil }
        self.lat = lat; self.lng = lng
        self.accuracy = o["accuracy"]?.double
        let raw = o["capturedAt"]?.string
        self.capturedAt = raw.flatMap(ISODate.parse)
        if capturedAt == nil { capturedAtRaw = raw }
    }

    public var json: JSONValue {
        .object(["lat": .number(lat), "lng": .number(lng), "accuracy": accuracy.map { .number($0) } ?? .null,
                 "capturedAt": capturedAt.map { .string(ISODate.string($0)) } ?? capturedAtRaw.map { .string($0) } ?? .null])
    }
}
