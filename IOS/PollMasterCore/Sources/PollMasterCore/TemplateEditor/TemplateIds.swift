import Foundation

/// Codes for new template parts (`template-schema.md` 11.3). Pure: the random part of a template id is passed in.
public enum TemplateIds {
    /// Lower-case ASCII words joined by `_` ("Giếng khoan" -> "gieng_khoan"); "" when nothing is left.
    public static func slug(_ s: String) -> String {
        var out = "", pendingSep = false
        for u in stripDiacritics(s).unicodeScalars {
            if (48...57).contains(u.value) || (65...90).contains(u.value) || (97...122).contains(u.value) {
                if pendingSep, !out.isEmpty { out += "_" }
                pendingSep = false
                out.unicodeScalars.append(u)
            } else { pendingSep = true }
        }
        return out.lowercased()
    }

    static func stripDiacritics(_ s: String) -> String {
        var out = String.UnicodeScalarView()
        for u in s.replacingOccurrences(of: "đ", with: "d").replacingOccurrences(of: "Đ", with: "D").decomposedStringWithCanonicalMapping.unicodeScalars
        where u.properties.generalCategory != .nonspacingMark { out.append(u) }
        return String(out)
    }

    /// Slug of `label` not in `taken`: "gieng_khoan", then "gieng_khoan_2", "_3"… ("opt" when the label has no letters).
    public static func optionValue(label: String, taken: Set<String>) -> String {
        let base = slug(label).isEmpty ? "opt" : slug(label)
        if !taken.contains(base) { return base }
        var n = 2
        while taken.contains("\(base)_\(n)") { n += 1 }
        return "\(base)_\(n)"
    }

    /// `prefix` + the smallest number >= 1 not in `taken` ("sig1", "c2", "HD-01" with `width` 2 and a "HD-" prefix).
    public static func firstUnused(prefix: String, taken: Set<String>, width: Int = 1) -> String {
        var n = 1
        while taken.contains(prefix + pad(n, width)) { n += 1 }
        return prefix + pad(n, width)
    }

    static func pad(_ n: Int, _ width: Int) -> String {
        let s = String(n)
        return s.count >= width ? s : String(repeating: "0", count: width - s.count) + s
    }

    /// Every item-level code: header fields, sections, section items (not columns).
    static func usedIds(_ root: JSONValue) -> Set<String> {
        var out = Set<String>()
        for f in root.object?["header"]?.array ?? [] { if let id = f.object?["id"]?.string { out.insert(id) } }
        for s in root.object?["sections"]?.array ?? [] {
            if let id = s.object?["id"]?.string { out.insert(id) }
            for i in s.object?["items"]?.array ?? [] { if let id = i.object?["id"]?.string { out.insert(id) } }
        }
        return out
    }

    /// Code of a new item. `section` is the index of the section it goes in (nil = header); `kind` is "field", "heading" or "table".
    public static func nextItemId(_ root: JSONValue, section: Int?, kind: String) -> String {
        let taken = usedIds(root)
        guard let section else { return firstUnused(prefix: "HD-", taken: taken, width: 2) }
        let sec = root.object?["sections"]?.array?[safe: section]?.object
        let sid = sec?["id"]?.string ?? "S"
        switch kind {
        case "heading": return firstUnused(prefix: sid, taken: taken)
        case "table": return firstUnused(prefix: sid + "T", taken: taken)
        default:
            let fields = (sec?["items"]?.array ?? []).compactMap(\.object).filter { ($0["kind"]?.string ?? "field") == "field" }
            if let last = fields.last?["id"]?.string, let next = successor(of: last, taken: taken) { return next }
            return firstUnused(prefix: sid + "-", taken: taken, width: 2)
        }
    }

    /// "A-07" -> "A-08", "A-9" -> "A-10", skipping codes in `taken`; nil when `id` does not end in a number.
    static func successor(of id: String, taken: Set<String>) -> String? {
        let digits = String(id.reversed().prefix { $0.isASCII && $0.isNumber }.reversed())
        guard !digits.isEmpty, var n = Int(digits) else { return nil }
        let prefix = String(id.dropLast(digits.count))
        n += 1
        while taken.contains(prefix + pad(n, digits.count)) { n += 1 }
        return prefix + pad(n, digits.count)
    }

    /// First free letter A-Z for a new section (taken by a section or item), else "S1", "S2"…
    public static func nextSectionId(_ root: JSONValue) -> String {
        let taken = usedIds(root)
        for u in UnicodeScalar("A").value...UnicodeScalar("Z").value {
            let c = String(UnicodeScalar(u)!)
            if !taken.contains(c) { return c }
        }
        return firstUnused(prefix: "S", taken: taken)
    }

    /// `{SLUG ≤ 8}-{4 random base36}` in capitals, not in `taken` (the library on this device); `random` returns the 4 characters.
    public static func templateId(shortName: String?, title: String, taken: Set<String>, random: () -> String = randomSuffix) -> String {
        func slugOf(_ s: String) -> String {
            String(stripDiacritics(s).uppercased().unicodeScalars.filter { ($0.value >= 48 && $0.value <= 57) || ($0.value >= 65 && $0.value <= 90) }.prefix(8).map(Character.init))
        }
        let short = shortName.map(slugOf) ?? "", head = short.isEmpty ? slugOf(title) : short
        let base = head.isEmpty ? "MAU" : head
        var id = ""
        for _ in 0..<50 {
            id = base + "-" + random().uppercased()
            if !taken.contains(id) { break }
        }
        return id
    }

    public static func randomSuffix() -> String {
        let chars = Array("abcdefghijklmnopqrstuvwxyz0123456789")
        return String((0..<4).map { _ in chars.randomElement()! })
    }
}

extension Array {
    subscript(safe i: Int) -> Element? { indices.contains(i) ? self[i] : nil }
}
