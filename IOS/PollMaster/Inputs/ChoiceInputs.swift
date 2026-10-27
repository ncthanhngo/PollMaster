import SwiftUI
import PollMasterCore

/// Selectable chip: filled accent colour + check mark when chosen, outlined when not.
struct Chip: View {
    var title: String
    var selected: Bool
    var fillWidth = false
    var minHeight: CGFloat = 36
    var action: () -> Void
    @Environment(\.pmTheme) private var theme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                // Grid cells are narrow: the check mark goes in the corner so the label never wraps.
                if selected && !fillWidth { Image(systemName: "checkmark").font(.caption.weight(.bold)) }
                Text(title).font(.subheadline.weight(selected ? .semibold : .regular)).multilineTextAlignment(.center)
                    .lineLimit(fillWidth && title.count <= 4 ? 1 : 3).minimumScaleFactor(0.7)
            }
            .padding(.horizontal, fillWidth ? 4 : 10).padding(.vertical, 6)
            .frame(maxWidth: fillWidth ? .infinity : nil, minHeight: max(minHeight, theme.minTouch))
            .hitSlop(max(0, (44 - max(minHeight, theme.minTouch)) / 2), .vertical)
            .overlay(alignment: .topTrailing) {
                if selected && fillWidth { Image(systemName: "checkmark").font(.system(size: 9, weight: .bold)).padding(4) }
            }
            .background(selected ? Color.accentColor : theme.unselectedChip, in: RoundedRectangle(cornerRadius: PMRadius.s))
            .overlay(RoundedRectangle(cornerRadius: PMRadius.s).stroke(selected || !theme.highContrast ? Color.clear : theme.border, lineWidth: theme.borderWidth))
            .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// Columns for a grid of `options`: 5 for many short codes, otherwise as many as the labels allow.
private func gridColumns(_ options: [Option], quick: Bool) -> Int {
    let longest = options.map(\.label.count).max() ?? 0
    if options.count > 6 && longest <= 4 { return 5 }
    if longest > 10 { return 2 }
    return min(max(options.count, 1), quick ? 3 : 4)
}

struct SingleChoiceInput: View {
    let field: Field
    @Binding var value: JSONValue?
    @Binding var other: [String: String]?
    /// One-tap button grid, never a menu ("Ghi lượt").
    var quick = false
    @Environment(\.pmTheme) private var theme

    private var options: [Option] { field.options ?? [] }
    private var manyShort: Bool { options.count > 6 && options.allSatisfy { $0.label.count <= 4 } }
    private var asMenu: Bool { options.count > 6 && !manyShort && !quick }

    private func pick(_ o: Option) { value = value?.string == o.value ? nil : .string(o.value) }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if asMenu {
                menu
            } else if manyShort || quick {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: gridColumns(options, quick: quick)), spacing: 6) {
                    ForEach(options, id: \.value) { o in
                        Chip(title: o.label, selected: value?.string == o.value, fillWidth: true, minHeight: quick ? 44 : 36) { pick(o) }
                    }
                }
            } else {
                FlowLayout {
                    ForEach(options, id: \.value) { o in Chip(title: o.label, selected: value?.string == o.value) { pick(o) } }
                }
            }
            OtherTextField(options: options, selected: value?.string.map { [$0] } ?? [], other: $other)
        }
    }

    private var menu: some View {
        Menu {
            ForEach(options, id: \.value) { o in
                Button { pick(o) } label: {
                    if value?.string == o.value { Label(o.label, systemImage: "checkmark") } else { Text(o.label) }
                }
            }
            if value != nil { Button("Bỏ chọn", role: .destructive) { value = nil } }
        } label: {
            HStack {
                Text(value?.string.map(field.optionLabel) ?? "Chọn…")
                    .foregroundStyle(value == nil ? theme.secondaryText : Color.primary)
                Spacer()
                Image(systemName: "chevron.up.chevron.down").font(.caption).secondaryText()
            }
            .inputBox()
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
                .textFieldStyle(.plain).inputBox()
        }
    }
}

struct YesNoInput: View {
    let field: Field
    @Binding var value: JSONValue?
    var quick = false

    var body: some View {
        let l = field.labels ?? ["Có", "Không"]
        HStack(spacing: 8) {
            Chip(title: l.first ?? "Có", selected: value?.bool == true, fillWidth: true, minHeight: quick ? 44 : 38) {
                value = value?.bool == true ? nil : .bool(true)
            }
            Chip(title: l.count > 1 ? l[1] : "Không", selected: value?.bool == false, fillWidth: true, minHeight: quick ? 44 : 38) {
                value = value?.bool == false ? nil : .bool(false)
            }
            if field.allowUnknown {
                Chip(title: "KĐĐ", selected: value?.string == "unknown", fillWidth: true, minHeight: quick ? 44 : 38) {
                    value = value?.string == "unknown" ? nil : .string("unknown")
                }
            }
        }
    }
}

struct RatingInput: View {
    let field: Field
    @Binding var value: JSONValue?
    @Environment(\.pmTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                ForEach(1...max(field.ratingMax, 1), id: \.self) { n in
                    let on = value?.double == Double(n)
                    Button { value = on ? nil : .int(n) } label: {
                        Text("\(n)").font(.headline).frame(width: 40, height: 40)
                            .background(on ? Color.accentColor : theme.unselectedChip, in: Circle())
                            .overlay(Circle().stroke(on || !theme.highContrast ? Color.clear : theme.border, lineWidth: theme.borderWidth))
                            .foregroundStyle(on ? Color.white : Color.primary)
                    }
                    .buttonStyle(.plain)
                }
            }
            if field.minLabel != nil || field.maxLabel != nil {
                HStack {
                    Text(field.minLabel ?? ""); Spacer(); Text(field.maxLabel ?? "")
                }
                .font(.caption2).secondaryText()
            }
        }
    }
}
