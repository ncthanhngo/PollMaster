import SwiftUI
import PollMasterCore

/// Input for single-value types; reused for samples, parts and table cells.
struct ScalarInput: View {
    let field: Field
    @Binding var value: JSONValue?
    @Binding var other: [String: String]?
    var focus: FocusSlot?
    var location: LocationService?
    var showUnit = true
    /// One-tap choice grids instead of menus.
    var quick = false

    var body: some View {
        switch field.type {
        case .text:
            HStack(spacing: 6) {
                if let p = field.prefix { Text(p).secondaryText().font(.subheadline).fixedSize() }
                TextField(field.hint ?? "Nhập…", text: text)
                    .textFieldStyle(.plain).submitLabel(.next).focusSlot(focus)
            }
            .inputBox()
        case .multiline:
            TextField(field.hint ?? "Nhập…", text: text, axis: .vertical)
                .lineLimit(3...8).textFieldStyle(.plain).inputBox().focusSlot(focus)
        case .number, .integer:
            NumberInput(placeholder: field.hint ?? "Nhập…", integer: field.type == .integer,
                        unit: showUnit ? field.unit : nil, value: $value, focus: focus)
        case .singleChoice: SingleChoiceInput(field: field, value: $value, other: $other, quick: quick)
        case .multiChoice: MultiChoiceInput(field: field, value: $value, other: $other)
        case .yesNo: YesNoInput(field: field, value: $value, quick: quick)
        case .rating: RatingInput(field: field, value: $value)
        case .date, .time, .datetime, .month: DateTimeInput(type: field.type, value: $value)
        case .elapsed: ElapsedInput(value: $value, focus: focus)
        case .compass: CompassInput(unit: showUnit ? field.unit : nil, value: $value, focus: focus, location: location)
        default: EmptyView()
        }
    }

    private var text: Binding<String> {
        Binding(get: { value?.string ?? "" }, set: { value = $0.isEmpty ? nil : .string($0) })
    }
}
