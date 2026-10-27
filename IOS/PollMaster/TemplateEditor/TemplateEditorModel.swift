import Foundation
import Observation
import SwiftUI
import PollMasterCore

/// Screens pushed inside the editor; node screens are named by their path in the template tree.
enum EditorRoute: Hashable {
    case info, header, signatures
    case section(Int)
    /// A field, heading, table or column.
    case item(DocPath)
    case json(DocPath)
}

enum EditorSheet: String, Identifiable {
    case issues, publish, preview
    var id: String { rawValue }
}

/// Editing session of one draft: every change is an `EditOp` on the raw tree; lint runs shortly after, the draft is saved shortly after that.
@MainActor @Observable
final class TemplateEditorModel {
    private(set) var draft: TemplateDraft
    private(set) var issues: [TemplateIssue] = []
    var path: [EditorRoute] = []
    var sheet: EditorSheet?
    var error: String?
    /// Template the draft started from (nil for a new template).
    let base: TemplateDoc?
    @ObservationIgnored private let persist: (TemplateDraft) -> Void
    @ObservationIgnored private var saveTask: Task<Void, Never>?
    @ObservationIgnored private var lintTask: Task<Void, Never>?
    /// Draft released or discarded: nothing may be written any more.
    @ObservationIgnored private var finished = false

    init(draft: TemplateDraft, base: TemplateDoc?, persist: @escaping (TemplateDraft) -> Void) {
        self.draft = draft; self.base = base; self.persist = persist
        issues = draft.doc.lint(base: base, isNewTemplate: draft.baseId == nil)
    }

    var doc: TemplateDoc { draft.doc }
    var isNew: Bool { draft.baseId == nil }
    var errors: [TemplateIssue] { issues.filter { $0.severity == .error } }
    var warnings: [TemplateIssue] { issues.filter { $0.severity == .warning } }

    func node(_ p: DocPath) -> JSONValue? { doc.node(at: p) }
    func object(_ p: DocPath) -> [String: JSONValue] { node(p)?.object ?? [:] }
    func list(_ p: DocPath) -> [JSONValue] { node(p)?.array ?? [] }
    func text(_ p: DocPath, _ key: String) -> String { object(p)[key]?.string ?? "" }

    // MARK: edits

    /// Applies ops in order; on failure nothing changes and `error` says why.
    @discardableResult
    func apply(_ ops: EditOp...) -> Bool {
        guard !finished else { return false }
        do { draft.doc = try TemplateEdit.apply(doc, ops) } catch {
            self.error = error.localizedDescription
            return false
        }
        scheduleLint(); scheduleSave()
        return true
    }

    func set(_ p: DocPath, _ key: String, _ v: JSONValue?) { apply(.set(path: p, key: key, value: v)) }

    func setProject(_ id: String) { guard draft.projectId != id else { return }; draft.projectId = id; scheduleSave() }

    func move(_ list: DocPath, _ i: Int, by d: Int) {
        guard (0..<self.list(list).count).contains(i + d) else { return }
        apply(.move(path: list, from: i, to: i + d))
    }

    /// Replaces the object at `p` with the text of the JSON box.
    func applyJSON(_ p: DocPath, _ text: String) -> Bool { apply(.replaceText(path: p, text: text)) }

    // MARK: saving

    private func scheduleSave() {
        saveTask?.cancel()
        saveTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(500))
            guard !Task.isCancelled else { return }
            self?.flush()
        }
    }

    /// Writes the draft now (leaving the editor, going to the background).
    func flush() {
        saveTask?.cancel()
        guard !finished else { return }
        persist(draft)
    }

    /// Released or discarded: later changes are dropped.
    func finish() { finished = true; saveTask?.cancel(); lintTask?.cancel() }

    private func scheduleLint() {
        lintTask?.cancel()
        let snapshot = doc, base = base, isNew = isNew
        lintTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            let found = await Task.detached { snapshot.lint(base: base, isNewTemplate: isNew) }.value
            guard !Task.isCancelled else { return }
            self?.issues = found
        }
    }

    /// Findings right now (the publish sheet and preview must not trust a lint still waiting to run).
    func relint() { lintTask?.cancel(); issues = doc.lint(base: base, isNewTemplate: isNew) }

    // MARK: base ids (locked on the form)

    /// Codes the base version has: fields, headings, tables, sections, and columns as "table/column".
    @ObservationIgnored private(set) lazy var baseIds: Set<String> = {
        guard let b = base?.root.object else { return [] }
        var out = Set<String>()
        func add(_ v: JSONValue) { if let id = v.object?["id"]?.string { out.insert(id) } }
        (b["header"]?.array ?? []).forEach(add)
        for s in b["sections"]?.array ?? [] {
            add(s)
            for i in s.object?["items"]?.array ?? [] {
                add(i)
                if let t = i.object?["id"]?.string { for c in i.object?["columns"]?.array ?? [] { if let cid = c.object?["id"]?.string { out.insert("\(t)/\(cid)") } } }
            }
        }
        return out
    }()

    /// Is the code of the node at `p` one the base has (so the form keeps it locked)?
    func isBaseId(_ p: DocPath) -> Bool {
        guard !isNew, let id = object(p)["id"]?.string else { return false }
        if case .key("columns")? = p.dropLast().last, let table = object(Array(p.dropLast(2)))["id"]?.string { return baseIds.contains("\(table)/\(id)") }
        return baseIds.contains(id)
    }
}
