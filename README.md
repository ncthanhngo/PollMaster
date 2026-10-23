# PollMaster

Ứng dụng khảo sát hiện trường **offline**, dùng chung cho nhiều loại biên bản. Mỗi loại survey
là một **mẫu JSON** (template) — app tự dựng form từ mẫu, lưu phiếu trên máy và xuất
JSON+media (.zip), Excel/CSV, PDF, Word.

Hai app native, cùng trải nghiệm:

| Thư mục | Nền tảng | Công nghệ |
|---|---|---|
| `IOS/` | iOS 17+ | Swift, SwiftUI, XcodeGen |
| `Android/` | Android 8.0+ (API 26) | Kotlin, Jetpack Compose |

## Cấu trúc

```
docs/template-schema.md        # HỢP ĐỒNG CHUNG: template, record, công thức, xuất file
shared/templates/*.json        # Mẫu đi kèm app (BB-TDP-01 – khảo sát tủ đổi pin)
shared/formula-test-vectors.json  # Test vector công thức — cả 2 app phải pass
shared/huong-dan-tao-mau-bang-ai.md  # File hướng dẫn cho AI tạo mẫu (đóng gói trong app)
IOS/                           # xem IOS/README.md
Android/                       # xem Android/README.md
```

## Thêm loại survey mới

**Cách nhanh (dùng AI):** trong app, tab **Mẫu → Tạo mẫu bằng AI → Chia sẻ file hướng dẫn**
(`shared/huong-dan-tao-mau-bang-ai.md`), gửi file đó cùng mô tả khảo sát cho Claude/ChatGPT, rồi **Dán JSON** kết quả vào app.
Nếu app báo lỗi → **Sao chép lỗi**, dán lại cho AI sửa.

**Cách thủ công:**

1. Viết file JSON theo `docs/template-schema.md` (tham khảo `shared/templates/bb-tdp-01.json`).
2. Import trong app (Mẫu khảo sát → Nhập từ file / URL), hoặc đặt vào `shared/templates/` để đóng gói sẵn.
