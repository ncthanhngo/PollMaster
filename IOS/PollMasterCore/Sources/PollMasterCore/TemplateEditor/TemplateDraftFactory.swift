import Foundation

/// The three ways a draft begins: from an installed version, from nothing, or as a copy of another template.
extension TemplateDraft {
    /// Editing `content` of `id@version` for release into `projectId`.
    public static func editing(id: String, version: String, content: JSONValue, projectId: String, now: String) -> TemplateDraft {
        TemplateDraft(baseId: id, baseVersion: version, projectId: projectId, createdAt: now, updatedAt: now, content: content)
    }

    /// An empty template: one empty section, a fresh code (`taken` = ids in the library on this device).
    public static func blank(projectId: String, taken: Set<String>, now: String, random: () -> String = TemplateIds.randomSuffix) -> TemplateDraft {
        let content: JSONValue = .object(["schemaVersion": 1, "id": .string(TemplateIds.templateId(shortName: nil, title: "Mẫu mới", taken: taken, random: random)), "version": "1.0",
                                          "title": "Mẫu mới", "header": .array([]), "sections": .array([.object(["id": "A", "title": "Mục mới", "items": .array([])])]),
                                          "signatures": .array([])])
        return TemplateDraft(baseId: nil, baseVersion: nil, projectId: projectId, createdAt: now, updatedAt: now, content: content)
    }

    /// A new template made from a copy of `source`: new code, version 1.0, "(bản sao)" after the title.
    public static func clone(of source: JSONValue, projectId: String, taken: Set<String>, now: String, random: () -> String = TemplateIds.randomSuffix) -> TemplateDraft {
        var o = source.object ?? [:]
        let title = (o["title"]?.string ?? "Mẫu") + " (bản sao)"
        o["id"] = .string(TemplateIds.templateId(shortName: o["shortName"]?.string, title: title, taken: taken, random: random))
        o["version"] = "1.0"
        o["title"] = .string(title)
        return TemplateDraft(baseId: nil, baseVersion: nil, projectId: projectId, createdAt: now, updatedAt: now, content: .object(o))
    }
}
