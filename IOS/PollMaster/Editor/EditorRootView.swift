import SwiftUI
import PollMasterCore

/// Resolves a navigation route to the matching editor screen.
struct RouteDestination: View {
    let route: Route
    @Environment(AppEnvironment.self) private var env

    var body: some View {
        if let model = env.editor(for: route.recordId) {
            switch route {
            case .editor: EditorRootView(model: model)
            case .section(_, let index, let focus): SectionView(model: model, index: index, focus: focus)
            case .row(_, let table, let row): RowFormView(model: model, tableId: table, rowId: row)
            case .review: ReviewView(model: model)
            }
        } else {
            ContentUnavailableView("Không mở được phiếu", systemImage: "exclamationmark.triangle",
                                   description: Text("Phiếu hoặc mẫu của phiếu không còn trên máy."))
        }
    }
}

/// Table of contents of a record: header + sections with missing-required counts.
struct EditorRootView: View {
    let model: RecordEditorModel
    @Environment(AppEnvironment.self) private var env
    @State private var exporting = false
    @State private var showInstructions = false

    var body: some View {
        List {
            if model.isLocked {
                Section { LockedBanner(model: model) }
            }
            Section {
                ProgressRow(progress: model.progress)
            }
            Section("Mục lục") {
                ForEach(Array(model.sectionTitles.enumerated()), id: \.offset) { i, s in
                    NavigationLink(value: Route.section(record: model.record.id, index: i, focus: nil)) {
                        HStack {
                            Text(s.title)
                            Spacer()
                            let n = model.issueCount(section: s.id)
                            if n > 0 { Text("\(n)").font(.caption.bold()).foregroundStyle(.white)
                                .padding(.horizontal, 8).padding(.vertical, 2).background(Color.red, in: Capsule()) }
                        }
                    }
                }
            }
            Section {
                NavigationLink(value: Route.review(record: model.record.id)) {
                    Label("Kiểm tra & hoàn thành", systemImage: "checkmark.seal")
                }
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("Kiểm tra") {
                    model.showAllErrors = true
                    env.recordsPath.append(.review(record: model.record.id))
                }
            }
            ToolbarItem(placement: .secondaryAction) {
                Menu {
                    Button("Xuất", systemImage: "square.and.arrow.up") { exporting = true }
                    Button("Hướng dẫn mẫu", systemImage: "book") { showInstructions = true }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .sheet(isPresented: $exporting) { ExportSheet(recordId: model.record.id) }
        .sheet(isPresented: $showInstructions) { InstructionsSheet(template: model.template) }
        .onDisappear { model.flush() }
        .alert("Lỗi", isPresented: Binding(get: { model.saveError != nil }, set: { if !$0 { model.saveError = nil } })) {
            Button("OK") { model.saveError = nil }
        } message: { Text(model.saveError ?? "") }
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

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Label("Đã hoàn thành lúc \(model.record.completedAt.map { ISODate.format($0, "HH:mm dd/MM/yyyy") } ?? "")",
                  systemImage: "checkmark.circle.fill").foregroundStyle(.green)
            Button("Mở lại") { confirm = true }.buttonStyle(.bordered)
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
