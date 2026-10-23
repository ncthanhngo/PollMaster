import Foundation
import ZIPFoundation

public enum ProjectBundleError: Error, LocalizedError, Equatable {
    case invalid([String])
    public var errorDescription: String? {
        guard case .invalid(let lines) = self else { return nil }
        return "Gói dự án không hợp lệ:\n" + lines.prefix(8).joined(separator: "\n")
    }
    public var lines: [String] { if case .invalid(let l) = self { return l } else { return [] } }
}

/// `.pmproject`: zip of `project.json`, `templates/<id>@<version>.json` and optional `sites.csv` (schema §9.3).
public struct ProjectBundle: Sendable {
    public struct Entry: Sendable {
        public var template: Template
        public var data: Data
    }

    public static let fileExtension = "pmproject"
    public var project: Project
    public var templates: [Entry]
    /// nil when the bundle has no `sites.csv`.
    public var sites: [Site]?

    public init(project: Project, templates: [Entry], sites: [Site]?) {
        self.project = project; self.templates = templates; self.sites = sites
    }

    public static func fileName(for p: Project) -> String { "\(p.code).\(fileExtension)" }

    /// Gathers the installed files of every template the project references.
    public static func make(_ p: Project, templates: TemplateStore, sites: [Site]) throws -> ProjectBundle {
        var entries: [Entry] = [], problems: [String] = []
        for ref in p.templates {
            if let d = templates.data(id: ref.id, version: ref.version), let t = try? TemplateLoader.decode(d) { entries.append(Entry(template: t, data: d)) }
            else { problems.append("Thiếu mẫu \(ref.key) trong thư viện.") }
        }
        if !problems.isEmpty { throw ProjectBundleError.invalid(problems) }
        return ProjectBundle(project: p, templates: entries, sites: sites.isEmpty ? nil : sites)
    }

    public func write(to url: URL) throws {
        var zip = ZipBuilder()
        zip.add("project.json", data: try JSONCoding.makeEncoder().encode(project))
        for e in templates {
            zip.add("templates/\(RecordNaming.sanitize(e.template.id))@\(RecordNaming.sanitize(e.template.version)).json", data: e.data)
        }
        if let sites, !sites.isEmpty { zip.add("sites.csv", data: SitesCSV.encode(sites)) }
        try zip.write(to: url)
    }

    private static let limits = (project: 1_000_000, template: 5_000_000, sites: 20_000_000)

    private static func read(_ archive: Archive, _ entry: ZIPFoundation.Entry, limit: Int) throws -> Data {
        guard entry.uncompressedSize <= UInt64(limit) else { throw ProjectBundleError.invalid(["\(entry.path): tệp quá lớn."]) }
        var out = Data()
        _ = try archive.extract(entry) { out.append($0) }
        return out
    }

    /// Reads and validates a bundle; every problem found is listed (nothing is installed).
    public static func read(from url: URL) throws -> ProjectBundle {
        let archive: Archive
        do { archive = try Archive(url: url, accessMode: .read) }
        catch { throw ProjectBundleError.invalid(["Không đọc được tệp: không phải gói dự án (zip)."]) }
        var problems: [String] = []
        var project: Project?, entries: [Entry] = [], sites: [Site]?
        for e in archive where e.type == .file {
            let path = e.path
            do {
                switch path {
                case "project.json":
                    do { project = try JSONCoding.makeDecoder().decode(Project.self, from: read(archive, e, limit: limits.project)) }
                    catch let err as DecodingError { problems.append("project.json: " + TemplateLoader.describe(err)) }
                case "sites.csv":
                    sites = try SitesCSV.parse(read(archive, e, limit: limits.sites))
                case _ where path.hasPrefix("templates/") && path.hasSuffix(".json") && !path.dropFirst(10).contains("/"):
                    let d = try read(archive, e, limit: limits.template)
                    do { entries.append(Entry(template: try TemplateLoader.decode(d), data: d)) }
                    catch { problems += TemplateText.errorLines(error).map { "\(path): \($0)" } }
                default: break
                }
            } catch let err as ProjectBundleError { problems += err.lines }
            catch { problems.append("\(path): \(error.localizedDescription)") }
        }
        if project == nil && !problems.contains(where: { $0.hasPrefix("project.json") }) { problems.append("Thiếu project.json.") }
        guard problems.isEmpty, var project else { throw ProjectBundleError.invalid(problems) }
        project.code = ProjectCode.trimmed(ProjectCode.normalize(project.code), to: ProjectCode.maxLength).nilIfEmpty ?? ProjectCode.base(from: project.name)
        return ProjectBundle(project: project, templates: entries, sites: sites)
    }
}
