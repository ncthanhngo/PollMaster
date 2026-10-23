import SwiftUI

private struct InFieldGroupKey: EnvironmentKey { static let defaultValue = false }

extension EnvironmentValues {
    /// True inside a `FieldGroupCard`: field views draw as rows, not as their own cards.
    var inFieldGroup: Bool {
        get { self[InFieldGroupKey.self] }
        set { self[InFieldGroupKey.self] = newValue }
    }
}

/// One block for consecutive fields, rows separated by hairlines (grouped form, like iOS Settings)
/// instead of a bordered card per field: the section reads as a few groups, not a wall of cards.
struct FieldGroupCard<Data: RandomAccessCollection, Row: View>: View where Data.Element: Identifiable {
    let data: Data
    @ViewBuilder let row: (Data.Element) -> Row
    @Environment(\.pmTheme) private var theme

    var body: some View {
        VStack(spacing: 0) {
            ForEach(Array(data.enumerated()), id: \.element.id) { i, e in
                if i > 0 {
                    Rectangle().fill(theme.border.opacity(theme.highContrast ? 1 : 0.5))
                        .frame(height: theme.highContrast ? 1 : 0.5).padding(.leading, PMSpace.m)
                }
                row(e)
            }
        }
        .environment(\.inFieldGroup, true)
        .background(theme.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: PMRadius.m))
        .overlay {
            if theme.highContrast { RoundedRectangle(cornerRadius: PMRadius.m).stroke(theme.border, lineWidth: theme.borderWidth) }
        }
    }
}

/// Field chrome: a row inside a `FieldGroupCard`, or its own card elsewhere. `stroke` marks an error or a checker note.
private struct FieldContainer: ViewModifier {
    var stroke: Color?
    var compact = false
    @Environment(\.inFieldGroup) private var inGroup
    @Environment(\.pmTheme) private var theme

    func body(content: Content) -> some View {
        if inGroup {
            content
                .padding(.horizontal, PMSpace.m).padding(.vertical, compact ? 10 : 14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(stroke.map { $0.opacity(0.07) } ?? .clear)
                .overlay(alignment: .leading) { if let stroke { Rectangle().fill(stroke).frame(width: 3) } }
        } else {
            content.cardBox(stroke: stroke, padding: compact ? 8 : 12)
        }
    }
}

extension View {
    func fieldContainer(stroke: Color? = nil, compact: Bool = false) -> some View { modifier(FieldContainer(stroke: stroke, compact: compact)) }
}
