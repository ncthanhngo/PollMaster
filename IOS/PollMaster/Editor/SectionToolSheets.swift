import SwiftUI
import PollMasterCore

/// Dialogs behind the section menu: bulk KĐĐ and copying a section from another record.
struct SectionToolSheets: ViewModifier {
    let model: RecordEditorModel
    let sectionId: String
    @Binding var askKDD: Bool
    @Binding var askCopy: Bool
    @State private var reason = ""
    @State private var source: Record?
    @State private var result: String?

    private var emptyCount: Int { model.emptyFlaggableCount(section: sectionId) }

    func body(content: Content) -> some View {
        content
            .alert(emptyCount == 0 ? "Không có trường nào để đánh dấu" : "Đánh dấu KĐĐ \(emptyCount) trường còn trống?", isPresented: $askKDD) {
                if emptyCount > 0 {
                    TextField("Lý do chung (bắt buộc)", text: $reason)
                    Button("Đánh dấu \(emptyCount) trường") {
                        let n = model.markUnmeasurable(section: sectionId, reason: reason)
                        result = n > 0 ? "Đã đánh dấu KĐĐ \(n) trường." : "Cần nhập lý do."
                        reason = ""
                    }
                    Button("Hủy", role: .cancel) { reason = "" }
                } else { Button("OK", role: .cancel) {} }
            } message: { Text(emptyCount == 0 ? "Mọi trường cho phép KĐĐ trong mục này đã có dữ liệu." : "Nhập một lý do chung, áp dụng cho tất cả trường này.") }
            .sheet(isPresented: $askCopy) {
                CopySectionSheet(model: model) { picked in
                    if model.hasData(section: sectionId) { source = picked } else { copy(picked) }
                }
            }
            .confirmationDialog("Mục này đã có dữ liệu. Ghi đè bằng dữ liệu từ phiếu đã chọn?", isPresented: Binding(get: { source != nil }, set: { if !$0 { source = nil } }),
                                titleVisibility: .visible) {
                Button("Ghi đè", role: .destructive) { if let s = source { copy(s) }; source = nil }
            }
            .alert(result ?? "", isPresented: Binding(get: { result != nil }, set: { if !$0 { result = nil } })) { Button("OK") {} }
    }

    private func copy(_ s: Record) {
        let n = model.copySection(sectionId, from: s)
        result = n > 0 ? "Đã chép \(n) mục dữ liệu (không gồm ảnh/video)." : "Phiếu đó không có dữ liệu ở mục này."
    }
}

private struct CopySectionSheet: View {
    let model: RecordEditorModel
    let onPick: (Record) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var records: [Record] = []

    var body: some View {
        NavigationStack {
            Group {
                if records.isEmpty {
                    ContentUnavailableView("Không có phiếu khác", systemImage: "doc", description: Text("Cần một phiếu khác cùng mẫu và phiên bản."))
                } else {
                    List(records) { r in
                        Button { onPick(r); dismiss() } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(RecordNaming.title(model.template, r)).font(.headline).foregroundStyle(.primary)
                                Text("Cập nhật \(ISODate.format(r.updatedAt, "dd/MM HH:mm"))").font(.caption).secondaryText()
                            }
                        }
                    }
                }
            }
            .navigationTitle("Chọn phiếu nguồn")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Đóng") { dismiss() } } }
        }
        .onAppear { records = model.otherRecords() }
    }
}
