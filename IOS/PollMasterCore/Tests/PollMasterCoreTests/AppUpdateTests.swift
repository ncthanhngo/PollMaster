import Testing
import Foundation
@testable import PollMasterCore

@Suite struct AppUpdateTests {
    private let json = Data("""
    [
      {"tag_name": "ios-v1.0.0-38", "draft": false, "prerelease": false, "assets": []},
      {"tag_name": "android-v1.0.0-37", "draft": false, "prerelease": false,
       "assets": [{"browser_download_url": "https://github.com/x/y/releases/download/android-v1.0.0-37/PollMaster-1.0.0-37.apk"}]},
      {"tag_name": "android-v1.0.0-39", "draft": false, "prerelease": true, "assets": []},
      {"tag_name": "android-v1.0.0-36", "assets": []},
      {"tag_name": "android-vbroken", "assets": []}
    ]
    """.utf8)

    @Test func picksTheNewestBuildOfThePlatform() throws {
        let u = try #require(AppUpdate.newest(releases: json, prefix: "android-v", current: 36))
        #expect(u.build == 37 && u.version == "1.0.0")
        #expect(u.downloadURL?.lastPathComponent == "PollMaster-1.0.0-37.apk")
        #expect(AppUpdate.newest(releases: json, prefix: "ios-v", current: 36)?.build == 38)
    }

    @Test func nothingWhenCurrentOrUnreadable() {
        #expect(AppUpdate.newest(releases: json, prefix: "android-v", current: 37) == nil)
        #expect(AppUpdate.newest(releases: Data("{\"message\":\"rate limited\"}".utf8), prefix: "ios-v", current: 1) == nil)
    }
}
