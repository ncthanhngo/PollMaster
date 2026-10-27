import Foundation
import PollMasterCore

/// Read-only views of the template structure and of the record, for the table of contents and the section tools.
extension RecordEditorModel {
    /// Section list for the table of contents: header first, then template sections.
    var sectionTitles: [(id: String, title: String)] {
        [(Validator.headerSectionId, Validator.headerSectionTitle)] + template.sections.map { ($0.id, $0.title) }
    }

    func items(ofSection index: Int) -> [Item] {
        index == 0 ? template.header.map { .field($0) } : template.sections[index - 1].items
    }

    func sectionNote(_ index: Int) -> String? { index == 0 ? nil : template.sections[index - 1].note }

    func issueCount(section id: String) -> Int { issues.filter { $0.sectionId == id }.count }

    func sectionIndex(of id: String) -> Int? { sectionTitles.firstIndex { $0.id == id } }

    /// Index of the section (0 = header) holding the field or table `id`.
    func sectionIndex(containing id: String) -> Int? {
        if template.header.contains(where: { $0.id == id }) { return 0 }
        return template.sections.firstIndex { $0.items.contains { $0.id == id } }.map { $0 + 1 }
    }

    /// "Tiếp tục" target: the last position if it still exists in the template.
    var resumeTarget: (index: Int, fieldId: String, label: String)? {
        guard let p = record.lastPosition, let i = sectionIndex(of: p.section) else { return nil }
        let label = template.field(p.field)?.label ?? template.table(p.field)?.title
        guard let label else { return nil }
        return (i, p.field, label)
    }

    func emptyFlaggableCount(section id: String) -> Int { SectionOps.emptyFlaggableFields(template, record, sectionId: id).count }

    func otherRecords() -> [Record] { SectionOps.candidates(for: record, among: store.list()) }

    func hasData(section id: String) -> Bool { SectionOps.hasData(template, record, sectionId: id) }

    func lookupConflicts(_ lookup: Lookup, site: Site) -> [String] {
        SiteLookup.fillValues(lookup, site: site).keys.filter { record.values[$0]?.v?.isBlank == false }.sorted()
    }
}
