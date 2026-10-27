import Foundation

/// One row of ERP `GET /projects` (only what the app keeps).
public struct ERPProject: Decodable, Equatable, Sendable {
    public var id: String
    public var code: String
    public var name: String
    public var shortName: String?
    /// "company", "internal" or "personal".
    public var type: String?
    public var customerName: String?
    /// Effective avatar: the project's own photo, else the customer logo; may be relative (`/uploads/…`).
    public var logo: String?
    public var createdAt: Date?

    public init(id: String, code: String, name: String, shortName: String? = nil, type: String? = nil, customerName: String? = nil,
                logo: String? = nil, createdAt: Date? = nil) {
        self.id = id; self.code = code; self.name = name; self.shortName = shortName; self.type = type
        self.customerName = customerName; self.logo = logo; self.createdAt = createdAt
    }

    enum CodingKeys: String, CodingKey {
        case id, code, name, shortName = "short_name", type = "project_type", customerName = "customer_name"
        case logo = "customer_logo_url", createdAt = "created_at"
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        code = c.lenient(String.self, .code) ?? ""
        name = c.lenient(String.self, .name) ?? ""
        shortName = c.lenient(String.self, .shortName)
        type = c.lenient(String.self, .type)
        customerName = c.lenient(String.self, .customerName)
        logo = c.lenient(String.self, .logo)
        createdAt = c.lenient(String.self, .createdAt).flatMap(ERPDate.parse)
    }

    /// The Phoenix project gets the templates already on the device when it first arrives (owner's decision).
    public var startsWithLibrary: Bool {
        [code, name, shortName ?? ""].contains { $0.localizedCaseInsensitiveContains("phoenix") }
    }

    /// Name shown in the app: the short name when the ERP has one (same rule as the ERP mobile app).
    public var displayName: String {
        let s = shortName?.trimmingCharacters(in: .whitespaces) ?? ""
        return !s.isEmpty ? s : (!name.isEmpty ? name : code)
    }
}

/// `{data: {items, total}}` of a paged ERP list.
public struct ERPPage<T: Decodable & Sendable>: Decodable, Sendable {
    public var items: [T]
    public var total: Int
}

/// `{data, error}` envelope of every ERP response.
public struct ERPEnvelope<T: Decodable>: Decodable {
    public var data: T?
    public var error: String?
}

enum ERPDate {
    /// RFC 3339 with or without fractional seconds.
    static func parse(_ s: String) -> Date? {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f.date(from: s) { return d }
        f.formatOptions = [.withInternetDateTime]
        return f.date(from: s)
    }
}

/// Projects come from the ERP: the device keeps one folder per ERP project (its templates, sites, colour, records).
extension Project {
    /// Same rule as `ERPProject.startsWithLibrary`, for a project already on the device.
    public var startsWithLibrary: Bool { [code, name].contains { $0.localizedCaseInsensitiveContains("phoenix") } }
}

public enum ERPProjectSync {
    public struct LogoChange: Equatable, Sendable {
        public var projectId: String
        /// nil: the ERP project has no avatar any more.
        public var url: String?
    }

    /// Writes the ERP list into the store: ERP fields refreshed, device fields (templates, colour, sites) kept, new projects
    /// get the next free colour; personal projects and projects no longer listed are archived (hidden, records kept).
    /// `library` (newest version of each template) goes to a new project that `startsWithLibrary`. Returns the projects whose avatar
    /// must be downloaded again (or removed).
    @discardableResult
    public static func apply(_ list: [ERPProject], to store: ProjectStore, library: [TemplateRef] = [], now: Date = Date()) throws -> [LogoChange] {
        let listed = list.filter { Project.kinds.contains($0.type ?? "") && isValidId($0.id) }
        let ids = Set(listed.map(\.id))
        var all = store.list(), changes: [LogoChange] = []
        for var p in all where !ids.contains(p.id) && !p.archived {
            p.archived = true
            try store.save(p)
        }
        for e in listed {
            let old = all.first { $0.id == e.id }
            var p = old ?? Project(id: e.id, code: "", name: "", color: Project.nextColor(used: all.compactMap(\.color)),
                                   templates: e.startsWithLibrary ? library : [], now: now)
            let code = ProjectCode.normalize(e.code)
            p.code = code.isEmpty ? ProjectCode.base(from: e.displayName) : code
            p.name = e.displayName
            p.client = e.customerName?.trimmingCharacters(in: .whitespaces).nilIfEmpty
            p.kind = e.type
            p.archived = false
            p.createdAt = e.createdAt ?? p.createdAt
            let logo = e.logo?.nilIfEmpty
            if logo != p.logo || (logo != nil && store.avatarURL(p.id) == nil) { changes.append(LogoChange(projectId: p.id, url: logo)) }
            p.logo = logo
            if p != old {
                p.updatedAt = now
                try store.save(p)
            }
            if old == nil { all.append(p) }
        }
        return changes
    }

    /// Templates the app brings that were never on this device also go to every Phoenix project already here
    /// (`startsWithLibrary`). A template removed from the project stays removed: only first-time templates are added.
    public static func addNewTemplates(_ refs: [TemplateRef], to store: ProjectStore, now: Date = Date()) throws {
        for p in store.list() where !p.archived && p.startsWithLibrary {
            for ref in refs where !p.templates.contains(where: { $0.id == ref.id }) { try store.addTemplate(ref, to: p.id, now: now) }
        }
    }

    /// The id becomes a folder name: only letters, digits and dashes, at most 64 characters (`^[A-Za-z0-9-]{1,64}$`).
    public static func isValidId(_ id: String) -> Bool {
        (1...64).contains(id.utf8.count) && id.utf8.allSatisfy { ($0 >= 48 && $0 <= 57) || ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122) || $0 == 45 }
    }

    /// Absolute URL of an ERP file path: uploads are served from the API host root, outside `/api/v1`.
    public static func fileURL(_ path: String, host: URL) -> URL? {
        path.hasPrefix("http") ? URL(string: path) : URL(string: path, relativeTo: host)?.absoluteURL
    }

    /// Record and project folders on the device: what the one-time wipe would remove.
    public static func localDataCounts(root: URL) -> (records: Int, projects: Int) {
        func count(_ dir: String) -> Int {
            ((try? FileManager.default.contentsOfDirectory(atPath: root.appendingPathComponent(dir).path)) ?? []).filter { !$0.hasPrefix(".") }.count
        }
        return (count("records"), count("projects"))
    }

    /// One-time wipe when the app switches to ERP projects: projects and records made on the device go, templates stay.
    public static func resetLocalData(root: URL) throws {
        for dir in ["projects", "records"] {
            let url = root.appendingPathComponent(dir, isDirectory: true)
            if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        }
    }
}

/// Error texts from the ERP backend: only its Vietnamese sentences are meant for people (not codes like `INVALID_CREDENTIALS`,
/// not English like "Invalid credentials" or "Email: ...").
public enum ERPMessage {
    private static let vietnamese = Set("àáảãạăằắẳẵặâầấẩẫậèéẻẽẹêềếểễệìíỉĩịòóỏõọôồốổỗộơờớởỡợùúủũụưừứửữựỳýỷỹỵđ")

    public static func readable(_ text: String?) -> String? {
        guard let t = text?.trimmingCharacters(in: .whitespacesAndNewlines), t.lowercased().contains(where: { vietnamese.contains($0) }) else { return nil }
        return t
    }
}
