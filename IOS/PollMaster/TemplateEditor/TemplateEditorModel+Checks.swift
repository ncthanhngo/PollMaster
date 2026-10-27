import Foundation
import PollMasterCore

extension TemplateEditorModel {
    /// Worst finding at or below the node at `p` (nil = none): the red / amber mark on a list row.
    func severity(under p: DocPath) -> TemplateIssue.Severity? {
        let prefix = p.text
        var worst: TemplateIssue.Severity?
        for i in issues where i.path == prefix || i.path.hasPrefix(prefix + ".") || i.path.hasPrefix(prefix + "[") {
            if i.severity == .error { return .error }
            worst = .warning
        }
        return worst
    }

    /// Places that would point at nothing once `list[index]` is gone (formulas, conditions, photo references…).
    func references(afterDeleting list: DocPath, at index: Int) -> [TemplateIssue] {
        guard let trial = try? TemplateEdit.apply(doc, .delete(path: list, index: index)) else { return [] }
        func dangling(_ d: TemplateDoc) -> [TemplateIssue] { d.lint(base: base, isNewTemplate: isNew).filter { $0.code == "DANGLING_REF" } }
        let before = Set(dangling(doc).map { $0.path + $0.message })
        return dangling(trial).filter { !before.contains($0.path + $0.message) }
    }
}
