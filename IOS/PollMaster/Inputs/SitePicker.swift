import SwiftUI
import PollMasterCore

/// Searchable list of planned sites (by code or address).
struct SitePicker: View {
    let sites: [Site]
    let onPick: (Site) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var shown: [Site] { sites.filter { $0.matches(query) } }

    var body: some View {
        NavigationStack {
            Group {
                if sites.isEmpty {
                    ContentUnavailableView("Chưa có danh sách điểm", systemImage: "mappin.slash",
                                           description: Text("Nhập tệp CSV tại Cài đặt > Danh sách điểm."))
                } else {
                    List(shown) { s in
                        Button { onPick(s); dismiss() } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.code).font(.headline).foregroundStyle(.primary)
                                if !s.address.isEmpty { Text(s.address).font(.subheadline).secondaryText() }
                                if !s.hasCoordinate { Text("Chưa có tọa độ").font(.caption).secondaryText() }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, prompt: "Tìm theo mã hoặc địa chỉ")
            .navigationTitle("Chọn điểm")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } } }
        }
    }
}
