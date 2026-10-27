import Foundation
import PollMasterCore

/// The template calls of the ERP backend (`project-poll-templates.md`), over `ERPClient`'s token handling.
struct ERPTemplateRemote: TemplateRemote {
    let client: ERPClient

    private struct Envelope: Decodable { var data: JSONValue?; var error: String?; var err_key: String? }

    private func path(_ pid: String, _ templateId: String? = nil) -> String {
        "projects/\(pid)/poll-templates" + (templateId.map { "/" + ($0.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? $0) } ?? "")
    }

    /// 403/404 mean "not yours to manage"; the server's own Vietnamese sentence wins when it has one.
    private func fail(_ status: Int, _ data: Data) -> Error {
        let e = try? JSONDecoder().decode(Envelope.self, from: data)
        let text = ERPMessage.readable(e?.error)
        switch status {
        case 400: return TemplateSyncFailure(text ?? "Mẫu không hợp lệ.")
        case 403, 404: return TemplateSyncFailure(text ?? "Bạn không có quyền quản lý mẫu của dự án này.")
        case 409 where e?.err_key == "TEMPLATE_VERSION_EXISTS": return TemplateVersionExists()
        case 413: return TemplateSyncFailure("Mẫu quá lớn (tối đa 2 MB).")
        default: return ERPError.server(text ?? "Máy chủ ERP báo lỗi \(status).")
        }
    }

    private func send(_ r: URLRequest) async throws -> (data: Data, status: Int) {
        var r = r
        r.timeoutInterval = 30
        return try await client.rawAuthorized(r)
    }

    func list(projectId: String) async throws -> ProjectTemplatesInfo? {
        let (data, status) = try await send(await client.request(path(projectId)))
        if status == 404 { return nil }
        guard status == 200 else { throw fail(status, data) }
        guard let info = (try? JSONDecoder().decode(ERPEnvelope<ProjectTemplatesInfo>.self, from: data))?.data else { throw ERPError.badResponse }
        return info
    }

    func add(projectId: String, content: JSONValue) async throws {
        let body = try JSONEncoder().encode(["content": content])
        let (data, status) = try await send(await client.request(path(projectId), method: "POST", body: body))
        guard status == 200 else { throw fail(status, data) }
    }

    func patch(projectId: String, templateId: String, version: String?, title: String?, shortName: String?, color: String?) async throws {
        var o: [String: String] = [:]
        if let version { o["version"] = version }
        if let title { o["title"] = title }
        if let shortName { o["short_name"] = shortName }
        if let color { o["color"] = color }
        let (data, status) = try await send(await client.request(path(projectId, templateId), method: "PATCH", body: try JSONEncoder().encode(o)))
        guard status == 200 else { throw fail(status, data) }
    }

    func remove(projectId: String, templateId: String) async throws -> Bool {
        let (data, status) = try await send(await client.request(path(projectId, templateId), method: "DELETE"))
        if status == 404 { return false }
        guard (200..<300).contains(status) else { throw fail(status, data) }
        return true
    }
}
