import SwiftUI
import UniformTypeIdentifiers
import PollMasterCore

/// "Chọn từ danh sách" for a `lookup` field; without a site list it turns into "Nạp danh sách điểm (CSV)" (ux-spec L13).
struct LookupButton: View {
    let field: Field
    @Binding var fv: FieldValue
    let scope: FieldScope
    @Environment(AppEnvironment.self) private var env
    @State private var picking = false
    @State private var importing = false
    @State private var pendingSite: Site?
    @State private var message: String?

    private var sites: [Site] { env.sites(forProject: scope.projectId) }
    private var noSites: Bool { sites.isEmpty }

    var body: some View {
        if field.lookup != nil, !scope.locked, !field.isComputed {
            Button { if noSites { importing = true } else { picking = true } } label: {
                Label(noSites ? "Nạp danh sách điểm (CSV)" : "Chọn từ danh sách",
                      systemImage: noSites ? "square.and.arrow.down" : "list.bullet.rectangle")
            }
            .buttonStyle(.bordered)
            .sheet(isPresented: $picking) { SitePicker(sites: sites, onPick: choose) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.commaSeparatedText, .plainText, .text]) { result in
                switch result {
                case .success(let url):
                    do { _ = try env.importSites(from: url, projectId: scope.projectId); picking = true } catch { message = error.localizedDescription }
                case .failure(let e): message = e.localizedDescription
                }
            }
            .alert("Danh sách điểm", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
                Button("OK") { message = nil }
            } message: { Text(message ?? "") }
            .confirmationDialog(conflictTitle, isPresented: Binding(get: { pendingSite != nil }, set: { if !$0 { pendingSite = nil } }),
                                titleVisibility: .visible) {
                Button("Ghi đè") { fill(overwrite: true) }
                Button("Chỉ điền ô trống") { fill(overwrite: false) }
                Button("Hủy", role: .cancel) {}
            }
        }
    }

    private var conflictTitle: String {
        guard let site = pendingSite, let lk = field.lookup else { return "" }
        return "Ghi đè \(scope.lookupConflicts(lk, site).joined(separator: ", "))?"
    }

    private func choose(_ site: Site) {
        fv.v = .string(site.code)
        guard let lk = field.lookup else { return }
        if scope.lookupConflicts(lk, site).isEmpty { scope.applyLookup(lk, site, true) } else { pendingSite = site }
    }

    private func fill(overwrite: Bool) {
        if let site = pendingSite, let lk = field.lookup { scope.applyLookup(lk, site, overwrite) }
        pendingSite = nil
    }
}
