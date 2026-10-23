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
                NumberInput(placeholder: "0–359", integer: false, value: $value, focus: focus)
                if let unit { Text(unit).font(.caption).foregroundStyle(.secondary) }
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
    @Binding var value: JSONValue?
    var location: LocationService
    @State private var busy = false
    @State private var error: String?

    private var gps: GPSValue? { GPSValue(value) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button {
                busy = true; error = nil
                Task {
                    defer { busy = false }
                    do {
                        let l = try await location.currentLocation()
                        value = GPSValue(lat: l.coordinate.latitude, lng: l.coordinate.longitude,
                                         accuracy: l.horizontalAccuracy >= 0 ? l.horizontalAccuracy : nil).json
                    } catch { self.error = error.localizedDescription }
                }
            } label: {
                if busy { ProgressView() } else { Label("Lấy vị trí hiện tại", systemImage: "location.fill") }
            }
            .buttonStyle(.borderedProminent).disabled(busy)

            HStack {
                coordField("Vĩ độ (lat)", keyPath: \.lat)
                coordField("Kinh độ (long)", keyPath: \.lng)
            }
            if let g = gps {
                HStack {
                    if let a = g.accuracy { Text("Độ chính xác ±\(ValueFormatter.number(a, decimals: 0)) m") }
                    Spacer()
                    if let url = URL(string: "http://maps.apple.com/?ll=\(g.lat),\(g.lng)") {
                        Link(destination: url) { Label("Mở bản đồ", systemImage: "map") }
                    }
                }
                .font(.caption).foregroundStyle(.secondary)
            }
            if let error { Text(error).font(.caption).foregroundStyle(.red) }
        }
    }

    private func coordField(_ label: String, keyPath: WritableKeyPath<GPSValue, Double>) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            ManualCoordinate(
                value: Binding(get: { gps?[keyPath: keyPath] }, set: { new in
                    guard let new else { return }
                    var g = gps ?? GPSValue(lat: 0, lng: 0, accuracy: nil)
                    g[keyPath: keyPath] = new
                    g.accuracy = nil          // hand-typed coordinates carry no accuracy
                    value = g.json
                }))
        }
    }
}

private struct ManualCoordinate: View {
    @Binding var value: Double?
    @State private var text = ""

    var body: some View {
        TextField("0.000000", text: $text)
            .keyboardType(.numbersAndPunctuation).textFieldStyle(.roundedBorder)
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
