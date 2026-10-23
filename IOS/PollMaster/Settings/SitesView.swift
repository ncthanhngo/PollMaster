import SwiftUI
import UniformTypeIdentifiers
import PollMasterCore

/// Import / inspect / clear the planned-site list of one project (CSV `code,address,lat,lng`).
struct SitesView: View {
    let projectId: String
    @Environment(AppEnvironment.self) private var env
    @State private var picking = false
    @State private var message: String?
    @State private var confirmClear = false
    @State private var query = ""
    @State private var filter = Visit.all

    private enum Visit: String, CaseIterable, Identifiable {
        case all = "Tất cả", visited = "Đã có phiếu", pending = "Chưa có phiếu"
        var id: String { rawValue }
    }

    private var sites: [Site] { env.sites(forProject: projectId) }
    private var visited: Set<String> { env.visitedCodes(forProject: projectId) }
    private var shown: [Site] {
        sites.filter { s in
            s.matches(query) && (filter == .all || (filter == .visited) == visited.contains(s.code))
        }
    }

    var body: some View {
        List {
            Section {
                Button("Nhập tệp CSV", systemImage: "square.and.arrow.down") { picking = true }
                if !sites.isEmpty { Button("Xóa danh sách", systemImage: "trash", role: .destructive) { confirmClear = true } }
            } header: {
                Text("\(sites.count) điểm · \(sites.filter { visited.contains($0.code) }.count) đã có phiếu")
            } footer: {
                Text("CSV UTF-8, dòng đầu: code,address,lat,lng. Cột khác bị bỏ qua. Nhập mới sẽ thay danh sách của dự án này.")
            }
            if !sites.isEmpty {
                Section {
                    Picker("Lọc", selection: $filter) { ForEach(Visit.allCases) { Text($0.rawValue).tag($0) } }.pickerStyle(.segmented)
                }
            }
            Section {
                ForEach(shown) { s in
                    HStack(alignment: .top) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(s.code).font(.headline)
                            if !s.address.isEmpty { Text(s.address).font(.subheadline).secondaryText() }
                            if let la = s.lat, let ln = s.lng {
                                Text(String(format: "%.6f, %.6f", la, ln)).font(.caption.monospacedDigit()).secondaryText()
                            }
                        }
                        Spacer()
                        if visited.contains(s.code) {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).accessibilityLabel("Đã có phiếu")
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
                do { message = "Đã nhập \(try env.importSites(from: url, projectId: projectId)) điểm." } catch { message = error.localizedDescription }
            case .failure(let e): message = e.localizedDescription
            }
        }
        .confirmationDialog("Xóa toàn bộ danh sách điểm của dự án?", isPresented: $confirmClear, titleVisibility: .visible) {
            Button("Xóa", role: .destructive) {
                if let p = env.project(id: projectId) {
                    do { try env.clearSites(of: p) } catch { message = error.localizedDescription }
                }
            }
        }
        .alert("Danh sách điểm", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK") { message = nil }
        } message: { Text(message ?? "") }
    }
}
