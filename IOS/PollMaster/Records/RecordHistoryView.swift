import SwiftUI
import PollMasterCore

/// "Lịch sử sửa" of a record (ux-spec N): the ERP's change log, newest save first, one line per changed field.
struct RecordHistoryView: View {
    let recordId: String
    @Environment(AppEnvironment.self) private var env
    @State private var state = Load.loading

    private enum Load { case loading, failed(String), loaded([RecordChange]) }

    /// Read once when the screen opens (labels and row positions come from the record as it is now).
    @State private var record: Record?
    @State private var template: Template?

    var body: some View {
        Group {
            switch state {
            case .loading: ProgressView("Đang tải lịch sử…")
            case .failed(let message):
                ContentUnavailableView {
                    Label("Không tải được lịch sử.", systemImage: "wifi.exclamationmark")
                } description: { Text(message) } actions: { Button("Thử lại") { Task { await load() } } }
            case .loaded(let log) where log.isEmpty: ContentUnavailableView("Chưa có thay đổi nào", systemImage: "clock")
            case .loaded(let log): list(log)
            }
        }
        .navigationTitle("Lịch sử sửa")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .task { await load() }
    }

    private func list(_ log: [RecordChange]) -> some View {
        List {
            ForEach(log) { entry in
                Section {
                    if entry.created {
                        Text("Tạo phiếu").font(.subheadline)
                    } else {
                        ForEach(Array(entry.changes.enumerated()), id: \.offset) { _, c in line(c) }
                    }
                } header: { Text(header(entry)).textCase(nil) }
            }
        }
    }

    private func line(_ c: FieldChange) -> some View {
        let label = ChangeLabels.label(path: c.path, serverLabel: c.label, template: template, record: record)
        let old = ChangeLabels.valueText(path: c.path, c.old, template: template)
        let new = ChangeLabels.valueText(path: c.path, c.new, template: template)
        return VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.subheadline.weight(.medium))
            Text("\(old) → \(new)").font(.footnote).secondaryText()
        }
        .accessibilityElement(children: .combine)
    }

    /// "{tên} · dd-MM-yyyy HH:mm" (+ " · app cũ" for a save made by the old manual upload).
    private func header(_ e: RecordChange) -> String {
        let when = ISODate.parse(e.changedAt).map { ISODate.format($0, "dd-MM-yyyy HH:mm") } ?? e.changedAt
        return [e.changedByName.isEmpty ? "Không rõ" : e.changedByName, when, e.source == "legacy" ? "app cũ" : nil]
            .compactMap { $0 }.joined(separator: " · ")
    }

    private func load() async {
        let r = env.recordStore.load(recordId)
        record = r
        template = r.flatMap { env.templateStore.load(id: $0.templateId, version: $0.templateVersion) }
        guard let r, let pid = r.projectId else { state = .failed("Không tìm thấy phiếu trên máy."); return }
        guard r.serverRev != nil else { state = .failed("Phiếu chưa có trên máy chủ nên chưa có lịch sử."); return }
        state = .loading
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "pmHistory") { state = .loaded(Self.sampleLog); return }
        #endif
        do { state = .loaded(try await ERPSyncRemote(client: env.erp).changes(projectId: pid, recordId: recordId)) }
        catch is CancellationError {}
        catch { state = .failed(error is URLError ? "Cần có mạng để xem lịch sử." : error.localizedDescription) }
    }

    #if DEBUG
    /// Launch aid `-pmHistory 1`: a log to look at without a server.
    private static let sampleLog = [
        RecordChange(rev: 3, changedByName: "Trần Thị B", changedAt: "2026-10-27T09:05:00Z", changes: [
            FieldChange(path: "values.B-02.v", old: .number(1500), new: .number(1520)),
            FieldChange(path: "values.A-05.v", old: .null, new: .string("gas_station")),
            FieldChange(path: "values.B-16.parts.overhang", old: .number(30), new: .null),
            FieldChange(path: "status", old: .string("draft"), new: .string("completed"))]),
        RecordChange(rev: 2, changedByName: "Nguyễn Văn A", changedAt: "2026-10-26T14:30:00Z", source: "legacy", changes: [
            FieldChange(path: "values.A-03.note", old: .null, new: .string("Cổng phụ"))]),
        RecordChange(rev: 1, changedByName: "Nguyễn Văn A", changedAt: "2026-10-26T08:00:00Z", created: true)]
    #endif
}
