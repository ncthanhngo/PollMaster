import Foundation

/// A template a project uses: id + version (schema §9.1).
public struct TemplateRef: Codable, Equatable, Hashable, Sendable {
    public var id: String
    public var version: String
    public init(id: String, version: String) { self.id = id; self.version = version }
    public init(_ t: Template) { id = t.id; version = t.version }
    public var key: String { "\(id)@\(version)" }
}

/// `<AppData>/projects/<projectId>/project.json`.
public struct Project: Codable, Equatable, Hashable, Sendable, Identifiable {
    /// Fixed on every device so a shared "Chung" bundle updates the same project everywhere.
    public static let generalId = "chung"
    public static let generalCode = "CHUNG"
    public static let generalName = "Chung"
    /// Identity colours, all dark enough for white initials (>= 4.5:1). The first six are the original set.
    public static let palette = ["#1B6B5A", "#1F5FA8", "#7B4FA3", "#C2571A", "#B8312F", "#5F6B73",
                                 "#0E7490", "#A3367A", "#8A6D0B", "#3F4BA8"]

    /// Colour for a new project: the first palette colour no project uses yet, else the least used one.
    public static func nextColor(used: [String]) -> String {
        let counts = used.reduce(into: [String: Int]()) { $0[$1.uppercased(), default: 0] += 1 }
        return palette.min { counts[$0.uppercased(), default: 0] < counts[$1.uppercased(), default: 0] } ?? palette[0]
    }

    /// Up to two initials for the avatar: first letters of the first two words ("Khảo sát…" → "KS").
    public static func initials(of name: String) -> String {
        let words = name.split(whereSeparator: { $0.isWhitespace })
        let letters = words.prefix(2).compactMap(\.first).map { String($0).uppercased() }
        return letters.isEmpty ? "?" : letters.joined()
    }

    public var schemaVersion = 1
    public var id: String
    public var code: String
    public var name: String
    public var client: String?
    public var description: String?
    public var color: String?
    public var templates: [TemplateRef]
    public var archived: Bool
    public var createdAt: Date
    public var updatedAt: Date

    public init(id: String = UUID().uuidString, code: String, name: String, client: String? = nil, description: String? = nil,
                color: String? = nil, templates: [TemplateRef] = [], archived: Bool = false, now: Date = Date()) {
        self.id = id; self.code = code; self.name = name; self.client = client; self.description = description
        self.color = color; self.templates = templates; self.archived = archived; createdAt = now; updatedAt = now
    }

    enum CodingKeys: String, CodingKey { case schemaVersion, id, code, name, client, description, color, templates, archived, createdAt, updatedAt }

    /// Only `id` and `code` are required on read; the rest falls back to defaults.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        code = try c.decode(String.self, forKey: .code)
        schemaVersion = c.lenient(Int.self, .schemaVersion) ?? 1
        name = c.lenient(String.self, .name) ?? code
        client = c.lenient(String.self, .client)
        description = c.lenient(String.self, .description)
        color = c.lenient(String.self, .color)
        templates = c.lenient([TemplateRef].self, .templates) ?? []
        archived = c.lenient(Bool.self, .archived) ?? false
        createdAt = c.lenient(Date.self, .createdAt) ?? Date()
        updatedAt = c.lenient(Date.self, .updatedAt) ?? createdAt
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(schemaVersion, forKey: .schemaVersion); try c.encode(id, forKey: .id)
        try c.encode(code, forKey: .code); try c.encode(name, forKey: .name)
        try c.encodeIfPresent(client, forKey: .client); try c.encodeIfPresent(description, forKey: .description)
        try c.encodeIfPresent(color, forKey: .color); try c.encode(templates, forKey: .templates)
        try c.encode(archived, forKey: .archived)
        try c.encode(createdAt, forKey: .createdAt); try c.encode(updatedAt, forKey: .updatedAt)
    }

    public var isGeneral: Bool { code == Self.generalCode }
    /// "Tên (MÃ)" as written under export titles.
    public var label: String { "\(name) (\(code))" }
}

public enum ProjectCode {
    public static let maxLength = 16

    /// Upper-case A-Z 0-9 and "-", no accents; any other run of characters becomes one "-"; no leading/trailing "-".
    public static func normalize(_ s: String) -> String {
        let folded = s.replacingOccurrences(of: "đ", with: "d").replacingOccurrences(of: "Đ", with: "D")
            .folding(options: .diacriticInsensitive, locale: Locale(identifier: "en_US_POSIX")).uppercased()
        var out = "", lastDash = true
        for u in folded.unicodeScalars {
            if u.isASCII, (u.properties.isAlphabetic || ("0"..."9").contains(Character(u))) {
                out.unicodeScalars.append(u); lastDash = false
            } else if !lastDash { out.append("-"); lastDash = true }
        }
        return out.hasSuffix("-") ? String(out.dropLast()) : out
    }

    /// Cuts to `limit`, at a "-" boundary when the cut would split a word that has an earlier boundary.
    static func trimmed(_ s: String, to limit: Int) -> String {
        guard s.count > limit else { return s }
        let head = String(s.prefix(limit))
        if s[s.index(s.startIndex, offsetBy: limit)] == "-" { return head }
        guard let dash = head.lastIndex(of: "-") else { return head }
        return String(head[..<dash])
    }

    /// Code proposed from a project name; "DU-AN" when the name has no usable character.
    public static func base(from name: String) -> String {
        let n = trimmed(normalize(name), to: maxLength)
        return n.isEmpty ? "DU-AN" : n
    }

    /// `code`, or `code-2`, `code-3`… (kept within the length limit) until it is not in `taken` (case-insensitive).
    public static func unique(_ code: String, taken: Set<String>) -> String {
        let used = Set(taken.map { $0.uppercased() })
        if !used.contains(code.uppercased()) { return code }
        var n = 2
        while true {
            let suffix = "-\(n)"
            let candidate = trimmed(code, to: maxLength - suffix.count) + suffix
            if !used.contains(candidate.uppercased()) { return candidate }
            n += 1
        }
    }

    public static func generate(from name: String, taken: Set<String>) -> String { unique(base(from: name), taken: taken) }

    /// A-Z 0-9 "-" only, 1...16 characters, not starting/ending with "-".
    public static func isValid(_ code: String) -> Bool {
        !code.isEmpty && code.count <= maxLength && normalize(code) == code
    }
}
