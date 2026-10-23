import Foundation

public struct SignatureDef: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var label: String
}

/// `roundEntry` of a heading: its `samples` fields are entered round by round (`Lần 1..n`).
public struct RoundEntry: Codable, Equatable, Sendable {
    public var samples: Int
    public var timeField: String?
}

public struct Heading: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var note: String?
    public var roundEntry: RoundEntry?

    enum Keys: String, CodingKey { case kind, id, title, note, roundEntry }
}

public struct FixedRow: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var fixed: [String: JSONValue]

    enum CodingKeys: String, CodingKey { case id, fixed }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        fixed = try c.decodeIfPresent([String: JSONValue].self, forKey: .fixed) ?? [:]
    }
}

/// Reminder shown when a stopwatch mark is recorded: `text` plus a button opening `field`.
public struct StopwatchCue: Codable, Equatable, Sendable {
    public var text: String
    public var field: String
}

public struct StopwatchDef: Codable, Equatable, Sendable {
    public var columns: [String]
    public var cues: [String: StopwatchCue]?

    /// Cue attached to the mark stored in `column` (e.g. "T6").
    public func cue(for column: String) -> StopwatchCue? { cues?[column] }
}

public struct TableDef: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var note: String?
    public var rowLabel: String?
    public var minRows: Int = 0
    public var required: Bool = false
    public var rows: [FixedRow]?
    public var stopwatch: StopwatchDef?
    public var columns: [Field]
    public var pairedWith: String?
    public var quickEntry: Bool = false
    public var inline: Bool = false

    enum CodingKeys: String, CodingKey {
        case kind, id, title, note, rowLabel, minRows, required, rows, stopwatch, columns, pairedWith, quickEntry, inline
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        title = try c.decodeIfPresent(String.self, forKey: .title) ?? id
        note = try c.decodeIfPresent(String.self, forKey: .note)
        rowLabel = try c.decodeIfPresent(String.self, forKey: .rowLabel)
        minRows = try c.decodeIfPresent(Int.self, forKey: .minRows) ?? 0
        required = try c.decodeIfPresent(Bool.self, forKey: .required) ?? false
        rows = try c.decodeIfPresent([FixedRow].self, forKey: .rows)
        stopwatch = try c.decodeIfPresent(StopwatchDef.self, forKey: .stopwatch)
        columns = try c.decode([Field].self, forKey: .columns)
        pairedWith = try c.decodeIfPresent(String.self, forKey: .pairedWith)
        quickEntry = try c.decodeIfPresent(Bool.self, forKey: .quickEntry) ?? false
        inline = try c.decodeIfPresent(Bool.self, forKey: .inline) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode("table", forKey: .kind); try c.encode(id, forKey: .id); try c.encode(title, forKey: .title)
        try c.encodeIfPresent(note, forKey: .note); try c.encodeIfPresent(rowLabel, forKey: .rowLabel)
        if minRows != 0 { try c.encode(minRows, forKey: .minRows) }
        if required { try c.encode(true, forKey: .required) }
        try c.encodeIfPresent(rows, forKey: .rows); try c.encodeIfPresent(stopwatch, forKey: .stopwatch)
        try c.encode(columns, forKey: .columns)
        try c.encodeIfPresent(pairedWith, forKey: .pairedWith)
        if quickEntry { try c.encode(true, forKey: .quickEntry) }
        if inline { try c.encode(true, forKey: .inline) }
    }

    public var isFixed: Bool { rows != nil }
    public func column(_ id: String) -> Field? { columns.first { $0.id == id } }
}

public enum Item: Codable, Equatable, Sendable, Identifiable {
    case field(Field)
    case heading(Heading)
    case table(TableDef)

    public var id: String {
        switch self {
        case .field(let f): return f.id
        case .heading(let h): return h.id
        case .table(let t): return t.id
        }
    }

    enum Kind: String, CodingKey { case kind }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: Kind.self)
        let kind = try container.decodeIfPresent(String.self, forKey: .kind) ?? "field"
        switch kind {
        case "field": self = .field(try Field(from: decoder))
        case "heading":
            let c = try decoder.container(keyedBy: Heading.Keys.self)
            self = .heading(Heading(id: try c.decode(String.self, forKey: .id),
                                    title: try c.decode(String.self, forKey: .title),
                                    note: try c.decodeIfPresent(String.self, forKey: .note),
                                    roundEntry: try c.decodeIfPresent(RoundEntry.self, forKey: .roundEntry)))
        case "table": self = .table(try TableDef(from: decoder))
        default:
            throw DecodingError.dataCorruptedError(forKey: Kind.kind, in: container, debugDescription: "kind không hợp lệ: \(kind)")
        }
    }

    public func encode(to encoder: Encoder) throws {
        switch self {
        case .field(let f): try f.encode(to: encoder)
        case .heading(let h):
            var c = encoder.container(keyedBy: Heading.Keys.self)
            try c.encode("heading", forKey: .kind); try c.encode(h.id, forKey: .id)
            try c.encode(h.title, forKey: .title); try c.encodeIfPresent(h.note, forKey: .note)
            try c.encodeIfPresent(h.roundEntry, forKey: .roundEntry)
        case .table(let t): try t.encode(to: encoder)
        }
    }
}

public struct Section: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    public var title: String
    public var note: String?
    public var items: [Item]

    public var fields: [Field] { items.compactMap { if case .field(let f) = $0 { return f } else { return nil } } }
    public var tables: [TableDef] { items.compactMap { if case .table(let t) = $0 { return t } else { return nil } } }
}

public struct Template: Codable, Equatable, Sendable, Identifiable {
    public var schemaVersion: Int
    public var id: String
    public var version: String
    public var title: String
    public var subtitle: String?
    public var instructions: String?
    public var recordTitle: String?
    public var mediaNamePattern: String?
    public var header: [Field]
    public var sections: [Section]
    public var signatures: [SignatureDef]

    enum CodingKeys: String, CodingKey {
        case schemaVersion, id, version, title, subtitle, instructions, recordTitle, mediaNamePattern, header, sections, signatures
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        schemaVersion = try c.decode(Int.self, forKey: .schemaVersion)
        id = try c.decode(String.self, forKey: .id)
        version = try c.decode(String.self, forKey: .version)
        title = try c.decode(String.self, forKey: .title)
        subtitle = try c.decodeIfPresent(String.self, forKey: .subtitle)
        instructions = try c.decodeIfPresent(String.self, forKey: .instructions)
        recordTitle = try c.decodeIfPresent(String.self, forKey: .recordTitle)
        mediaNamePattern = try c.decodeIfPresent(String.self, forKey: .mediaNamePattern)
        header = try c.decodeIfPresent([Field].self, forKey: .header) ?? []
        sections = try c.decode([Section].self, forKey: .sections)
        signatures = try c.decodeIfPresent([SignatureDef].self, forKey: .signatures) ?? []
    }

    public var key: String { "\(id)@\(version)" }

    /// Header + section fields (not table columns).
    public var allFields: [Field] { header + sections.flatMap(\.fields) }
    public var allTables: [TableDef] { sections.flatMap(\.tables) }

    public func field(_ id: String) -> Field? { allFields.first { $0.id == id } }
    public func table(_ id: String) -> TableDef? { allTables.first { $0.id == id } }

    /// Partner of a paired repeat table (declared on either side).
    public func pairedTable(of table: TableDef) -> TableDef? {
        guard !table.isFixed else { return nil }
        let partner = table.pairedWith.flatMap(self.table) ?? allTables.first { $0.pairedWith == table.id }
        return partner.flatMap { $0.id != table.id && !$0.isFixed ? $0 : nil }
    }

    /// Ids of fields (record fields and table columns) whose `photoRef` points at `fieldId`, in template order.
    public func photoRefUsers(of fieldId: String) -> [String] {
        (allFields + allTables.flatMap(\.columns)).filter { $0.photoRef == fieldId }.map(\.id)
    }
    public var recordCodeField: Field? { allFields.first { $0.role == "recordCode" } }
}
