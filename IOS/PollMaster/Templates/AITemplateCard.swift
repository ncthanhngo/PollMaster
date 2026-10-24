import SwiftUI

/// Hero block at the top of the Templates tab: the AI authoring flow as two steps, each with its own action.
/// A solid brand block (flat colour blocking) so it reads as the entry point, not as another list row.
struct AITemplateCard: View {
    let onPaste: () -> Void
    @Environment(\.pmTheme) private var theme

    private var guideURL: URL? { Bundle.main.url(forResource: "huong-dan-tao-mau-bang-ai", withExtension: "md") }

    var body: some View {
        VStack(alignment: .leading, spacing: PMSpace.m) {
            HStack(spacing: 12) {
                Image(systemName: "sparkles").font(.title2.weight(.semibold))
                    .frame(width: 48, height: 48)
                    .background(.white.opacity(0.18), in: RoundedRectangle(cornerRadius: PMRadius.m))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Tạo mẫu bằng AI").font(.title3.weight(.bold))
                    Text("Mô tả biên bản, Claude hoặc ChatGPT viết mẫu cho bạn").font(.subheadline).opacity(0.85)
                }
            }
            VStack(spacing: 0) {
                step(1, "Gửi hướng dẫn cho AI", "Kèm mô tả khảo sát cần làm") {
                    if let url = guideURL {
                        ShareLink(item: url) { pill("Gửi", "square.and.arrow.up") }.buttonStyle(.borderless)
                    }
                }
                Rectangle().fill(.white.opacity(0.22)).frame(height: 0.5).padding(.leading, 52)
                step(2, "Dán JSON AI trả về", "App tự kiểm tra lỗi") {
                    Button(action: onPaste) { pill("Dán", "doc.on.clipboard") }.buttonStyle(.borderless)
                }
            }
            .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: PMRadius.m))
        }
        .foregroundStyle(.white)
        .padding(PMSpace.m)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(brand, in: RoundedRectangle(cornerRadius: PMRadius.l))
    }

    /// Brand green, same in light and dark (white text stays >= 4.5:1); plain black/white in high contrast.
    private var brand: AnyShapeStyle {
        if theme.highContrast { return AnyShapeStyle(Color.black) }
        return AnyShapeStyle(LinearGradient(colors: [Color(red: 0.13, green: 0.50, blue: 0.42), Color(red: 0.08, green: 0.32, blue: 0.27)],
                                            startPoint: .topLeading, endPoint: .bottomTrailing))
    }

    private func step<Action: View>(_ n: Int, _ title: String, _ detail: String, @ViewBuilder action: () -> Action) -> some View {
        HStack(spacing: 12) {
            Text("\(n)").font(.subheadline.weight(.bold)).foregroundStyle(theme.highContrast ? Color.black : brandInk)
                .frame(width: 28, height: 28).background(.white, in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.subheadline.weight(.semibold))
                Text(detail).font(.footnote).opacity(0.8).fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            action()
        }
        .padding(.horizontal, 12).padding(.vertical, 10)
    }

    /// Dark brand green for text on white: fixed (not the dark-mode accent), so it stays readable in both modes.
    private var brandInk: Color { Color(red: 0.08, green: 0.36, blue: 0.30) }

    /// Same-size white action pill for both steps, so the two buttons line up.
    private func pill(_ title: String, _ icon: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: icon).font(.subheadline.weight(.semibold))
            Text(title).font(.subheadline.weight(.semibold))
        }
        .foregroundStyle(theme.highContrast ? Color.black : brandInk)
        .frame(width: 88, height: 40)
        .background(.white, in: Capsule())
        .contentShape(Capsule())
    }
}
