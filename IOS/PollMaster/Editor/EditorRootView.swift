import SwiftUI
import PollMasterCore

/// Resolves a navigation route to the matching editor screen.
struct RouteDestination: View {
    let route: Route
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        Group {
            destination
        }
        .toolbar(.hidden, for: .tabBar)      // the whole editing flow runs full screen
    }

    @ViewBuilder private var destination: some View {
        if let model = env.editor(for: route.recordId) {
            switch route {
            case .editor: EditorRootView(model: model)
            case .section(_, let index, let focus): SectionView(model: model, index: index, focus: focus)
            case .row(_, let table, let row): RowFormView(model: model, tableId: table, rowId: row)
            case .review: ReviewView(model: model)
            case .fill: FillOneByOneView(model: model)
            }
        } else {
            ContentUnavailableView("Không mở được phiếu", systemImage: "exclamationmark.triangle",
                                   description: Text("Phiếu hoặc mẫu của phiếu không còn trên máy."))
        }
    }
}

/// Table of contents of a record: header + sections with neutral progress, resume button.
struct EditorRootView: View {
    let model: RecordEditorModel
    @Environment(AppEnvironment.self) private var env
    @Environment(\.colorScheme) private var scheme
    @Environment(\.pmTheme) private var theme
    @State private var exporting = false
    @State private var showInstructions = false
    @State private var confirmReturn = false

    var body: some View {
        List {
            if model.isLocked { Section { LockedBanner(model: model) } }
            if model.record.returned && !model.isLocked {
                Section { Label("Phiếu được trả lại để sửa. Xem các ghi chú kiểm tra màu cam.", systemImage: "arrow.uturn.backward.circle.fill")
                    .foregroundStyle(theme.attention).font(.subheadline) }
            }
            Section { progressHero }
            Section("Mục lục") {
                ForEach(Array(model.sectionTitles.enumerated()), id: \.offset) { i, s in
                    NavigationLink(value: Route.section(record: model.record.id, index: i, focus: nil)) {
                        HStack(spacing: 12) {
                            sectionIcon(s.id)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(s.title).font(.body.weight(.medium))
                                statsLine(s.id)
                            }
                            Spacer(minLength: 4)
                            if model.showAllErrors, model.issueCount(section: s.id) > 0 {
                                Text("\(model.issueCount(section: s.id))").font(.caption.bold()).foregroundStyle(.white)
                                    .padding(.horizontal, 8).padding(.vertical, 2).background(theme.alert, in: Capsule())
                            }
                        }
                        .padding(.vertical, 2)
                    }
                }
            }
            Section {
                NavigationLink(value: Route.review(record: model.record.id)) {
                    Label("Kiểm tra & hoàn thành", systemImage: "checkmark.seal")
                }
            }
            if model.isLocked && model.reviewMode {
                Section {
                    Button("Trả lại để sửa", systemImage: "arrow.uturn.backward") { confirmReturn = true }
                    Button("Thoát chế độ kiểm tra") { model.reviewMode = false }
                } header: { Text("Chế độ kiểm tra") } footer: { Text("Chạm thẻ trường để thêm ghi chú kiểm tra.") }
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                VStack(spacing: 0) {
                    Text(model.title).font(.headline).lineLimit(1).truncationMode(.tail)
                    Text(subtitle).font(.caption2).secondaryText().lineLimit(1)
                }
            }
            ToolbarItem(placement: .primaryAction) {
                Button(model.showAllErrors && !model.issues.isEmpty ? "Kiểm tra · \(model.issues.count)" : "Kiểm tra") {
                    model.showAllErrors = true
                    env.recordsPath.append(.review(record: model.record.id))
                }
                .font(.subheadline)
            }
            ToolbarItem(placement: .secondaryAction) {
                Menu {
                    Button("Xuất", systemImage: "square.and.arrow.up") { exporting = true }
                    Button("Hướng dẫn mẫu", systemImage: "book") { showInstructions = true }
                    if model.isLocked {
                        Button(model.reviewMode ? "Thoát chế độ kiểm tra" : "Chế độ kiểm tra", systemImage: "text.badge.checkmark") {
                            model.reviewMode.toggle()
                        }
                    }
                    Button(scheme == .dark ? "Giao diện sáng" : "Giao diện tối", systemImage: scheme == .dark ? "sun.max" : "moon") {
                        env.settings.appearance = AppearanceMode.toggled(displayingDark: scheme == .dark)
                    }
                    Button("Sao lưu ngay", systemImage: "externaldrive") { Task { await model.backup(force: true) } }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .sheet(isPresented: $exporting) { ExportSheet(recordId: model.record.id) }
        .sheet(isPresented: $showInstructions) { InstructionsSheet(template: model.template) }
        .confirmationDialog("Trả phiếu về cho người khảo sát sửa? Trạng thái sẽ về Nháp, kèm các ghi chú kiểm tra.", isPresented: $confirmReturn,
                            titleVisibility: .visible) {
            Button("Trả lại để sửa") { model.returnForFix() }
        }
        .onDisappear { model.flush() }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15 * 60))
                await model.backup()
            }
        }
        .alert("Lỗi", isPresented: Binding(get: { model.saveError != nil || model.backupError != nil }, set: { if !$0 { model.saveError = nil; model.backupError = nil } })) {
            Button("OK") { model.saveError = nil; model.backupError = nil }
        } message: { Text(model.saveError ?? model.backupError ?? "") }
    }

    private var subtitle: String {
        model.lastSaved.map { "Đã lưu \(ISODate.format($0, "HH:mm"))" } ?? "Tự động lưu"
    }

    /// Progress at a glance plus the "continue where I left off" button (the screen's one primary action).
    private var progressHero: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                Text("\(model.progress.done)").font(.largeTitle.weight(.bold)).monospacedDigit()
                Text("/ \(model.progress.total) bắt buộc").font(.subheadline).secondaryText()
                Spacer()
                Text("\(Int((model.progress.fraction * 100).rounded())) %").font(.headline).monospacedDigit()
                    .foregroundStyle(model.progress.fraction >= 1 ? theme.success : Color.accentColor)
            }
            ProgressView(value: model.progress.fraction).tint(model.progress.fraction >= 1 ? theme.success : Color.accentColor)
            if let resume = model.resumeTarget, !model.isLocked {
                NavigationLink(value: Route.section(record: model.record.id, index: resume.index, focus: resume.fieldId)) {
                    Label("Tiếp tục: \(resume.fieldId) \(resume.label)", systemImage: "arrow.right")
                        .font(.subheadline.weight(.semibold)).lineLimit(1)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(.vertical, 6)
    }

    /// Status of a section: done (check), started (half), not started (dashed), nothing required (dot).
    @ViewBuilder private func sectionIcon(_ id: String) -> some View {
        let p = model.sectionProgress[id]
        if let p, p.total > 0, p.done == p.total { IconBadge(systemImage: "checkmark", tint: theme.success, size: 32) }
        else if let p, p.done > 0 { IconBadge(systemImage: "circle.lefthalf.filled", size: 32) }
        else if let p, p.total > 0 { IconBadge(systemImage: "circle.dashed", tint: theme.secondaryText, size: 32) }
        else { IconBadge(systemImage: "list.bullet", tint: theme.secondaryText, size: 32) }
    }

    /// Sub-line of every section: required x/y · rows of repeat tables · photos/videos; 📷 while a required photo is missing (L14).
    @ViewBuilder private func statsLine(_ id: String) -> some View {
        let st = model.sectionStats[id]
        HStack(spacing: 4) {
            Text(st.map { $0.line.isEmpty ? "Không có mục bắt buộc" : $0.line } ?? "").monospacedDigit()
            if st?.missingPhoto == true { Image(systemName: "camera.fill").foregroundStyle(theme.warning).accessibilityLabel("Còn thiếu ảnh bắt buộc") }
        }
        .font(.footnote).secondaryText()
    }
}

struct ProgressRow: View {
    let progress: RequiredProgress
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(progress.done)/\(progress.total) bắt buộc").font(.subheadline.weight(.medium))
            ProgressView(value: progress.fraction)
        }
    }
}

struct LockedBanner: View {
    let model: RecordEditorModel
    @State private var confirm = false
    @Environment(\.pmTheme) private var theme

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Đã hoàn thành lúc \(model.record.completedAt.map { ISODate.format($0, "HH:mm dd/MM/yyyy") } ?? "")",
                  systemImage: "checkmark.circle.fill").foregroundStyle(theme.success)
            HStack {
                Button("Mở lại") { confirm = true }.buttonStyle(.bordered)
                if !model.reviewMode { Button("Chế độ kiểm tra") { model.reviewMode = true }.buttonStyle(.bordered) }
            }
        }
        .confirmationDialog("Mở lại phiếu để chỉnh sửa? Trạng thái sẽ về Nháp.", isPresented: $confirm, titleVisibility: .visible) {
            Button("Mở lại") { model.reopen() }
        }
    }
}

struct InstructionsSheet: View {
    let template: Template
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(template.instructions ?? "Mẫu này không có hướng dẫn.")
                    .frame(maxWidth: .infinity, alignment: .leading).padding()
            }
            .navigationTitle("Hướng dẫn")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Xong") { dismiss() } } }
        }
    }
}
