import Foundation

/// A newer build published by the release scripts: GitHub releases of the public repo, tagged `<platform>-v<version>-<build>`
/// (`android-v1.0.0-37`, `ios-v1.0.0-37`); the build is the commit count, so a larger one is newer.
public struct AppUpdate: Equatable {
    public let build: Int
    public let version: String
    /// First asset (the Android APK); nil for iOS, which updates through TestFlight.
    public let downloadURL: URL?

    /// GitHub `GET /repos/{owner}/{repo}/releases` of the repo the app is released from.
    public static let releasesURL = URL(string: "https://api.github.com/repos/ncthanhngo/PollMaster/releases?per_page=30")!

    /// Newest release tagged `prefix…` with a build above `current`; nil when there is none or the list is unreadable.
    public static func newest(releases json: Data, prefix: String, current: Int) -> AppUpdate? {
        struct Release: Decodable {
            struct Asset: Decodable { var browser_download_url: URL }
            var tag_name: String
            var draft: Bool?
            var prerelease: Bool?
            var assets: [Asset]?
        }
        guard let list = try? JSONDecoder().decode([Release].self, from: json) else { return nil }
        return list.compactMap { r -> AppUpdate? in
            guard r.draft != true, r.prerelease != true, r.tag_name.hasPrefix(prefix) else { return nil }
            let rest = r.tag_name.dropFirst(prefix.count)
            guard let dash = rest.lastIndex(of: "-"), let build = Int(rest[rest.index(after: dash)...]) else { return nil }
            return AppUpdate(build: build, version: String(rest[..<dash]), downloadURL: r.assets?.first?.browser_download_url)
        }
        .filter { $0.build > current }
        .max { $0.build < $1.build }
    }
}
