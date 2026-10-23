import SwiftUI

/// Wraps children onto new lines when the row is full.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    private func rows(_ subviews: Subviews, width: CGFloat) -> [[Int]] {
        var rows: [[Int]] = [[]]
        var x: CGFloat = 0
        for (i, s) in subviews.enumerated() {
            let w = s.sizeThatFits(.unspecified).width
            if x + w > width, !rows[rows.count - 1].isEmpty { rows.append([]); x = 0 }
            rows[rows.count - 1].append(i)
            x += w + spacing
        }
        return rows
    }

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var height: CGFloat = 0
        let rs = rows(subviews, width: width)
        for r in rs { height += r.map { subviews[$0].sizeThatFits(.unspecified).height }.max() ?? 0 }
        height += spacing * CGFloat(max(rs.count - 1, 0))
        let used = rs.map { r in r.reduce(CGFloat(0)) { $0 + subviews[$1].sizeThatFits(.unspecified).width } + spacing * CGFloat(max(r.count - 1, 0)) }.max() ?? 0
        return CGSize(width: proposal.width ?? used, height: height)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var y = bounds.minY
        for r in rows(subviews, width: bounds.width) {
            var x = bounds.minX
            let h = r.map { subviews[$0].sizeThatFits(.unspecified).height }.max() ?? 0
            for i in r {
                let s = subviews[i].sizeThatFits(.unspecified)
                subviews[i].place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
                x += s.width + spacing
            }
            y += h + spacing
        }
    }
}
