import SwiftUI

/// ERP sign-in: the same email and password as the ERP web and mobile app (EVSELab staff and partner organisations such as Kimsen).
struct LoginView: View {
    @Environment(AppEnvironment.self) private var env
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @FocusState private var focus: Field?

    private enum Field { case email, password }

    private var canSubmit: Bool { email.contains("@") && !password.isEmpty && !busy }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(spacing: 8) {
                        IconBadge(systemImage: "list.clipboard", size: 64)
                        Text("PollMaster").font(.title.bold())
                        Text("Đăng nhập bằng tài khoản ERP").font(.subheadline).secondaryText()
                    }
                    .frame(maxWidth: .infinity)
                    .listRowBackground(Color.clear)
                }
                Section {
                    TextField("Email", text: $email)
                        .keyboardType(.emailAddress).textContentType(.username)
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .focused($focus, equals: .email).submitLabel(.next)
                        .onSubmit { focus = .password }
                    SecureField("Mật khẩu", text: $password)
                        .textContentType(.password)
                        .focused($focus, equals: .password).submitLabel(.go)
                        .onSubmit { if canSubmit { submit() } }
                } footer: {
                    if let error { Text(error).foregroundStyle(.red) }
                    else { Text("Tài khoản của EVSELab và các tổ chức đối tác (vd. Kimsen) đều đăng nhập được. Cần mạng cho lần đăng nhập; sau đó dùng được khi mất mạng.") }
                }
                Section {
                    Button(action: submit) {
                        HStack { Spacer(); if busy { ProgressView() } else { Text("Đăng nhập").bold() }; Spacer() }
                    }
                    .disabled(!canSubmit)
                } footer: {
                    if env.localDataResetPending {
                        Text("Phiếu và dự án đang có trên máy sẽ được sao lưu rồi xóa khi đăng nhập (mẫu được giữ lại). Dự án lấy từ ERP.")
                    }
                }
            }
            .onAppear { focus = .email }
            .modifier(WipeDialogs())
        }
    }

    private func submit() {
        busy = true; error = nil
        Task {
            do { try await env.login(email: email, password: password) }
            catch is CancellationError {}       // declined the wipe: back to the form
            catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}

/// The two dialogs of the first-sign-in wipe (answers go to `AppEnvironment.answerWipe`).
private struct WipeDialogs: ViewModifier {
    @Environment(AppEnvironment.self) private var env

    private func shown(_ match: @escaping (WipePrompt) -> Bool) -> Binding<Bool> {
        Binding(get: { env.wipePrompt.map(match) ?? false }, set: { _ in })
    }

    func body(content: Content) -> some View {
        content
            .alert("Xóa dữ liệu cũ trên máy?", isPresented: shown { if case .confirm = $0 { true } else { false } }, presenting: env.wipePrompt) { _ in
                Button("Sao lưu rồi xóa", role: .destructive) { env.answerWipe(.proceed) }
                Button("Hủy", role: .cancel) { env.answerWipe(.cancel) }
            } message: { p in
                if case .confirm(let records, let projects) = p {
                    Text("Có \(records) phiếu và \(projects) dự án tạo trước khi dùng ERP. App sẽ sao lưu toàn bộ phiếu (Tệp > PollMaster > PollMaster Backup) rồi xóa; mẫu được giữ lại.")
                }
            }
            .alert("Sao lưu chưa xong", isPresented: shown { if case .backupFailed = $0 { true } else { false } }, presenting: env.wipePrompt) { _ in
                Button("Thử lại") { env.answerWipe(.retry) }
                Button("Xóa không sao lưu", role: .destructive) { env.answerWipe(.proceed) }
                Button("Hủy", role: .cancel) { env.answerWipe(.cancel) }
            } message: { p in
                if case .backupFailed(let failed, let total) = p { Text("Không sao lưu được \(failed) trong \(total) phiếu. Xóa ngay thì các phiếu này sẽ mất.") }
            }
    }
}
