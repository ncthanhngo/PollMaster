import Foundation

/// Round-by-round entry of `samples` fields under a heading with `roundEntry` (schema §8).
public enum RoundEntryRules {
    /// Ids of the `samples` fields that belong to heading `headingId`: from it to the next heading (tables are skipped).
    public static func groupFieldIds(in items: [Item], headingId: String) -> [String] {
        guard let start = items.firstIndex(where: { if case .heading(let h) = $0 { return h.id == headingId } else { return false } }) else { return [] }
        var out: [String] = []
        for item in items[(start + 1)...] {
            switch item {
            case .heading: return out
            case .field(let f) where f.samples != nil: out.append(f.id)
            default: break
            }
        }
        return out
    }

    /// Heading whose round group contains `fieldId`.
    public static func heading(owning fieldId: String, in items: [Item]) -> Heading? {
        var current: Heading?
        for item in items {
            switch item {
            case .heading(let h): current = h
            case .field(let f) where f.id == fieldId: return current?.roundEntry != nil ? current : nil
            default: break
            }
        }
        return nil
    }

    /// Time to write into the entry's `timeField` for `round`: set when a round gets its first value (edit of another group
    /// field) and the time of that round is still blank. nil otherwise.
    public static func timeToFill(_ entry: RoundEntry, round: Int, editedFieldId: String, editedHasValue: Bool,
                                  time: FieldValue?, now: Date = Date()) -> String? {
        guard let timeField = entry.timeField, editedFieldId != timeField, editedHasValue, round >= 0, round < entry.samples else { return nil }
        guard time?.sample(round).isBlank ?? true else { return nil }
        return ISODate.format(now, "HH:mm")
    }

    /// First round (0-based) in which `value` has nothing yet; the last round when all are filled.
    public static func firstEmptyRound(samples: Int, value: FieldValue?) -> Int {
        (0..<Swift.max(samples, 1)).first { value?.sample($0).isBlank ?? true } ?? Swift.max(samples, 1) - 1
    }
}
