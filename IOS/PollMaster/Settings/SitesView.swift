import SwiftUI
import UniformTypeIdentifiers
import PollMasterCore

/// Import / inspect / clear the planned-site list (CSV `code,address,lat,lng`).
struct SitesView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var picking = false
    @State private var message: String?
    @State private var confirmClear = false
    @State private var query = ""

    private var shown: [Site] { env.sites.filter { $0.matches(query) } }

    var body: some View {
        List {
            Section {
                Button("Nhập tệp CSV", systemImage: "square.and.arrow.down") { picking = true }
                if !env.sites.isEmpty { Button("Xóa danh sách", systemImage: "trash", role: .destructive) { confirmClear = true } }
            } header: { Text("\(env.sites.count) điểm") } footer: {
                Text("CSV UTF-8, dòng đầu: code,address,lat,lng. Cột khác bị bỏ qua. Nhập mới sẽ thay danh sách hiện có.")
            }
            Section {
                ForEach(shown) { s in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(s.code).font(.headline)
                        if !s.address.isEmpty { Text(s.address).font(.subheadline).secondaryText() }
                        if let la = s.lat, let ln = s.lng {
                            Text(String(format: "%.6f, %.6f", la, ln)).font(.caption.monospacedDigit()).secondaryText()
                        }
                    }
                }
            }
        }
        .searchable(text: $query, prompt: "Tìm theo mã hoặc địa chỉ")
        .navigationTitle("Danh sách điểm")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $picking, allowedContentTypes: [.commaSeparatedText, .plainText, .text]) { result in
            switch result {
            case .success(let url):
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let sites = try SitesCSV.parse(Data(contentsOf: url))
                    try env.siteStore.save(sites)
                    env.reloadSites()
                    message = "Đã nhập \(sites.count) điểm."
                } catch { message = error.localizedDescription }
            case .failure(let e): message = e.localizedDescription
            }
        }
        .confirmationDialog("Xóa toàn bộ danh sách điểm?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Xóa", role: .destructive) {
                do { try env.siteStore.clear(); env.reloadSites() } catch { message = error.localizedDescription }
            }
        }
        .alert("Danh sách điểm", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil }
        } message: { Text(message ?? "") }
    }
}
