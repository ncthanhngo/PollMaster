import Foundation

/// ISO-8601 helpers: always written as `yyyy-MM-dd'T'HH:mm:ssXXX`, read with or without seconds / fractional seconds.
public enum ISODate {
    public static func string(_ date: Date, timeZone: TimeZone = .current) -> String {
        format(date, "yyyy-MM-dd'T'HH:mm:ssXXX", timeZone: timeZone)
    }

    // date, HH:mm, optional :ss, optional fraction, optional zone (Z, +07:00, +0700, +07)
    private static let pattern = try! NSRegularExpression(
        pattern: #"^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})(?::(\d{2}))?(?:[.,](\d+))?(Z|[+-]\d{2}(?::?\d{2})?)?$"#)

    public static func parse(_ raw: String) -> Date? {
        let s = raw.trimmingCharacters(in: .whitespaces)
        let ns = s as NSString
        guard let m = pattern.firstMatch(in: s, range: NSRange(location: 0, length: ns.length)) else { return nil }
        func group(_ i: Int) -> String? { m.range(at: i).location == NSNotFound ? nil : ns.substring(with: m.range(at: i)) }
        var zone = "XXX", offset = ""
        if let z = group(5) {
            if z == "Z" { offset = "Z" }
            else {
                let digits = z.dropFirst().replacingOccurrences(of: ":", with: "")
                offset = String(z.first!) + digits.prefix(2) + ":" + (digits.count >= 4 ? String(digits.suffix(2)) : "00")
            }
        } else { zone = "" }
        let text = "\(group(1)!)T\(group(2)!):\(group(3) ?? "00")\(offset)"
        guard var date = parse(text, pattern: "yyyy-MM-dd'T'HH:mm:ss" + zone) else { return nil }
        if let frac = group(4), let f = Double("0." + frac) { date.addTimeInterval(f) }
        return date
    }

    /// `yyyy-MM-dd` in the local calendar.
    public static func dayString(_ date: Date = Date()) -> String { format(date, "yyyy-MM-dd") }

    public static func format(_ date: Date, _ pattern: String, timeZone: TimeZone = .current) -> String {
        formatters.use(pattern) { f in
            f.timeZone = timeZone
            return f.string(from: date)
        }
    }

    public static func parse(_ s: String, pattern: String) -> Date? {
        formatters.use(pattern) { f in
            f.timeZone = .current
            return f.date(from: s)
        }
    }

    /// One `DateFormatter` per format string (creating them is slow); the lock serialises use because the time zone is set per call.
    private static let formatters = FormatterCache()
}

private final class FormatterCache: @unchecked Sendable {
    private let lock = NSLock()
    private var byPattern: [String: DateFormatter] = [:]

    func use<T>(_ pattern: String, _ body: (DateFormatter) -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        let f: DateFormatter
        if let cached = byPattern[pattern] { f = cached } else {
            f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = pattern
            byPattern[pattern] = f
        }
        return body(f)
    }
}

public enum JSONCoding {
    public static func makeEncoder(pretty: Bool = true) -> JSONEncoder {
        let e = JSONEncoder()
        e.outputFormatting = pretty ? [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes] : [.sortedKeys, .withoutEscapingSlashes]
        e.dateEncodingStrategy = .custom { date, enc in
            var c = enc.singleValueContainer()
            try c.encode(ISODate.string(date))
        }
        return e
    }

    public static func makeDecoder() -> JSONDecoder {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .custom { dec in
            let s = try dec.singleValueContainer().decode(String.self)
            guard let date = ISODate.parse(s) else {
                throw DecodingError.dataCorrupted(.init(codingPath: dec.codingPath, debugDescription: "Ngày giờ không hợp lệ: \(s)"))
            }
            return date
        }
        return d
    }
}
