import Foundation

public enum ExportFormat: String, CaseIterable, Identifiable, Sendable, Codable {
    case zip, xlsx, csv, pdf, docx
    public var id: String { rawValue }
    public var title: String {
        switch self {
        case .zip: return "JSON + media (.zip)"
        case .xlsx: return "Excel (.xlsx)"
        case .csv: return "CSV"
        case .pdf: return "PDF"
        case .docx: return "Word (.docx)"
        }
    }
}

/// Builds export files for a record into a temporary folder.
public struct RecordExporter: Sendable {
    public let store: RecordStore
    public init(store: RecordStore) { self.store = store }

    public func export(_ t: Template, _ r: Record, formats: [ExportFormat], into dir: URL, now: Date = Date()) throws -> [URL] {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let model = ExportModelBuilder.build(t, r, now: now)
        let folder = store.folder(r.id)
        var out: [URL] = []
        for format in ExportFormat.allCases where formats.contains(format) {
            let url = dir.appendingPathComponent("\(model.baseName).\(format == .zip ? "zip" : format.rawValue)")
            switch format {
            case .zip: try writeZip(t, r, to: url)
            case .xlsx: try XLSXExport.write(model, to: url)
            case .csv: try CSVExport.info(model).write(to: url, options: .atomic)
            case .pdf: try PDFExport.write(model, recordFolder: folder, to: url)
            case .docx: try DOCXExport.write(model, recordFolder: folder, to: url)
            }
            out.append(url)
        }
        return out
    }

    func writeZip(_ t: Template, _ r: Record, to url: URL) throws {
        var zip = ZipBuilder()
        zip.add("record.json", data: try JSONCoding.makeEncoder().encode(r))
        zip.add("export.json", data: try ExportJSON.build(t, r))
        for rel in Set(RecordStore.mediaFiles(of: r)).sorted() {
            let file = store.fileURL(r.id, rel)
            if FileManager.default.fileExists(atPath: file.path) { zip.add(rel, file: file) }
        }
        try zip.write(to: url)
    }
}
