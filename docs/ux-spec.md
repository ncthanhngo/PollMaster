# PollMaster — UX chung iOS & Android

Hai app phải có **cùng luồng màn hình, cùng nhãn tiếng Việt, cùng hành vi**. Khác biệt chỉ ở
control native (SwiftUI vs Material 3). Hỗ trợ light/dark, Dynamic Type / font scale, điện thoại và tablet.

## Điều hướng

Tab gốc (iOS TabView / Android NavigationBar): **Phiếu khảo sát** · **Mẫu** · **Cài đặt**.

### 1. Mẫu (Templates)
- Danh sách mẫu: tiêu đề, id, version, số trường. Mẫu đóng gói sẵn (`shared/templates`) tự cài lần đầu.
- Nút **+**: "Nhập từ tệp" (document picker / SAF, `.json`) · "Nhập từ URL". Lỗi parse → thông báo dòng/đường dẫn lỗi.
  Trùng id+version → hỏi "Ghi đè?".
- Chạm mẫu → chi tiết: hướng dẫn (`instructions`), danh sách section, nút **Tạo phiếu mới**.
- Xóa mẫu: chỉ khi không còn phiếu dùng mẫu đó (nếu có, báo số phiếu).

### 2. Phiếu khảo sát (Records)
- Danh sách mọi phiếu, mới nhất trước. Mỗi dòng: `recordTitle`, tên mẫu, cập nhật lúc, chip trạng thái
  **Nháp** / **Hoàn thành**, thanh tiến độ bắt buộc (đã điền / tổng).
- Tìm kiếm theo tiêu đề; lọc theo trạng thái.
- Nút **+** → chọn mẫu → tạo phiếu (áp `default`, bắt đầu `sessionTime`).
- Vuốt/menu: **Nhân bản** (copy values, bỏ media, status nháp), **Xuất**, **Xóa** (xác nhận).

### 3. Soạn phiếu (Record editor)
- Thanh trên: tiêu đề phiếu, tiến độ `x/y bắt buộc`, nút **Kiểm tra** (→ màn 4), menu: Xuất, Hướng dẫn mẫu.
- Màn đầu là **mục lục section**: Thông tin đầu biên bản (header) + A..J, mỗi dòng có số lỗi bắt buộc còn thiếu.
  Chạm vào → màn section; trong màn section có nút "Mục trước / Mục sau".
- **Thẻ field**: badge mã (`A-01`) + nhãn + `*` đỏ nếu bắt buộc; dòng nhỏ xám `method`; ô nhập theo type có
  hậu tố `unit`; menu `⋯` của field: "Ước lượng (~)", "Không đo được (KĐĐ)", "Thêm ghi chú", "Xóa giá trị".
  Có cờ → badge `~` / `KĐĐ` cạnh mã; KĐĐ hiện ô lý do bắt buộc và làm mờ ô nhập.
  Ghi chú hiện dưới thẻ khi có. Lỗi validate hiện chữ đỏ dưới thẻ (chỉ sau khi người dùng đã bấm Kiểm tra
  hoặc rời field).
- `samples`: hàng ngang "Lần 1 · Lần 2 · Lần 3".
- `parts`: các ô phụ cùng hàng (xuống dòng khi hẹp), mỗi ô có nhãn nhỏ + đơn vị.
- `photo`/`video`/`attachPhoto`: lưới thumbnail + nút "Chụp" / "Thư viện"; chạm thumbnail để xem toàn màn,
  xóa. Ảnh lưu JPEG chất lượng ~0.85, cạnh dài tối đa 2560 px, đặt tên theo `mediaNamePattern`; gắn GPS nếu có quyền.
- `gps`: nút "Lấy vị trí hiện tại" (hiển thị độ chính xác), cho sửa tay; nút mở bản đồ.
- `sessionTime`: hiển thị bắt đầu / kết thúc, cho sửa.
- `computed`: ô xám readonly, cập nhật tức thì, có biểu tượng ƒx; chạm hiện công thức.
- **Bảng**: danh sách thẻ dòng ("Lượt 1", tóm tắt 2–3 cột đầu có giá trị), nút **Thêm dòng**, menu dòng:
  Nhân bản / Xóa. Chạm dòng → form dòng (cùng thẻ field). Bảng dòng cố định: không thêm/xóa, thẻ hiển thị cột readonly.
- **Stopwatch** (F1): trong form dòng, khối đồng hồ lớn mm:ss, nút **Bắt đầu (T0)** → **Ghi mốc T1…T8** (nhãn nút đổi
  theo mốc kế tiếp) + **Hoàn tác mốc**. Giữ màn hình sáng khi đồng hồ chạy.
- Autosave liên tục; không có nút Lưu. Bàn phím có nút "Tiếp" sang ô kế tiếp.

### 4. Kiểm tra & hoàn thành
- Danh sách lỗi nhóm theo section (mã + nhãn + lý do); chạm → nhảy tới field.
- Khu chữ ký theo `signatures`: tên người ký + khung ký tay (canvas, nút Xóa).
- Nút **Hoàn thành** (tắt khi còn lỗi). Phiếu đã hoàn thành: banner "Đã hoàn thành lúc …" + nút **Mở lại**.

### 5. Xuất
- Sheet chọn định dạng (checkbox): JSON + media (.zip) · Excel (.xlsx) · CSV · PDF · Word (.docx).
  Nhớ lựa chọn lần trước. Bấm **Xuất** → tiến độ → share sheet (nhiều file cùng lúc).

### Cài đặt
- Tên người khảo sát mặc định (điền vào field `HD-surveyor` nếu template có field cùng id) — lưu cục bộ.
- Chất lượng ảnh (Cao/Trung bình), dung lượng đã dùng, phiên bản app.

## Quyền
Camera, Thư viện ảnh, Vị trí khi dùng app, Microphone (quay video). Chuỗi mô tả quyền tiếng Việt.
