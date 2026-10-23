import Foundation

/// Suggested value for the `recordCode` field of a new record (schema §8).
public enum RecordCodeProposal {
    /// Last run of digits in `code` ("BB-007/A" -> "007"); nil when it holds no digit.
    static func trailingNumber(_ code: String) -> (value: Int, width: Int)? {
        var digits = ""
        var found = false
        for ch in code.reversed() {
            if ch.isASCII, ch.isNumber { digits.insert(ch, at: digits.startIndex); found = true }
            else if found { break }
        }
        guard found, let n = Int(digits) else { return nil }
        return (n, digits.count)
    }

    /// `max(number) + 1` over records of the same template id, zero padding of the highest code kept; "001" when none.
    public static func propose(for t: Template, among records: [Record]) -> String? {
        guard let field = t.recordCodeField else { return nil }
        let numbers = records.filter { $0.templateId == t.id }
            .compactMap { r in ValueFormatter.display(field, r.values[field.id]) }
            .compactMap(trailingNumber)
        guard let top = numbers.max(by: { $0.value < $1.value }) else { return "001" }
        let next = String(top.value + 1)
        return String(repeating: "0", count: Swift.max(0, top.width - next.count)) + next
    }
}
