import Foundation

/// One missing thing to fill in "Điền lần lượt" (ux-spec L8): a record/header field, or a table to open.
public struct FillStep: Equatable, Sendable, Identifiable {
    public var id: String { fieldId }
    public var sectionId: String
    public var sectionTitle: String
    public var fieldId: String
    public var isTable: Bool
}

public enum FillQueue {
    /// Missing items in template order (header first), one per field/table; table row/column issues collapse into the table.
    public static func steps(_ t: Template, _ r: Record) -> [FillStep] {
        let tableIds = Set(t.allTables.map(\.id))
        var seen = Set<String>()
        return Validator.validate(t, r).compactMap { issue in
            guard seen.insert(issue.fieldId).inserted else { return nil }
            return FillStep(sectionId: issue.sectionId, sectionTitle: issue.sectionTitle, fieldId: issue.fieldId, isTable: tableIds.contains(issue.fieldId))
        }
    }

    /// Ids of header fields and section items in template order.
    static func templateOrder(_ t: Template) -> [String] { t.header.map(\.id) + t.sections.flatMap { $0.items.map(\.id) } }

    /// First step positioned after `current` in template order that was not skipped. `current` may already be filled (no longer a step).
    /// nil `current` -> the first step. nil result -> nothing left after it.
    public static func next(_ t: Template, _ r: Record, after current: String?, skipped: Set<String> = []) -> FillStep? {
        let candidates = steps(t, r).filter { !skipped.contains($0.fieldId) }
        guard let current else { return candidates.first }
        let order = templateOrder(t)
        guard let pos = order.firstIndex(of: current) else { return candidates.first }
        return candidates.first { (order.firstIndex(of: $0.fieldId) ?? -1) > pos }
    }
}
