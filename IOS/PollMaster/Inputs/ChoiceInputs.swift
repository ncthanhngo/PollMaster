import SwiftUI
import PollMasterCore

private struct Chip: View {
    var title: String
    var selected: Bool
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.subheadline)
                .padding(.horizontal, 12).padding(.vertical, 7)
                .background(selected ? Color.accentColor : Color(.tertiarySystemFill), in: Capsule())
                .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
    }
}

struct SingleChoiceInput: View {
    let field: Field
    @Binding var value: JSONValue?
    @Binding var other: [String: String]?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FlowLayout {
                ForEach(field.options ?? [], id: \.value) { o in
                    Chip(title: o.label, selected: value?.string == o.value) {
                        value = value?.string == o.value ? nil : .string(o.value)
                    }
                }
            }
            OtherTextField(options: field.options ?? [], selected: value?.string.map { [$0] } ?? [], other: $other)
        }
    }
}

struct MultiChoiceInput: View {
    let field: Field
    @Binding var value: JSONValue?
    @Binding var other: [String: String]?

    private var selected: [String] { value?.array?.compactMap(\.string) ?? [] }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            FlowLayout {
                ForEach(field.options ?? [], id: \.value) { o in
                    Chip(title: o.label, selected: selected.contains(o.value)) { toggle(o.value) }
                }
            }
            OtherTextField(options: field.options ?? [], selected: selected, other: $other)
        }
    }

    private func toggle(_ v: String) {
        var s = selected
        if let i = s.firstIndex(of: v) { s.remove(at: i) } else { s.append(v) }
        // keep option order
        let order = (field.options ?? []).map(\.value)
        s.sort { (order.firstIndex(of: $0) ?? 0) < (order.firstIndex(of: $1) ?? 0) }
        value = s.isEmpty ? nil : .array(s.map { .string($0) })
    }
}

/// Free-text box for selected options flagged `withText`.
private struct OtherTextField: View {
    let options: [Option]
    let selected: [String]
    @Binding var other: [String: String]?

    var body: some View {
        ForEach(options.filter { $0.withText && selected.contains($0.value) }, id: \.value) { o in
            TextField("\(o.label): ghi rõ", text: Binding(
                get: { other?[o.value] ?? "" },
                set: { new in
                    var d = other ?? [:]
                    d[o.value] = new.isEmpty ? nil : new
                    other = d.isEmpty ? nil : d
                }))
                .textFieldStyle(.roundedBorder)
        }
    }
}

struct YesNoInput: View {
    let field: Field
    @Binding var value: JSONValue?

    var body: some View {
        let l = field.labels ?? ["Có", "Không"]
        HStack(spacing: 8) {
            button(l.first ?? "Có", selected: value?.bool == true) { value = value?.bool == true ? nil : .bool(true) }
            button(l.count > 1 ? l[1] : "Không", selected: value?.bool == false) { value = value?.bool == false ? nil : .bool(false) }
            if field.allowUnknown {
                button("KĐĐ", selected: value?.string == "unknown") { value = value?.string == "unknown" ? nil : .string("unknown") }
            }
        }
    }

    private func button(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(.subheadline.weight(.medium)).frame(maxWidth: .infinity).padding(.vertical, 9)
                .background(selected ? Color.accentColor : Color(.tertiarySystemFill), in: RoundedRectangle(cornerRadius: 8))
                .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
    }
}

struct RatingInput: View {
    let field: Field
    @Binding var value: JSONValue?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                ForEach(1...max(field.ratingMax, 1), id: \.self) { n in
                    let on = value?.double == Double(n)
                    Button { value = on ? nil : .int(n) } label: {
                        Text("\(n)").font(.headline).frame(width: 40, height: 40)
                            .background(on ? Color.accentColor : Color(.tertiarySystemFill), in: Circle())
                            .foregroundStyle(on ? Color.white : Color.primary)
                    }
                    .buttonStyle(.plain)
                }
            }
            if field.minLabel != nil || field.maxLabel != nil {
                HStack {
                    Text(field.minLabel ?? ""); Spacer(); Text(field.maxLabel ?? "")
                }
                .font(.caption2).foregroundStyle(.secondary)
            }
        }
    }
}
