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
                        Text("Dự án và phiếu đang có trên máy sẽ bị xóa khi đăng nhập (mẫu được giữ lại). Dự án lấy từ ERP.")
                    }
                }
            }
            .onAppear { focus = .email }
        }
    }

    private func submit() {
        busy = true; error = nil
        Task {
            do { try await env.login(email: email, password: password) } catch { self.error = error.localizedDescription }
            busy = false
        }
    }
}
