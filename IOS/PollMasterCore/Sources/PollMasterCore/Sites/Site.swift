import Foundation

/// A planned survey point (schema §7.3).
public struct Site: Codable, Equatable, Sendable, Identifiable, Hashable {
    public var code: String
    public var address: String
    public var lat: Double?
    public var lng: Double?
    public var id: String { code }

    public init(code: String, address: String = "", lat: Double? = nil, lng: Double? = nil) {
        self.code = code; self.address = address; self.lat = lat; self.lng = lng
    }

    enum CodingKeys: String, CodingKey { case code, address, lat, lng }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        code = try c.decode(String.self, forKey: .code)
        address = try c.decodeIfPresent(String.self, forKey: .address) ?? ""
        lat = try? c.decodeIfPresent(Double.self, forKey: .lat)
        lng = try? c.decodeIfPresent(Double.self, forKey: .lng)
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(code, forKey: .code); try c.encode(address, forKey: .address)
        try c.encode(lat, forKey: .lat); try c.encode(lng, forKey: .lng)   // explicit null
    }

    public var hasCoordinate: Bool { lat != nil && lng != nil }

    /// Search by code or address, accent- and case-insensitive.
    public func matches(_ query: String) -> Bool {
        let q = query.trimmingCharacters(in: .whitespaces)
        if q.isEmpty { return true }
        let opts: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        return code.range(of: q, options: opts) != nil || address.range(of: q, options: opts) != nil
    }
}

public enum SitesCSVError: Error, LocalizedError, Equatable {
    case notText, missingCodeColumn, empty
    public var errorDescription: String? {
        switch self {
        case .notText: return "Tệp không phải văn bản UTF-8."
        case .missingCodeColumn: return "Thiếu cột 'code' ở dòng tiêu đề (code,address,lat,lng)."
        case .empty: return "Tệp không có điểm nào."
        }
    }
}

/// CSV `code,address,lat,lng` (UTF-8, BOM optional). Extra columns are ignored; invalid coordinates become nil.
public enum SitesCSV {
    public static func parse(_ data: Data) throws -> [Site] {
        var bytes = data
        if bytes.starts(with: [0xEF, 0xBB, 0xBF]) { bytes = bytes.dropFirst(3) }
        guard let text = String(data: bytes, encoding: .utf8) else { throw SitesCSVError.notText }
        let rows = records(text).filter { !($0.count == 1 && $0[0].trimmingCharacters(in: .whitespaces).isEmpty) }
        guard let head = rows.first else { throw SitesCSVError.empty }
        let names = head.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }
        guard let ci = names.firstIndex(of: "code") else { throw SitesCSVError.missingCodeColumn }
        let ai = names.firstIndex(of: "address"), li = names.firstIndex(of: "lat"), gi = names.firstIndex(of: "lng")
        func cell(_ r: [String], _ i: Int?) -> String { i.flatMap { $0 < r.count ? r[$0] : nil }?.trimmingCharacters(in: .whitespaces) ?? "" }
        func num(_ s: String) -> Double? { Double(s.replacingOccurrences(of: ",", with: ".")) }
        var seen = Set<String>()
        let sites = rows.dropFirst().compactMap { r -> Site? in
            let code = cell(r, ci)
            guard !code.isEmpty, seen.insert(code).inserted else { return nil }
            return Site(code: code, address: cell(r, ai), lat: num(cell(r, li)), lng: num(cell(r, gi)))
        }
        if sites.isEmpty { throw SitesCSVError.empty }
        return sites
    }

    /// RFC-4180 style split: quoted fields may hold commas, quotes ("") and line breaks.
    static func records(_ text: String) -> [[String]] {
        var rows: [[String]] = [], row: [String] = [], field = "", quoted = false
        var chars = text.makeIterator()
        var pending = chars.next()
        while let ch = pending {
            pending = chars.next()
            if quoted {
                if ch == "\"" {
                    if pending == "\"" { field.append("\""); pending = chars.next() } else { quoted = false }
                } else { field.append(ch) }
            } else if ch == "\"" { quoted = true }
            else if ch == "," { row.append(field); field = "" }
            else if ch == "\n" || ch == "\r\n" || ch == "\r" {
                row.append(field); field = ""; rows.append(row); row = []
            } else { field.append(ch) }
        }
        if !field.isEmpty || !row.isEmpty { row.append(field); rows.append(row) }
        return rows
    }
}

/// `<root>/sites.json`.
public struct SiteStore: Sendable {
    public let url: URL
    public init(root: URL) { url = root.appendingPathComponent("sites.json") }

    public func load() -> [Site] {
        guard let d = try? Data(contentsOf: url) else { return [] }
        return (try? JSONDecoder().decode([Site].self, from: d)) ?? []
    }

    public func save(_ sites: [Site]) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes]
        try enc.encode(sites).write(to: url, options: .atomic)
    }

    public func clear() throws { if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) } }
}

public enum Geo {
    /// Great-circle distance in meters (haversine).
    public static func distance(lat1: Double, lng1: Double, lat2: Double, lng2: Double) -> Double {
        let r = 6_371_000.0, rad = Double.pi / 180
        let dLat = (lat2 - lat1) * rad, dLng = (lng2 - lng1) * rad
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * rad) * cos(lat2 * rad) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(min(1, sqrt(a)))
    }
}

public enum SiteLookup {
    /// Values a `lookup.fill` writes for `site`: target field id -> value (`address`, `code`, `gps`, `lat`, `lng`).
    public static func fillValues(_ lookup: Lookup, site: Site) -> [String: FieldValue] {
        var out: [String: FieldValue] = [:]
        for (target, attr) in lookup.fill {
            switch attr {
            case "address": if !site.address.isEmpty { out[target] = FieldValue(v: .string(site.address)) }
            case "code": out[target] = FieldValue(v: .string(site.code))
            case "gps": if let la = site.lat, let ln = site.lng { out[target] = FieldValue(v: GPSValue(lat: la, lng: ln, accuracy: nil).json) }
            case "lat": if let la = site.lat { out[target] = FieldValue(v: .number(la)) }
            case "lng": if let ln = site.lng { out[target] = FieldValue(v: .number(ln)) }
            default: break
            }
        }
        return out
    }

    /// Writes the fill values; with `overwrite == false` only into targets that hold nothing yet.
    /// Returns the target ids that already held data (what a confirmation would overwrite).
    @discardableResult
    public static func apply(_ values: [String: FieldValue], to r: inout Record, overwrite: Bool) -> [String] {
        var occupied: [String] = []
        for (id, fv) in values.sorted(by: { $0.key < $1.key }) {
            let has = r.values[id].map { $0.v?.isBlank == false } ?? false
            if has { occupied.append(id); if !overwrite { continue } }
            var n = r.values[id] ?? FieldValue()
            n.v = fv.v; n.flag = nil; n.reason = nil
            r.values[id] = n
        }
        return occupied
    }

    /// The planned site matching the lookup field's value (by code).
    public static func site(for field: Field, in r: Record, sites: [Site]) -> Site? {
        guard let code = r.values[field.id]?.v?.string?.trimmingCharacters(in: .whitespaces), !code.isEmpty else { return nil }
        return sites.first { $0.code == code }
    }

    /// Distance (m) from each lookup's `gps` field to the planned site coordinate.
    public static func plannedDistances(_ t: Template, _ r: Record, sites: [Site]) -> [(fieldId: String, meters: Double)] {
        var out: [(String, Double)] = []
        for f in t.allFields {
            guard let lk = f.lookup, let target = lk.gps, let site = site(for: f, in: r, sites: sites),
                  let la = site.lat, let ln = site.lng else { continue }
            guard let g = GPSValue(r.values[target]?.v), r.values[target]?.flag != .notMeasurable else { continue }
            out.append((target, Geo.distance(lat1: g.lat, lng1: g.lng, lat2: la, lng2: ln)))
        }
        return out
    }
}
