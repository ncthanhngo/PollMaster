---
description: Phát hành bản mới — APK Android lên GitHub Release + build iOS lên TestFlight
---

Phát hành PollMaster cho cả 2 nền tảng từ code đã commit trên `main`.

1. **Chuẩn bị**: `git status` — còn thay đổi thì hỏi user commit (gợi ý message) trước; HEAD chưa push thì `git push origin main`.
   Không phát hành từ cây chưa commit: số build = số commit.
2. **Android** (chạy nền được): `Android/release-apk.sh` — test, build APK release đã ký, tạo GitHub Release `android-v<version>-<build>`
   kèm file APK. Báo lỗi "no keystore" → nhắc user chạy lệnh setup một lần ở đầu script (dạng `! …`), không tự tạo key.
3. **iOS**: `IOS/release-testflight.sh` — test, archive, ký, upload TestFlight (vài phút).
4. Hai bước 2 và 3 độc lập: một bên lỗi vẫn chạy bên kia, báo rõ bên nào lỗi kèm output.
5. Báo cáo ngắn: số build, **link tải APK** (để gửi đi), trang release, trạng thái upload TestFlight.
