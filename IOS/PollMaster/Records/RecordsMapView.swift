import SwiftUI
import MapKit
import PollMasterCore

/// Records with a GPS value as pins coloured by status (Nháp grey, Hoàn thành green, Cần sửa orange), plus planned sites
/// without a record as hollow pins (ux-spec L18). Tap a pin for a summary card.
struct RecordsMapView: View {
    let records: [RecordSummary]
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @State private var camera: MapCameraPosition = .automatic
    @State private var selected: Pin?

    private enum Pin: Hashable { case record(String), site(String) }

    private var located: [RecordSummary] { records.filter { $0.coordinate != nil } }
    private var unvisited: [Site] { env.sites.filter { $0.hasCoordinate && !env.visitedSiteCodes.contains($0.code) } }

    var body: some View {
        Map(position: $camera) {
            ForEach(located) { s in
                if let c = s.coordinate {
                    Annotation(s.title, coordinate: CLLocationCoordinate2D(latitude: c.lat, longitude: c.lng), anchor: .bottom) {
                        pin("mappin.circle.fill", color: color(s), selected: selected == .record(s.id)).onTapGesture { selected = .record(s.id) }
                    }
                }
            }
            ForEach(unvisited) { site in
                Annotation(site.code, coordinate: CLLocationCoordinate2D(latitude: site.lat ?? 0, longitude: site.lng ?? 0), anchor: .bottom) {
                    pin("mappin.circle", color: .gray, selected: selected == .site(site.code)).onTapGesture { selected = .site(site.code) }
                }
            }
        }
        .mapControls { MapCompass(); MapScaleView() }
        .overlay(alignment: .bottom) { card.padding() }
        .overlay {
            if located.isEmpty && unvisited.isEmpty {
                ContentUnavailableView("Chưa có điểm nào", systemImage: "map",
                                       description: Text("Phiếu có tọa độ GPS và điểm trong danh sách điểm sẽ hiện ở đây."))
                    .background(.regularMaterial)
            }
        }
        .onAppear { camera = fitted() }
    }

    private func color(_ s: RecordSummary) -> Color {
        if s.returned && s.status == .draft { return .orange }
        return s.status == .completed ? .green : .gray
    }

    private func pin(_ icon: String, color: Color, selected: Bool) -> some View {
        Image(systemName: icon).font(.system(size: selected ? 38 : 30)).foregroundStyle(color)
            .background(Circle().fill(.white).padding(4))
            .frame(minWidth: 44, minHeight: 44)
    }

    @ViewBuilder private var card: some View {
        switch selected {
        case .record(let id)?:
            if let s = records.first(where: { $0.id == id }) {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(s.title).font(.headline).lineLimit(1)
                        Spacer()
                        Button { selected = nil } label: { Image(systemName: "xmark.circle.fill").secondaryText() }.buttonStyle(.plain)
                    }
                    Text(s.subtitle).font(.subheadline).secondaryText().lineLimit(1)
                    Text("\(statusText(s)) · \(s.progress.done)/\(s.progress.total) bắt buộc").font(.caption).foregroundStyle(color(s))
                    Button { env.open(s.id) } label: { Text("Mở phiếu").frame(maxWidth: .infinity) }.buttonStyle(.borderedProminent)
                }
                .padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            }
        case .site(let code)?:
            if let site = env.sites.first(where: { $0.code == code }) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(site.code).font(.headline)
                        Spacer()
                        Button { selected = nil } label: { Image(systemName: "xmark.circle.fill").secondaryText() }.buttonStyle(.plain)
                    }
                    if !site.address.isEmpty { Text(site.address).font(.subheadline).secondaryText() }
                    Text("Điểm kế hoạch · chưa có phiếu").font(.caption).secondaryText()
                }
                .padding(12).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
            }
        case nil: EmptyView()
        }
    }

    private func statusText(_ s: RecordSummary) -> String {
        s.returned && s.status == .draft ? "Cần sửa" : (s.status == .completed ? "Hoàn thành" : "Nháp")
    }

    /// Region around every pin; automatic (device area) when there is none.
    private func fitted() -> MapCameraPosition {
        let pts = located.compactMap(\.coordinate).map { ($0.lat, $0.lng) } + unvisited.compactMap { s in s.lat.flatMap { la in s.lng.map { (la, $0) } } }
        guard let first = pts.first else { return .automatic }
        var (minLa, maxLa, minLn, maxLn) = (first.0, first.0, first.1, first.1)
        for (la, ln) in pts { minLa = min(minLa, la); maxLa = max(maxLa, la); minLn = min(minLn, ln); maxLn = max(maxLn, ln) }
        let span = MKCoordinateSpan(latitudeDelta: max((maxLa - minLa) * 1.5, 0.02), longitudeDelta: max((maxLn - minLn) * 1.5, 0.02))
        return .region(MKCoordinateRegion(center: CLLocationCoordinate2D(latitude: (minLa + maxLa) / 2, longitude: (minLn + maxLn) / 2), span: span))
    }
}
