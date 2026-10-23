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

    var body: some View {
        switch field.type {
        case .text:
            HStack(spacing: 6) {
                if let p = field.prefix { Text(p).foregroundStyle(.secondary).font(.subheadline) }
                TextField(field.hint ?? "", text: text)
                    .textFieldStyle(.roundedBorder).submitLabel(.next).focusSlot(focus)
            }
        case .multiline:
            TextField(field.hint ?? "", text: text, axis: .vertical)
                .lineLimit(3...8).textFieldStyle(.roundedBorder).focusSlot(focus)
        case .number, .integer:
            HStack(spacing: 6) {
                NumberInput(placeholder: field.hint ?? "", integer: field.type == .integer, value: $value, focus: focus)
                if showUnit, let u = field.unit { Text(u).font(.subheadline).foregroundStyle(.secondary).fixedSize() }
            }
        case .singleChoice: SingleChoiceInput(field: field, value: $value, other: $other)
        case .multiChoice: MultiChoiceInput(field: field, value: $value, other: $other)
        case .yesNo: YesNoInput(field: field, value: $value)
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
