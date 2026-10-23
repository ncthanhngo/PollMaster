import Foundation

public enum CSVExport {
    public static let infoHeaders = ["Mục", "Mã", "Thông số", "Giá trị", "Đơn vị", "Cờ", "Lý do", "Ghi chú", "Kiểm tra"]

    public static func infoRow(_ e: ExportEntry) -> [String] {
        [e.sectionTitle, e.id, e.label, e.display, e.unit, e.flag, e.reason, e.note, e.review]
    }

    static func escape(_ s: String) -> String {
        guard s.contains(where: { $0 == "," || $0 == "\"" || $0 == "\n" || $0 == "\r" }) else { return s }
        return "\"" + s.replacingOccurrences(of: "\"", with: "\"\"") + "\""
    }

    /// CSV of the `Thông tin` sheet, UTF-8 with BOM.
    public static func info(_ model: ExportModel) -> Data {
        let rows = [infoHeaders] + model.entries.map(infoRow)
        let text = rows.map { $0.map(escape).joined(separator: ",") }.joined(separator: "\r\n") + "\r\n"
        return Data([0xEF, 0xBB, 0xBF]) + Data(text.utf8)
    }
}
