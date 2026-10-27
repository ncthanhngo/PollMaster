import Foundation
import UIKit
import PollMasterCore

/// "Có bản mới": the release script publishes a GitHub release `ios-v<version>-<build>` once TestFlight has processed the build.
extension AppEnvironment {
    private static let promptedKey = "updatePromptedBuild"
    /// PollMaster in TestFlight; the App Store page of TestFlight itself when that app is missing.
    private static let testFlightApp = URL(string: "itms-beta://beta.itunes.apple.com/v1/app/6826277832")!
    private static let testFlightStore = URL(string: "https://apps.apple.com/app/testflight/id899247664")!

    static var currentBuild: Int { Int(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "") ?? 0 }

    /// At launch and when the app comes back, at most once an hour (GitHub allows 60 anonymous calls an hour per address).
    func checkForUpdate() async {
        #if DEBUG
        guard UserDefaults.standard.bool(forKey: "pmUpdateCheck") else { return }
        #endif
        if let last = updateCheckedAt, Date().timeIntervalSince(last) < 3600 { return }
        updateCheckedAt = Date()
        guard let (data, res) = try? await URLSession.shared.data(from: AppUpdate.releasesURL),
              (res as? HTTPURLResponse)?.statusCode == 200 else { return }
        availableUpdate = AppUpdate.newest(releases: data, prefix: "ios-v", current: Self.currentBuild)
        if let u = availableUpdate, UserDefaults.standard.integer(forKey: Self.promptedKey) < u.build {
            UserDefaults.standard.set(u.build, forKey: Self.promptedKey)
            updatePrompt = u
        }
    }

    /// Opens PollMaster in TestFlight, where "Cập nhật" installs the new build.
    func openUpdate() {
        UIApplication.shared.open(Self.testFlightApp) { opened in
            if !opened { UIApplication.shared.open(Self.testFlightStore) }
        }
    }
}
