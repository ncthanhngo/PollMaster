import Foundation

/// "Gửi lên ERP" (schema §9.6): the text fields of the multipart request, the multipart body file and the stamp after a send.
public enum ERPUpload {
    /// Form fields in contract order; optional ones are left out when empty. The `pdf` and `zip` file parts are added by the client.
    public static func fields(_ t: Template, _ r: Record) -> [(name: String, value: String)] {
        var out: [(String, String)] = [("record_id", r.id)]
        if let code = RecordNaming.rawRecordCode(t, r) { out.append(("record_code", code)) }
        out += [("title", RecordNaming.title(t, r)), ("template_id", t.id), ("template_version", t.version), ("template_title", t.title),
                ("status", r.status.rawValue), ("recorded_at", ISODate.string(r.createdAt))]
        if let done = r.completedAt { out.append(("completed_at", ISODate.string(done))) }
        return out
    }

    /// The record to save with `erpUploadedAt` stamped, or nil when it must stay untouched: deleted or moved to another project
    /// while the files were sent. An edit made meanwhile stays newer than `at` (the time the upload started) and so shows as unsent.
    public static func stamped(snapshot: Record, current: Record?, at: Date) -> Record? {
        guard var r = current, r.projectId == snapshot.projectId else { return nil }
        r.erpUploadedAt = at
        return r
    }

    /// "Đã gửi ERP dd-MM-yyyy HH:mm" or "Có thay đổi chưa gửi"; nil for a record never sent.
    public static func marker(uploadedAt: Date?, hasUnsentChanges: Bool, timeZone: TimeZone = .current) -> String? {
        guard let uploadedAt else { return nil }
        return hasUnsentChanges ? "Có thay đổi chưa gửi" : "Đã gửi ERP " + ISODate.format(uploadedAt, "dd-MM-yyyy HH:mm", timeZone: timeZone)
    }

    /// Writes a `multipart/form-data` body to `url`, copying the files in chunks (a 90 MB zip never sits in memory).
    public static func writeBody(fields: [(name: String, value: String)], files: [(name: String, file: URL, type: String)],
                                 boundary: String, to url: URL) throws {
        FileManager.default.createFile(atPath: url.path, contents: nil)
        let out = try FileHandle(forWritingTo: url)
        defer { try? out.close() }
        for f in fields {
            try out.write(contentsOf: Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(f.name)\"\r\n\r\n\(f.value)\r\n".utf8))
        }
        for f in files {
            let name = f.file.lastPathComponent.replacingOccurrences(of: "\"", with: "_")
            try out.write(contentsOf: Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(f.name)\"; filename=\"\(name)\"\r\nContent-Type: \(f.type)\r\n\r\n".utf8))
            let input = try FileHandle(forReadingFrom: f.file)
            defer { try? input.close() }
            while let chunk = try input.read(upToCount: 1 << 20), !chunk.isEmpty { try out.write(contentsOf: chunk) }
            try out.write(contentsOf: Data("\r\n".utf8))
        }
        try out.write(contentsOf: Data("--\(boundary)--\r\n".utf8))
    }
}
