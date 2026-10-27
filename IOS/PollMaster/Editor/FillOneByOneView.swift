import SwiftUI
import PollMasterCore

/// "Điền lần lượt" (ux-spec L8): one missing field at a time, in template order. Tiếp saves and moves on, Bỏ qua leaves it,
/// KĐĐ asks for a reason, tables and media can be opened in place and the flow comes back here.
struct FillOneByOneView: View {
    let model: RecordEditorModel
    @Environment(AppEnvironment.self) private var env
    @Environment(\.pmTheme) private var theme
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focused: String?
    @State private var current: FillStep?
    @State private var total = 0
    @State private var position = 1
    @State private var skipped: Set<String> = []
    @State private var started = false
    @State private var askReason = false
    @State private var reason = ""

    private var field: Field? { current.flatMap { model.template.field($0.fieldId) } }
    private var table: TableDef? { current.flatMap { model.template.table($0.fieldId) } }

    var body: some View {
        ZStack {
            theme.screenBackground.ignoresSafeArea()
            if let step = current { content(step) }
            else if started {
                ContentUnavailableView {
                    Label("Đã xong các mục cần điền", systemImage: "checkmark.circle.fill")
                } description: {
                    Text(skipped.isEmpty ? "Quay lại Kiểm tra để hoàn thành." : "\(skipped.count) mục đã bỏ qua vẫn còn thiếu.")
                } actions: {
                    Button("Xong") { dismiss() }.buttonStyle(.borderedProminent)
                }
            }
        }
        .navigationTitle("Điền lần lượt")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Thoát") { model.flush(); dismiss() } }
            ToolbarItemGroup(placement: .keyboard) { Spacer(); Button("Xong") { focused = nil } }
        }
        .onAppear {
            guard !started else { return }
            let steps = FillQueue.steps(model.template, model.record)
            total = steps.count; current = steps.first; started = true
        }
        .alert("Lý do không đo được", isPresented: $askReason) {
            TextField("Lý do", text: $reason)
            Button("Hủy", role: .cancel) {}
            Button("Lưu") { markUnmeasurable() }
        }
    }

    @ViewBuilder private func content(_ step: FillStep) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("\(min(position, max(total, 1))) / \(total) · \(step.sectionTitle)")
                    .font(.subheadline.weight(.semibold)).secondaryText()
                if let t = table {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(t.title).font(.headline)
                        Text("Bảng còn thiếu dữ liệu bắt buộc.").font(.subheadline).secondaryText()
                        openButton(step, title: "Mở bảng")
                    }
                    .cardBox()
                } else if let f = field {
                    FieldCard(field: f, fv: model.binding(f.id), scope: model.scope(focus: $focused, sectionId: step.sectionId), allowCompact: false)
                        .id(f.id)
                    if f.isMedia { openButton(step, title: "Mở mục \(step.sectionTitle)") }
                }
            }
            .padding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .safeAreaInset(edge: .bottom) { actionBar }
    }

    private func openButton(_ step: FillStep, title: String) -> some View {
        Button {
            guard let idx = model.sectionIndex(of: step.sectionId) else { return }
            env.editorPath.append(.section(record: model.record.id, index: idx, focus: step.fieldId))
        } label: { Label(title, systemImage: "arrow.up.right.square").frame(maxWidth: .infinity, minHeight: max(32, theme.minTouch)) }
            .buttonStyle(.bordered)
    }

    private var actionBar: some View {
        HStack(spacing: 8) {
            Button { advance(skip: true) } label: { Text("Bỏ qua").frame(maxWidth: .infinity, minHeight: max(36, theme.minTouch)) }
                .buttonStyle(.bordered)
            if field?.flagsEnabled == true {
                Button { reason = ""; askReason = true } label: { Text("KĐĐ").frame(maxWidth: .infinity, minHeight: max(36, theme.minTouch)) }
                    .buttonStyle(.bordered)
            }
            Button { advance(skip: false) } label: { Text("Tiếp").font(.headline).frame(maxWidth: .infinity, minHeight: max(36, theme.minTouch)) }
                .buttonStyle(.borderedProminent)
        }
        .controlSize(.large)
        .padding(.horizontal).padding(.vertical, 8)
        .background(.bar)
    }

    private func advance(skip: Bool) {
        guard let cur = current else { return }
        focused = nil
        model.flush()
        if skip { skipped.insert(cur.fieldId) }
        if let next = FillQueue.next(model.template, model.record, after: cur.fieldId, skipped: skipped) {
            current = next; position += 1
        } else { current = nil }
    }

    private func markUnmeasurable() {
        guard let f = field else { return }
        let text = reason.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        var fv = model.record.value(f.id)
        fv.flag = .notMeasurable; fv.reason = text
        model.setValue(f.id, fv)
        advance(skip: false)
    }
}
