import SwiftUI
import PollMasterCore

/// Compass bearing 0-359 with a button that reads the device heading.
struct CompassInput: View {
    var unit: String?
    @Binding var value: JSONValue?
    var focus: FocusSlot?
    var location: LocationService?
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                NumberInput(placeholder: "0–359", integer: false, unit: unit, value: $value, focus: focus)
                if let location {
                    Button {
                        busy = true; error = nil
                        Task {
                            defer { busy = false }
                            do { value = .number(try await location.currentHeading().rounded()) }
                            catch { self.error = error.localizedDescription }
                        }
                    } label: { Label("Đọc la bàn", systemImage: "location.north.line") }
                        .buttonStyle(.bordered).disabled(busy)
                }
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
    }
}

struct GPSInput: View {
    let field: Field
    @Binding var value: JSONValue?
    var location: LocationService
    /// Meters to the planned site, when the record has one.
    var plannedDistance: Double?
    @State private var busy = false
    @State private var error: String?
    /// Half-typed coordinates: stored only once both lat and long exist (never a made-up 0).
    @State private var draft: [String: Double] = [:]
    @Environment(\.pmTheme) private var theme

    private var gps: GPSValue? { GPSValue(value) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button { fetch() } label: {
                if busy { ProgressView() }
                else { Label(gps == nil ? "Lấy vị trí hiện tại" : "Lấy lại", systemImage: "location.fill") }
            }
            .buttonStyle(.borderedProminent).disabled(busy)

            HStack(spacing: 8) {
                coordField("Vĩ độ (lat)", placeholder: "Chưa lấy vị trí", name: "lat", keyPath: \.lat)
                coordField("Kinh độ (long)", placeholder: "Chưa lấy vị trí", name: "lng", keyPath: \.lng)
            }
            if let g = gps {
                HStack(spacing: 6) {
                    if let a = g.accuracy {
                        let ok = a <= field.gpsMaxAccuracy
                        Label("±\(ValueFormatter.number(a, decimals: 0)) m", systemImage: ok ? "checkmark.circle.fill" : "exclamationmark.triangle.fill")
                            .foregroundStyle(ok ? theme.success : theme.warning).font(.caption.weight(.semibold))
                    }
                    if let d = plannedDistance {
                        Text("Cách điểm kế hoạch \(ValueFormatter.number(d, decimals: 0)) m").font(.caption)
                            .foregroundStyle(d > 100 ? theme.warning : theme.success)
                    }
                    Spacer()
                    if let url = URL(string: "http://maps.apple.com/?ll=\(g.lat),\(g.lng)") {
                        Link(destination: url) { Label("Bản đồ", systemImage: "map") }.font(.caption)
                    }
                }
            }
            if let error { Text(error).font(.caption).foregroundStyle(theme.alert) }
        }
    }

    private func fetch() {
        busy = true; error = nil
        Task {
            defer { busy = false }
            do {
                let l = try await location.currentLocation()
                value = GPSValue(lat: l.coordinate.latitude, lng: l.coordinate.longitude,
                                 accuracy: l.horizontalAccuracy >= 0 ? l.horizontalAccuracy : nil).json
            } catch { self.error = error.localizedDescription }
        }
    }

    private func coordField(_ label: String, placeholder: String, name: String, keyPath: WritableKeyPath<GPSValue, Double>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption2).secondaryText()
            ManualCoordinate(
                placeholder: placeholder,
                value: Binding(get: { gps?[keyPath: keyPath] ?? draft[name] }, set: { new in
                    guard let new else { return }
                    if var g = gps {
                        g[keyPath: keyPath] = new
                        g.accuracy = nil          // hand-typed coordinates carry no accuracy
                        value = g.json
                        return
                    }
                    draft[name] = new
                    if let la = draft["lat"], let ln = draft["lng"] { value = GPSValue(lat: la, lng: ln, accuracy: nil).json; draft = [:] }
                }))
        }
    }
}

private struct ManualCoordinate: View {
    let placeholder: String
    @Binding var value: Double?
    @State private var text = ""

    var body: some View {
        TextField(placeholder, text: $text)
            .keyboardType(.numbersAndPunctuation).textFieldStyle(.plain).inputBox()
            .onAppear { sync() }
            .onChange(of: text) { _, new in
                if let d = Double(new.replacingOccurrences(of: ",", with: ".")), d != value { value = d }
            }
            .onChange(of: value) { _, _ in
                if Double(text.replacingOccurrences(of: ",", with: ".")) != value { sync() }
            }
    }

    private func sync() { text = value.map { String(format: "%.6f", $0) } ?? "" }
}
