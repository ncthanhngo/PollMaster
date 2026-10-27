import Foundation
import PollMasterCore

extension RecordEditorModel {
    /// Validation, progress, stats and warnings follow a burst of typing ~200 ms later instead of on every keystroke.
    func scheduleRefresh() {
        refreshTask?.cancel()
        refreshTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(200))
            guard !Task.isCancelled else { return }
            self?.refresh()
        }
    }

    /// Recomputes everything derived from the record now (leaving a section, review, save).
    func refresh() {
        refreshTask?.cancel(); refreshTask = nil
        derivedStale = false
        computed = ComputedEngine(template: template, record: record).computeAll()
        issues = Validator.validate(template, record)
        progress = Validator.progress(template, record)
        sectionProgress = Validator.sectionProgress(template, record)
        sectionStats = Dictionary(uniqueKeysWithValues: sectionTitles.map {
            ($0.id, OutlineStats.compute(template, record, sectionId: $0.id, progress: sectionProgress[$0.id]))
        })
        let planned = sites()
        warnings = Warnings.all(template, record, sites: planned)
        reviewNotes = Warnings.reviewNotes(template, record)
        plannedDistances = Dictionary(SiteLookup.plannedDistances(template, record, sites: planned).map { ($0.fieldId, $0.meters) },
                                      uniquingKeysWith: { a, _ in a })
    }
}
