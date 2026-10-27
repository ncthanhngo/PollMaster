import Foundation

/// What publishing a draft does on the server (`template-schema.md` 11.2).
public enum TemplatePublishRules {
    public enum Kind: String, Equatable, Sendable {
        /// Only title, short name or colour differ from the base: PATCH, no new version.
        case meta
        case newVersion
        case newTemplate
    }

    private static let metaKeys = ["title", "shortName", "color"]

    public static func publishKind(base: TemplateDoc?, draft: TemplateDoc) -> Kind {
        guard let base else { return .newTemplate }
        func stripped(_ d: TemplateDoc) -> JSONValue? {
            guard var o = d.root.object else { return nil }
            for k in metaKeys { o[k] = nil }
            return .object(o)
        }
        return stripped(base) == stripped(draft) ? .meta : .newVersion
    }

    /// "1.9" -> "1.10", "2" -> "2.1", "1.0-beta" -> "1.0-beta.1".
    public static func bumpMinor(_ v: String) -> String {
        var parts = v.split(separator: ".", omittingEmptySubsequences: false).map(String.init)
        guard parts.count > 1, let last = parts.last, let n = Int(last) else { return v + ".1" }
        parts[parts.count - 1] = String(n + 1)
        return parts.joined(separator: ".")
    }

    /// The version a new release gets: the newest known one bumped; "1.0" when none is known.
    public static func nextVersion(known: [String]) -> String {
        guard var best = known.first else { return "1.0" }
        for v in known.dropFirst() where TemplateVersion.isNewer(v, than: best) { best = v }
        return bumpMinor(best)
    }
}

extension TemplatePublishRules {
    /// Newest of `known` that is older than `current` ("Quay lại bản trước"); nil when there is none.
    public static func previousVersion(of current: String, among known: [String]) -> String? {
        var best: String?
        for v in known where TemplateVersion.isNewer(current, than: v) && (best.map { TemplateVersion.isNewer(v, than: $0) } ?? true) { best = v }
        return best
    }

    /// Projects the user manages (not archived) whose list holds template `id`, except `target`: the ones a release can move along (ticked by default).
    public static func otherProjects(using id: String, excluding target: String?, in all: [Project]) -> [Project] {
        manageable(using: id, in: all).filter { $0.id != target }
    }

    /// Projects the user manages (not archived) that use template `id` (any version); nil `id` = every managed project.
    public static func manageable(using id: String?, in all: [Project]) -> [Project] {
        all.filter { !$0.archived && $0.canManage && (id == nil || $0.templates.contains { $0.id == id }) }
    }
}
