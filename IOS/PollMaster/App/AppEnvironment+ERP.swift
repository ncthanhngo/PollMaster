import Foundation
import UIKit
import PollMasterCore

/// ERP sign-in and the project list it brings (projects are not created on the device).
extension AppEnvironment {
    private static let accountKey = "erpAccount", resetKey = "erpLocalDataReset"

    static func storedAccount() -> ERPAccount? {
        guard TokenStore.refresh != nil, let d = UserDefaults.standard.data(forKey: accountKey) else { return nil }
        return try? JSONDecoder().decode(ERPAccount.self, from: d)
    }

    private func setAccount(_ a: ERPAccount?) {
        account = a
        if let a, let d = try? JSONEncoder().encode(a) { UserDefaults.standard.set(d, forKey: Self.accountKey) }
        else { UserDefaults.standard.removeObject(forKey: Self.accountKey) }
    }

    /// Projects and records made before ERP sign-in existed are wiped once (templates stay); true while that is still ahead.
    var localDataResetPending: Bool {
        !UserDefaults.standard.bool(forKey: Self.resetKey) && !recordStore.list().isEmpty
    }

    func login(email: String, password: String) async throws {
        let a = try await erp.login(email: email.trimmingCharacters(in: .whitespaces), password: password)
        if !UserDefaults.standard.bool(forKey: Self.resetKey) {
            editors = [:]
            try ERPProjectSync.resetLocalData(root: root)
            UserDefaults.standard.set(true, forKey: Self.resetKey)
            currentProjectId = nil
        }
        setAccount(a)
        reload()
        await syncProjects()
    }

    /// Records stay on the device; projects the next account cannot see stay hidden.
    func logout() async {
        editors.values.forEach { $0.flush() }
        await erp.logout()
        setAccount(nil)
        currentProjectId = nil
        projectsPath = []; recordsPath = []
    }

    /// Fetches the ERP project list (sign-in, app opened, pull to refresh). Offline the last list stays.
    func syncProjects() async {
        guard account != nil, !syncing else { return }
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "pmAccount") { return }      // sample account of the screenshots: no tokens
        #endif
        syncing = true
        defer { syncing = false }
        do {
            let list = try await erp.projects()
            for change in try ERPProjectSync.apply(list, to: projectStore, library: newestTemplates.map(TemplateRef.init)) {
                if let url = change.url {
                    // a failed download leaves no file, so the next sync tries again
                    if let data = try? await erp.file(url), let jpeg = UIImage(data: data)?.squareThumbnail(256).jpegData(compressionQuality: 0.85) {
                        try? projectStore.setAvatar(jpeg, for: change.projectId)
                    } else { try? projectStore.removeAvatar(change.projectId) }
                } else { try? projectStore.removeAvatar(change.projectId) }
            }
            syncError = nil
            reload()
        } catch ERPError.sessionExpired {
            setAccount(nil)
            errorMessage = ERPError.sessionExpired.localizedDescription
        } catch {
            syncError = "Chưa tải được danh sách dự án từ ERP: \(error.localizedDescription)"
        }
    }
}
