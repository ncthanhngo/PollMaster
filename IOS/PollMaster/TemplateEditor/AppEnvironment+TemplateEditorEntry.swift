import Foundation
import PollMasterCore

/// Buttons that open the template editor: from a template, from a draft, or a new template (blank / copy).
extension AppEnvironment {
    /// "Sửa nội dung" / "Tiếp tục nháp": the template's draft (made now when none) in the full-screen editor.
    func editContent(of t: Template) {
        do { editingDraftId = try startDraft(from: t).draftId } catch { errorMessage = error.localizedDescription }
    }

    func resume(_ d: TemplateDraft) { editingDraftId = d.draftId }

    /// Where a new template goes unless the user picks another project in the release sheet: `preferred`, the open project, else the first.
    func defaultTarget(preferred: Project? = nil) -> Project? {
        let pool = publishTargets
        return pool.first { $0.id == preferred?.id } ?? pool.first { $0.id == currentProjectId } ?? pool.first
    }

    /// "Tạo mẫu mới": blank, or a copy of `source`.
    func createTemplate(cloning source: Template? = nil, project: Project? = nil) {
        guard let target = defaultTarget(preferred: project) else { infoMessage = "Cần quản lý ít nhất một dự án để tạo mẫu mới."; return }
        do { editingDraftId = try newDraft(cloning: source, project: target).draftId } catch { errorMessage = error.localizedDescription }
    }
}
