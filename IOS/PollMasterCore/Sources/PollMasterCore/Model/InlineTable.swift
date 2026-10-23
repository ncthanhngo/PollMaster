import Foundation

/// Layout of a fixed-row table answered straight on the list (`inline: true`, schema §8).
public struct InlineLayout: Equatable, Sendable {
    /// Readonly columns shown as the row label.
    public var labelColumns: [Field]
    /// First choice column, shown as buttons.
    public var choice: Field
    /// Other editable columns, opened by the ✎ sheet.
    public var sheetColumns: [Field]
}

extension TableDef {
    /// nil unless the table is fixed, `inline`, and its first choice column (yesNo / singleChoice) has at most 4 options.
    public var inlineLayout: InlineLayout? {
        guard inline, isFixed,
              let choice = columns.first(where: { !$0.readonly && ($0.type == .yesNo || $0.type == .singleChoice) }) else { return nil }
        if choice.type == .singleChoice, (choice.options?.count ?? 0) > 4 { return nil }
        return InlineLayout(labelColumns: columns.filter(\.readonly), choice: choice,
                            sheetColumns: columns.filter { !$0.readonly && $0.id != choice.id })
    }
}
