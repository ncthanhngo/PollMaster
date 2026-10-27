import SwiftUI
import PollMasterCore

/// The inputs that depend on the field's type (default value, labels, counts…).
extension FieldForm {
    @ViewBuilder var typeSection: some View {
        switch typeName {
        case "text":
            Section("Ô nhập") {
                TextField("Giá trị mặc định", text: defaultText)
                TextField("Tiền tố hiện trước giá trị", text: model.str(path, "prefix"))
                Toggle("Ô ngắn (nửa dòng)", isOn: model.flag(path, "short"))
                Toggle("Kèm ảnh", isOn: model.flag(path, "attachPhoto"))
            }
        case "multiline": Section("Ô nhập") { TextField("Giá trị mặc định", text: defaultText, axis: .vertical).lineLimit(1...4) }
        case "number", "integer":
            Section { NumField(title: "Giá trị mặc định", value: model.num(path, "default")); Toggle("Kèm ảnh", isOn: model.flag(path, "attachPhoto")) }
        case "date": Section { Toggle("Mặc định là hôm nay", isOn: special("$today")) }
        case "datetime": Section { Toggle("Mặc định là bây giờ", isOn: special("$now")) }
        case "yesNo":
            Section("Có / Không") {
                TextField("Nhãn nút \"Có\"", text: label(0, "Có"))
                TextField("Nhãn nút \"Không\"", text: label(1, "Không"))
                Toggle("Cho chọn \"Không đánh giá được\"", isOn: model.flag(path, "allowUnknown"))
            }
        case "rating":
            Section("Đánh giá sao") {
                NumField(title: "Số sao tối đa", value: model.num(path, "max"), placeholder: "5")
                TextField("Nhãn mức thấp", text: model.str(path, "minLabel"))
                TextField("Nhãn mức cao", text: model.str(path, "maxLabel"))
            }
        case "photo", "video", "audio":
            Section("Số lượng") {
                NumField(title: "Ít nhất", value: model.num(path, "minCount"))
                NumField(title: "Nhiều nhất", value: model.num(path, "maxCount"))
            }
        case "gps": Section { NumField(title: "Sai số tối đa (m)", value: model.num(path, "maxAccuracy"), placeholder: "15") }
        default: EmptyView()
        }
    }

    private var typeName: String { model.text(path, "type").isEmpty ? "text" : model.text(path, "type") }

    private var defaultText: Binding<String> {
        Binding(get: { model.object(path)["default"]?.string ?? "" }, set: { model.set(path, "default", .string($0)) })
    }

    private func special(_ token: String) -> Binding<Bool> {
        Binding(get: { model.object(path)["default"]?.string == token }, set: { model.set(path, "default", $0 ? .string(token) : nil) })
    }

    /// `labels` is [yes, no]; both default texts remove the key.
    private func label(_ i: Int, _ fallback: String) -> Binding<String> {
        Binding(get: { (model.object(path)["labels"]?.array ?? []).indices.contains(i) ? (model.object(path)["labels"]?.array?[i].string ?? "") : "" },
                set: { new in
                    var l = (model.object(path)["labels"]?.array ?? []).map { $0.string ?? "" }
                    while l.count < 2 { l.append(l.count == 0 ? "Có" : "Không") }
                    l[i] = new.isEmpty ? fallback : new
                    model.set(path, "labels", l == ["Có", "Không"] ? nil : .array(l.map(JSONValue.string)))
                })
    }
}
