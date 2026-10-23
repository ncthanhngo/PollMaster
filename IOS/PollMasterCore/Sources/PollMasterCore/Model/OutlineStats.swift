import Foundation

/// Sub-line facts of a section in the outline (ux-spec L14).
public struct SectionStats: Equatable, Sendable {
    public var required: RequiredProgress
    /// Repeat tables with at least one non-empty row: (row label, count).
    public var rowCounts: [(label: String, count: Int)]
    public var mediaFilled: Int
    public var mediaTotal: Int
    /// A required / minCount photo field is not satisfied yet.
    public var missingPhoto: Bool

    public static func == (a: SectionStats, b: SectionStats) -> Bool {
        a.required == b.required && a.mediaFilled == b.mediaFilled && a.mediaTotal == b.mediaTotal && a.missingPhoto == b.missingPhoto
            && a.rowCounts.map(\.label) == b.rowCounts.map(\.label) && a.rowCounts.map(\.count) == b.rowCounts.map(\.count)
    }

    /// "2/5 · 3 lượt · 12/15"; parts without content are left out. Empty when there is nothing to say.
    public var line: String {
        var parts: [String] = []
        if required.total > 0 { parts.append("\(required.done)/\(required.total)") }
        parts += rowCounts.map { "\($0.count) \($0.label)" }
        if mediaTotal > 0 { parts.append("\(mediaFilled)/\(mediaTotal)") }
        return parts.joined(separator: " · ")
    }
}

public enum OutlineStats {
    /// `progress` may be passed when the caller already computed `Validator.sectionProgress` (it validates the whole record).
    public static func compute(_ t: Template, _ r: Record, sectionId: String, progress: RequiredProgress? = nil) -> SectionStats {
        let items: [Item] = sectionId == Validator.headerSectionId ? t.header.map { .field($0) } : (t.sections.first { $0.id == sectionId }?.items ?? [])
        let lookup: (String) -> FieldValue? = { r.values[$0] }
        var stats = SectionStats(required: progress ?? Validator.sectionProgress(t, r)[sectionId] ?? RequiredProgress(done: 0, total: 0),
                                 rowCounts: [], mediaFilled: 0, mediaTotal: 0, missingPhoto: false)
        for item in items {
            switch item {
            case .field(let f) where f.type == .photo || f.type == .video:
                stats.mediaTotal += 1
                if r.values[f.id]?.mediaInV.isEmpty == false { stats.mediaFilled += 1 }
                if f.type == .photo, f.required || f.minCount != nil,
                   Validator.issues(for: f, r.values[f.id], lookup: lookup).contains(where: { $0.0 == .required || $0.0 == .minCount }) {
                    stats.missingPhoto = true
                }
            case .table(let tb) where !tb.isFixed:
                let n = r.rows(of: tb).filter { !$0.isEmpty }.count
                if n > 0 { stats.rowCounts.append((tb.rowLabel?.lowercased() ?? "dòng", n)) }
            default: break
            }
        }
        return stats
    }
}
