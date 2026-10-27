import Foundation
import UIKit
import PollMasterCore

/// Dialog of the one-time wipe of pre-ERP data at the first sign-in.
enum WipePrompt: Equatable {
    case confirm(records: Int, projects: Int)
    case backupFailed(failed: Int, total: Int)
}

enum WipeAnswer { case proceed, retry, cancel }

/// ERP sign-in and the project list it brings (projects are not created on the device).
extension AppEnvironment {
    private static let accountKey = "erpAccount", resetKey = "erpLocalDataReset"

    static func storedAccount() -> ERPAccount? {
        guard TokenStore.refresh != nil, let d = UserDefaults.standard.data(forKey: accountKey) else { return nil }
        return try? JSONDecoder().decode(ERPAccount.self, from: d)
    }

    /// Launch without a stored account: tokens left in the Keychain (it survives an app reinstall) belong to nobody.
    static func forgetOrphanTokens() -> ERPAccount? {
        TokenStore.clear()
        return nil
    }

    /// Projects and records made before ERP sign-in existed are wiped once (templates stay); true while that is still ahead.
    static func resetPending(root: URL) -> Bool {
        guard !UserDefaults.standard.bool(forKey: resetKey) else { return false }
        let n = ERPProjectSync.localDataCounts(root: root)
        return n.records + n.projects > 0
    }

    /// A new account (or none) starts a new session: results of requests made for the old one are dropped.
    private func setAccount(_ a: ERPAccount?) {
        sessionGen += 1
        account = a
        if let a, let d = try? JSONEncoder().encode(a) { UserDefaults.standard.set(d, forKey: Self.accountKey) }
        else { UserDefaults.standard.removeObject(forKey: Self.accountKey) }
    }

    /// Fills in details of the same account (the user id found later) without ending the session.
    func updateAccountDetails(_ a: ERPAccount) {
        account = a
        if let d = try? JSONEncoder().encode(a) { UserDefaults.standard.set(d, forKey: Self.accountKey) }
    }

    func setSessionExpired() {
        setAccount(nil)
        errorMessage = ERPError.sessionExpired.localizedDescription
    }

    /// Launched before the first unlock nothing was readable: look again when the app becomes active.
    func recheckAccount() {
        guard account == nil, let a = Self.storedAccount() else { return }
        setAccount(a)
        installBundledTemplates()
        reload()
        Task { await syncProjects() }
    }

    func login(email: String, password: String) async throws {
        var a = try await erp.login(email: email.trimmingCharacters(in: .whitespaces), password: password)
        if a.userId == nil { a.userId = try? await erp.me() }
        if !UserDefaults.standard.bool(forKey: Self.resetKey) {
            do { try await wipeLocalDataForERP() } catch {
                await erp.logout()          // declined or failed: stay on the sign-in screen without tokens
                throw error
            }
        }
        setAccount(a)
        reload()
        await syncProjects()
    }

    /// Owner's decision: data made before ERP projects goes. Nothing is wiped without a confirmation and a backup of every record
    /// (a failed backup needs an explicit "Xóa không sao lưu"). Throws `CancellationError` when the user declines.
    private func wipeLocalDataForERP() async throws {
        let n = ERPProjectSync.localDataCounts(root: root)
        if n.records + n.projects > 0 {
            guard await ask(.confirm(records: n.records, projects: n.projects)) == .proceed else { throw CancellationError() }
            while true {
                let r = await backupAll(ids: recordStore.ids())
                if r.failed == 0 { break }
                switch await ask(.backupFailed(failed: r.failed, total: r.done + r.failed)) {
                case .retry: continue
                case .proceed: break
                case .cancel: throw CancellationError()
                }
                break
            }
            // nothing may be written after the wipe: drop the editors with their pending autosaves first
            (Array(editors.values) + closingEditors.values).forEach { $0.discard() }
            editors = [:]; closingEditors = [:]
            recordsPath = []
            try ERPProjectSync.resetLocalData(root: root)
        }
        UserDefaults.standard.set(true, forKey: Self.resetKey)
        localDataResetPending = false
        currentProjectId = nil
        summaries = []; projectStats = [:]; visitedByProject = [:]
    }

    private func ask(_ prompt: WipePrompt) async -> WipeAnswer {
        await withCheckedContinuation { c in
            wipeAnswer = c
            wipePrompt = prompt
        }
    }

    /// The user's choice in the wipe dialog.
    func answerWipe(_ answer: WipeAnswer) {
        guard let c = wipeAnswer else { return }
        wipeAnswer = nil
        wipePrompt = nil
        c.resume(returning: answer)
    }

    /// Records stay on the device; projects the next account cannot see stay hidden.
    func logout() async {
        flushEditors()
        syncTask?.cancel(); syncTask = nil; syncing = false
        cancelRecordSync()
        setAccount(nil)
        currentProjectId = nil
        projectsPath = []; recordsPath = []
        await erp.logout()
    }

    /// Fetches the ERP project list (sign-in, app opened, pull to refresh). Offline the last list stays.
    func syncProjects() async {
        guard account != nil else { return }
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "pmAccount") { return }      // sample account of the screenshots: no tokens
        #endif
        if syncing, syncGen == sessionGen { return }        // one already runs for this session; a newer session starts its own
        let gen = sessionGen
        syncGen = gen; syncing = true
        let task = Task { await runSync(gen) }
        syncTask = task
        await task.value
    }

    private func runSync(_ gen: Int) async {
        defer { if syncGen == gen { syncing = false; syncTask = nil } }
        do {
            let list = try await erp.projects()
            guard gen == sessionGen else { return }
            for change in try ERPProjectSync.apply(list, to: projectStore, library: newestTemplates.map(TemplateRef.init)) {
                guard gen == sessionGen else { return }
                if let url = change.url {
                    // a failed download leaves no file, so the next sync tries again
                    if let data = try? await erp.file(url), gen == sessionGen,
                       let jpeg = UIImage(data: data)?.squareThumbnail(256).jpegData(compressionQuality: 0.85) {
                        try? projectStore.setAvatar(jpeg, for: change.projectId)
                    } else if gen == sessionGen { try? projectStore.removeAvatar(change.projectId) }
                } else { try? projectStore.removeAvatar(change.projectId) }
            }
            guard gen == sessionGen else { return }
            syncError = nil
            reload()
            requestRecordSync()
        } catch ERPError.sessionExpired {
            guard gen == sessionGen else { return }        // signed out meanwhile: nothing to report
            setSessionExpired()
        } catch {
            guard gen == sessionGen else { return }
            syncError = "Chưa tải được danh sách dự án từ ERP: \(error.localizedDescription)"
        }
    }
}
