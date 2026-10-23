import Foundation

public enum PartVisibility {
    /// Whether `part` of `field` is shown. `lookup` resolves other fields (row scope first, then record).
    public static func isVisible(_ part: Part, in field: Field, value: FieldValue, lookup: (String) -> FieldValue?) -> Bool {
        guard let cond = part.showWhen else { return true }
        let target = cond.field == "$self" ? value : (lookup(cond.field) ?? FieldValue())
        if let eq = cond.equals { return target.v == eq }
        if let needle = cond.contains {
            if let arr = target.v?.array { return arr.contains(.string(needle)) }
            return target.v == .string(needle)   // not an array: equality
        }
        return true
    }

    public static func visibleParts(of field: Field, value: FieldValue, lookup: (String) -> FieldValue?) -> [Part] {
        (field.parts ?? []).filter { isVisible($0, in: field, value: value, lookup: lookup) }
    }
}
