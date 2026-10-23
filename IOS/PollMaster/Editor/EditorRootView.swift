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
                    .foregroundStyle(.orange).font(.subheadline) }
            }
            if let resume = model.resumeTarget {
                Section {
                    NavigationLink(value: Route.section(record: model.record.id, index: resume.index, focus: resume.fieldId)) {
                        Label("Tiếp tục: \(resume.fieldId) \(resume.label)", systemImage: "arrow.right.circle.fill").lineLimit(2)
                    }
                    .foregroundStyle(Color.accentColor)
                }
            }
            Section { ProgressRow(progress: model.progress) }
            Section("Mục lục") {
                ForEach(Array(model.sectionTitles.enumerated()), id: \.offset) { i, s in
                    NavigationLink(value: Route.section(record: model.record.id, index: i, focus: nil)) {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.title)
                                statsLine(s.id)
                            }
                            Spacer()
                            sectionBadge(s.id)
                        }
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
        let saved = model.lastSaved.map { " · Đã lưu \(ISODate.format($0, "HH:mm"))" } ?? ""
        return "\(model.progress.done)/\(model.progress.total) bắt buộc\(saved)"
    }

    /// Sub-line of every section: required x/y · rows of repeat tables · photos/videos; 📷 while a required photo is missing (L14).
    @ViewBuilder private func statsLine(_ id: String) -> some View {
        if let st = model.sectionStats[id], !st.line.isEmpty || st.missingPhoto {
            HStack(spacing: 4) {
                if !st.line.isEmpty { Text(st.line).monospacedDigit() }
                if st.missingPhoto { Image(systemName: "camera.fill").foregroundStyle(theme.warning).accessibilityLabel("Còn thiếu ảnh bắt buộc") }
            }
            .font(.caption).secondaryText()
        }
    }

    /// Green check when the section's required items are complete; red count only after the first "Kiểm tra".
    @ViewBuilder private func sectionBadge(_ id: String) -> some View {
        let missing = model.issueCount(section: id)
        if model.showAllErrors && missing > 0 {
            Text("\(missing)").font(.caption.bold()).foregroundStyle(.white)
                .padding(.horizontal, 8).padding(.vertical, 2).background(Color.red, in: Capsule())
        } else if let p = model.sectionProgress[id], p.total > 0, p.done == p.total {
            Image(systemName: "checkmark").font(.footnote.weight(.bold)).foregroundStyle(theme.success)
        }
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
