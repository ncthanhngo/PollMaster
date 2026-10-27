import Foundation
import Security
import PollMasterCore

/// Signed-in ERP user, shown in Cài đặt.
struct ERPAccount: Codable, Equatable {
    var name: String
    var email: String
    var org: String?
}

enum ERPError: LocalizedError {
    case server(String)
    /// The refresh token was refused: the user has to sign in again.
    case sessionExpired
    case mustChangePassword
    case badResponse

    var errorDescription: String? {
        switch self {
        case .server(let m): return m
        case .sessionExpired: return "Phiên đăng nhập ERP đã hết hạn. Hãy đăng nhập lại."
        case .mustChangePassword: return "Tài khoản cần đổi mật khẩu. Hãy đăng nhập ERP trên web hoặc app ERP để đổi, rồi đăng nhập lại."
        case .badResponse: return "Máy chủ ERP trả về dữ liệu không đọc được."
        }
    }
}

/// ERP REST API (same backend and login as the ERP mobile app; EVSELab and tenant accounts such as Kimsen alike).
/// Access token 4 h, refresh token 30 days and rotated on every refresh; both kept in the Keychain.
actor ERPClient {
    /// Uploaded files (`/uploads/…`) are served from the host root, the API under `/api/v1`.
    static let host = URL(string: "https://api.evselab.com")!
    private static let api = host.appendingPathComponent("api/v1")
    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 20
        // The backend applies the per-organisation "may use the mobile app" switch to requests marked like this.
        c.httpAdditionalHeaders = ["X-Client-App": "evs-mobile", "Accept": "application/json"]
        return URLSession(configuration: c)
    }()
    private var refreshing: Task<Void, Error>?

    private struct Tokens: Decodable {
        var token: String
        var refresh_token: String
    }

    private struct LoginData: Decodable {
        struct User: Decodable { var name: String?; var display_name: String?; var email: String?; var org_name: String? }
        var token: String
        var refresh_token: String
        var user: User?
        var must_change_password: Bool?
    }

    func login(email: String, password: String) async throws -> ERPAccount {
        let body = try JSONSerialization.data(withJSONObject: ["email": email, "password": password])
        let d: LoginData = try await send(request("auth/login", method: "POST", body: body))
        if d.must_change_password == true { throw ERPError.mustChangePassword }
        TokenStore.save(access: d.token, refresh: d.refresh_token)
        let name = [d.user?.display_name, d.user?.name].compactMap { $0?.trimmingCharacters(in: .whitespaces) }.first { !$0.isEmpty }
        return ERPAccount(name: name ?? email, email: d.user?.email ?? email, org: d.user?.org_name.flatMap { $0.isEmpty ? nil : $0 })
    }

    /// Revokes the refresh token (best effort) and forgets both tokens.
    func logout() async {
        if let refresh = TokenStore.refresh, let body = try? JSONSerialization.data(withJSONObject: ["refresh_token": refresh]) {
            _ = try? await session.data(for: request("auth/logout", method: "POST", body: body))
        }
        TokenStore.clear()
    }

    /// Active projects the user may see (the backend scopes them by permission and organisation), every page.
    func projects() async throws -> [ERPProject] {
        var all: [ERPProject] = [], page = 1
        while true {
            let p: ERPPage<ERPProject> = try await authorized("projects", query: ["status": "active", "page_size": "100", "page": "\(page)"])
            all += p.items
            if p.items.isEmpty || all.count >= p.total { return all }
            page += 1
        }
    }

    /// Project avatar / customer logo (public static file).
    func file(_ path: String) async throws -> Data {
        guard let url = ERPProjectSync.fileURL(path, host: Self.host) else { throw ERPError.badResponse }
        let (data, res) = try await session.data(from: url)
        guard (res as? HTTPURLResponse)?.statusCode == 200 else { throw ERPError.badResponse }
        return data
    }

    // MARK: plumbing

    private func request(_ path: String, method: String = "GET", query: [String: String] = [:], body: Data? = nil) -> URLRequest {
        var c = URLComponents(url: Self.api.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { c.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
        var r = URLRequest(url: c.url!)
        r.httpMethod = method
        if let body { r.httpBody = body; r.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        return r
    }

    /// GET with the access token; a 401 refreshes once (shared by concurrent calls) and retries.
    private func authorized<T: Decodable>(_ path: String, query: [String: String]) async throws -> T {
        var r = request(path, query: query)
        r.setValue("Bearer \(TokenStore.access ?? "")", forHTTPHeaderField: "Authorization")
        do { return try await send(r) } catch ERPHTTPError.status(401, _) {
            try await refresh()
            r.setValue("Bearer \(TokenStore.access ?? "")", forHTTPHeaderField: "Authorization")
            return try await send(r)
        }
    }

    private func refresh() async throws {
        if let refreshing { return try await refreshing.value }
        let task = Task { try await doRefresh() }
        refreshing = task
        defer { refreshing = nil }
        try await task.value
    }

    private func doRefresh() async throws {
        // Only a refused token (400/401) ends the session; network trouble keeps it for the next try.
        guard let refresh = TokenStore.refresh else { throw ERPError.sessionExpired }
        let body = try JSONSerialization.data(withJSONObject: ["refresh_token": refresh])
        do {
            let t: Tokens = try await send(request("auth/refresh", method: "POST", body: body))
            TokenStore.save(access: t.token, refresh: t.refresh_token)
        } catch ERPHTTPError.status(let code, _) where code == 400 || code == 401 {
            TokenStore.clear()
            throw ERPError.sessionExpired
        }
    }

    private func send<T: Decodable>(_ r: URLRequest) async throws -> T {
        let (data, res) = try await session.data(for: r)
        let status = (res as? HTTPURLResponse)?.statusCode ?? 0
        let envelope = try? JSONDecoder().decode(ERPEnvelope<T>.self, from: data)
        guard (200..<300).contains(status) else {
            // Machine codes (SCREAMING_SNAKE) are not for people; the backend's Vietnamese sentences are.
            let message = (try? JSONDecoder().decode(ERPEnvelope<JSONValue>.self, from: data))?.error.flatMap { $0 == $0.uppercased() ? nil : $0 }
            // the backend answers a wrong password or a malformed one with 400/401 and an English text
            if status == 400 || status == 401, r.url?.path.hasSuffix("auth/login") == true { throw ERPError.server("Sai email hoặc mật khẩu.") }
            throw status == 401 ? ERPHTTPError.status(401, message) : ERPError.server(message ?? "Máy chủ ERP báo lỗi \(status).")
        }
        guard let value = envelope?.data else { throw ERPError.badResponse }
        return value
    }
}

private enum ERPHTTPError: Error { case status(Int, String?) }

/// Access and refresh tokens in the Keychain (this device only, readable after the first unlock).
enum TokenStore {
    private static let service = "com.evselab.pollmaster.erp"

    static var access: String? { read("access") }
    static var refresh: String? { read("refresh") }

    static func save(access: String, refresh: String) { write("access", access); write("refresh", refresh) }
    static func clear() { for a in ["access", "refresh"] { SecItemDelete(query(a) as CFDictionary) } }

    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    private static func read(_ account: String) -> String? {
        var q = query(account)
        q[kSecReturnData as String] = true
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    private static func write(_ account: String, _ value: String) {
        SecItemDelete(query(account) as CFDictionary)
        var q = query(account)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }
}
