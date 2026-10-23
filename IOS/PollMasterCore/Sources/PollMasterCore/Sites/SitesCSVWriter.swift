import Foundation

extension SitesCSV {
    /// `code,address,lat,lng` with a UTF-8 BOM, the format `parse` reads back.
    public static func encode(_ sites: [Site]) -> Data {
        func num(_ d: Double?) -> String { d.map { String($0) } ?? "" }
        let rows = [["code", "address", "lat", "lng"]] + sites.map { [$0.code, $0.address, num($0.lat), num($0.lng)] }
        let text = rows.map { $0.map(CSVExport.escape).joined(separator: ",") }.joined(separator: "\r\n") + "\r\n"
        return Data([0xEF, 0xBB, 0xBF]) + Data(text.utf8)
    }
}
