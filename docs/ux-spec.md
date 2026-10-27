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

---

# Bản sửa sau review hiện trường + UI/UX (bắt buộc cả 2 app, ưu tiên hơn phần trên nếu mâu thuẫn)

## A. Dữ liệu an toàn
- **Sao lưu tự động**: ghi file zip "JSON + media" (định dạng §5.1) của phiếu vào thư mục người dùng thấy được:
  iOS `Documents/PollMaster Backup/` (bật `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace` để hiện trong app Tệp);
  Android `Download/PollMaster/` qua MediaStore. Tên `{base}_backup.zip` (ghi đè bản cũ của cùng phiếu).
  Khi nào: rời một mục mà có thay đổi, mỗi 15 phút khi đang soạn và có thay đổi, khi Hoàn thành, khi app vào nền. Chạy nền, không chặn UI.
  Lưu `record.backupAt`. Cài đặt: công tắc "Tự động sao lưu" (mặc định bật), hiển thị thư mục, nút "Sao lưu tất cả ngay".
- Danh sách phiếu: badge vàng **"Chưa sao lưu"** khi `updatedAt > backupAt`.
- Thanh trên màn soạn: chữ nhỏ **"Đã lưu HH:mm"** (cập nhật sau mỗi autosave).

## B. Danh sách phiếu
- Tiêu đề = `recordTitle` (BB-TDP-01: `{A-01} · {A-03}`), rỗng → "Phiếu mới · dd/MM/yyyy HH:mm".
- Dòng phụ = `{template.id} · {mã biên bản nếu có}` (không lặp tiêu đề mẫu). Dòng 3: "Cập nhật dd/MM HH:mm" + tiến độ "x/y".
- Thumbnail 48×48 bên trái = ảnh đầu tiên của field photo đầu tiên có ảnh; không có → icon mẫu.
- Chip trạng thái: Nháp (xám) · Hoàn thành (xanh) · **Cần sửa** (cam, khi `returned`) · Lỗi đọc (đỏ) · Chưa sao lưu (vàng).

## C. Màn soạn phiếu
- **iOS ẩn tab bar** trong toàn bộ luồng soạn phiếu (`.toolbar(.hidden, for: .tabBar)`), giống Android.
- Tiêu đề thanh trên = tiêu đề phiếu ngắn (1 dòng, cắt cuối); dòng phụ "x/y bắt buộc · Đã lưu HH:mm".
- Nút **Kiểm tra** là nút phụ (iOS: nút chữ thường; Android: TextButton/OutlinedButton), có số lỗi khi đã từng kiểm tra.
- Mục lục: mỗi mục hiện **tiến độ trung tính** "đã điền/bắt buộc" màu xám → đủ thì "✓" xanh. **Không dùng màu đỏ trước khi người dùng
  bấm Kiểm tra lần đầu** (lưu cờ này theo phiếu trong phiên). Sau khi kiểm tra: mục còn thiếu hiển thị số đỏ.
- Đầu mục lục: nút **"Tiếp tục: {fieldId} {label}"** nhảy tới `lastPosition` (cập nhật khi focus/chạm field).
- Điều hướng mục: thanh **ghim cuối màn** "‹ Mục trước · Kiểm tra n/N · Mục sau ›" (không phải cuộn hết mục).
- Menu mục (⋯ trên thanh): **"Đánh dấu KĐĐ các trường còn trống"** (nhập 1 lý do chung, áp cho mọi field có `flags` còn trống trong mục,
  có xác nhận số lượng) · **"Lấy dữ liệu mục này từ phiếu khác"** (chọn phiếu cùng mẫu → chép values + tables của mục, không chép media, hỏi ghi đè).

## D. Thẻ field (thiết kế chung)
- **Form kiểu nhóm (phẳng)**: các field liên tiếp dưới một heading nằm chung **một khối trắng** (dark: `secondarySystemGroupedBackground`),
  ngăn bằng đường mảnh; heading là tiêu đề nhóm (title3 đậm) phía trên khối. Không viền (chỉ có viền khi Tương phản cao); field lỗi /
  có ghi chú kiểm tra: vạch màu 3 pt bên trái + nền rất nhạt. Bảng đứng riêng ngoài khối.
- Quy chuẩn iOS: bo góc chỉ 8 / 12 / 16 (`PMRadius`), khoảng cách 4 / 8 / 16 / 24 (`PMSpace`), màu chỉ lấy từ theme
  (`alert`, `warning`, `attention`, `success`, `secondaryText`).
- Hàng tiêu đề: nhãn **17 pt đậm** · `*` đỏ **ngay sau nhãn** · menu ⋯ màu xám trung tính.
- **Một dòng phụ** dưới nhãn: badge mã (nhỏ, nhạt) · `method` · khoảng thường gặp, chữ footnote màu secondary **đủ tương phản** (≥ 4.5:1).
- Nút phụ (Đọc chữ từ ảnh, Chụp, Chọn từ danh sách, điều hướng mục): nền màu nhấn nhạt + chữ màu nhấn, không dùng nền xám.
- **Chế độ gọn** (mặc định bật, tắt trong Cài đặt): field number/integer/text ngắn không có parts → nhãn và ô nhập cùng một hàng
  (ô rộng cố định ~132 pt, đơn vị ở đuôi ô; dòng phụ chạy hết chiều ngang bên dưới). Mục tiêu ≥ 6 field/màn điện thoại 6".
- **Parts**: lưới 2 cột (3 cột nếu ≥ 3 part và màn ≥ 600 dp), nhãn part = tên (không kèm đơn vị), đơn vị ở đuôi ô.
- Ô trống trông trống: placeholder là chữ hướng dẫn ("Nhập…", "Chọn tháng", "Chưa lấy vị trí"), **không bao giờ** hiển thị số 0 giả
  hoặc ngày mặc định khi chưa có giá trị (GPS, month, date, time, datetime).
- Field có `flags`: "Ước lượng (~)" và "Không đo được (KĐĐ)" chỉ nằm trong menu ⋯ (vùng chạm ≥ 44); khi bật hiện badge "~ Ước lượng" / "KĐĐ" sau nhãn.
- Field số có `warnMin/warnMax` (hoặc `min/max`): dòng phụ "Thường a–b đơn vị" dưới nhãn.
- singleChoice/yesNo: chip **chọn = tô đặc màu chính + ✓**, chưa chọn = viền. singleChoice > 6 lựa chọn mà nhãn ngắn (≤ 4 ký tự) →
  **lưới nút** (5 cột) thay vì dropdown; còn lại > 6 lựa chọn → dropdown/menu.
- Bàn phím số: số thập phân dùng bàn phím có dấu "." (iOS `.decimalPad`; Android `KeyboardType.Decimal`), cho nhập số âm chỉ khi `min` < 0
  hoặc `warnMin` < 0 (Android `NumberSigned` / iOS nút ± trên toolbar bàn phím).
- `photoRef`: dưới ô nhập, hàng thumbnail ảnh của field tham chiếu + nút "📷 Chụp P08".
- Cảnh báo (warn) màu vàng/cam dưới ô; lỗi màu đỏ.
- `review` (ghi chú kiểm tra) hiển thị khung cam "Kiểm tra: …" trên đầu thẻ.

## E. Bảng
- Bảng trống: chữ "Chưa có dòng nào — bấm Thêm dòng" + nút.
- Field computed của mục đặt dưới bảng trong khối **"Kết quả"** (tiêu đề nhỏ), không xen giữa nút và danh sách dòng.
- `pairedWith` + `quickEntry` (E1↔F1): nút "Ghi lượt mới" mở form **Ghi lượt**: trên cùng đồng hồ T0…T8 (to, nút ghi mốc lớn ≥ 56 pt),
  dưới là lưới nút một chạm cho các cột chọn (Mã KB dạng lưới 5×3, O/T, Giới tính, Chiều cao, Nhóm, Tay, Kết quả), rồi số lần đút,
  hàng khoang, ghi chú, nút "🎥 Quay video lượt này". Cột `carryOver` được điền sẵn từ lượt trước. Nút "Lượt tiếp theo" lưu và mở lượt mới.
- Danh sách dòng của bảng ghép đôi hiển thị ở cả 2 mục E và F (cùng dữ liệu).

## F. Media & âm thanh
- Field `audio`: nút tròn ghi âm (đỏ khi đang ghi, hiển thị thời gian), danh sách bản ghi có phát/xóa. Cần quyền micro (Android `RECORD_AUDIO`).
- Field photo được tham chiếu hiển thị "Dùng cho: B-01, A-07, B-20, B-21".

## G. Kiểm tra & người kiểm tra
- Màn Kiểm tra có 3 nhóm: **Lỗi** (đỏ, chặn Hoàn thành) · **Cần xem lại** (cảnh báo, vàng) · **Ghi chú kiểm tra** (cam).
- Phiếu đã hoàn thành: menu "Chế độ kiểm tra" → xem chỉ đọc, chạm thẻ để thêm/sửa ghi chú kiểm tra (`review`), nút **"Trả lại để sửa"**
  (status draft + `returned`). Người khảo sát sửa xong có thể xóa ghi chú kiểm tra (nút "Đã xử lý" trên khung cam).

## H. Danh sách điểm & GPS
- Cài đặt → "Danh sách điểm": nhập CSV, xem số điểm, xóa.
- Field có `lookup`: ô nhập + nút "Chọn từ danh sách" (sheet tìm kiếm theo mã/địa chỉ).
- GPS: hiển thị ±m với màu (xanh ≤ maxAccuracy, cam nếu kém), nút "Lấy lại"; nếu có điểm kế hoạch thì "Cách điểm kế hoạch N m".

## I. Hiển thị ngoài trời
- Cài đặt → **"Tương phản cao"** (mặc định tắt): nền trắng tinh/đen tinh, chữ phụ cùng màu chữ chính, viền 2 px, cỡ chữ +10 %, chip chọn
  tương phản tối đa.
- Màu chữ phụ mặc định phải đạt WCAG AA trên nền thẻ ở cả light/dark. iOS dark: ô nhập có nền phân biệt với thẻ (không đen tuyền trên xám).

## J. Quy ước đã chốt khi triển khai bản sửa
- Tên file sao lưu: `{recordCode đã làm sạch}_{8 ký tự đầu record id}_backup.zip`; không có mã → `{recordId}_backup.zip`.
- Ghi chú kiểm tra (`review`) chỉ cho field header/mục, không cho ô trong bảng.
- Chế độ gọn: hàng gọn dùng cùng hàng tiêu đề với thẻ thường (badge mã đứng trước nhãn), `method` tối đa 2 dòng.
- Mẫu nhiều phiên bản: tab Mẫu vẫn liệt kê bản cũ (ghi chú "Bản cũ", tắt "Tạo phiếu mới"); hộp chọn mẫu khi tạo phiếu chỉ có bản mới nhất.
- "Lấy dữ liệu mục từ phiếu khác": chỉ chép giá trị không rỗng, chép kèm dòng của bảng ghép đôi để giữ id đồng bộ.

## K. Giao diện Sáng / Tối
- **Mặc định: Sáng**, không theo hệ thống. Cài đặt → "Giao diện": **Sáng** (mặc định) · **Tối** · **Theo hệ thống**. Lưu cục bộ,
  áp dụng ngay (không khởi động lại), gồm cả các sheet/dialog.
- **Chuyển nhanh 1 chạm**: nút biểu tượng ☀ / ☾ (sun.max / moon; Material LightMode / DarkMode) ở thanh trên của 3 màn gốc
  (Phiếu khảo sát, Mẫu, Cài đặt; iOS đặt bên trái, xa nút +) — chạm để đảo Sáng ↔ Tối (nếu đang "Theo hệ thống" thì đảo so với giao diện đang hiển thị và lưu thành
  Sáng/Tối cụ thể). Trong màn soạn phiếu: mục "Giao diện tối" / "Giao diện sáng" trong menu ⋯.
- "Tương phản cao" vẫn là công tắc riêng, áp lên cả Sáng và Tối.
- Android: **tắt dynamic color** (Material You theo hình nền) — dùng bảng màu thương hiệu cố định cho cả Sáng và Tối để 2 app đồng nhất.
- iOS: `AccentColor` = màu chính thương hiệu `#1B6B5A`; tối `#34A88E` (đậm hơn Android `#6FD6BE` vì nút iOS luôn chữ trắng).

## L. Bản sửa sau review 3 (hiện trường + UI/UX)
1. **Stopwatch ghim**: trong form Ghi lượt / form dòng có stopwatch, khối đồng hồ dính đầu màn; khi cuộn thu thành dải mảnh
   `mm:ss · [Ghi T4] · ↶`. Rung nhẹ (haptic) mỗi lần ghi mốc. `cues` (schema §8) hiện banner nhắc.
2. **Hint**: luôn hiện dưới nhãn (1 lần), **không** làm placeholder trong ô hẹp (samples, parts, hàng gọn). Placeholder ô hẹp = "Nhập…".
3. **Hàng gọn** chỉ cho number/integer (không parts, không samples) và text có `short`. Text khác luôn ô rộng.
4. Chữ báo bảng trống dùng đúng tên nút: "Chưa có lượt nào — bấm Ghi lượt mới" (bảng quickEntry, theo `rowLabel` viết thường) /
   "Chưa có dòng nào — bấm Thêm dòng".
5. **roundEntry** (schema §8) cho G2.
6. **Bảng `inline`** (schema §8) cho E2.
7. `stepper`, `chipsRange` (schema §8).
8. **Kiểm tra & hoàn thành**: đầu màn "Còn N mục bắt buộc" + nút lớn **"Điền lần lượt"**; danh sách gom theo mục, mỗi mục thu gọn được; **"Còn thiếu"** (chưa điền / thiếu ảnh / thiếu dòng) màu trung tính, **"Lỗi"** đỏ chỉ cho giá trị sai (ngoài khoảng, thiếu lý do KĐĐ)
   (mặc định mở mục đầu). Dưới nút Hoàn thành khi bị tắt: "Còn N mục bắt buộc". **Chế độ Điền lần lượt**: màn chỉ hiện 1 field thiếu
   (thẻ field đầy đủ, hàng gọn tắt), thanh trên "k / N · tên mục", nút **Tiếp** (lưu, sang field thiếu kế tiếp theo thứ tự mẫu),
   **Bỏ qua**, **KĐĐ** (nhập lý do), **Thoát**. Bảng/media thiếu → nút "Mở" đưa tới đúng chỗ rồi quay lại.
9. **Tạo phiếu**: chỉ có 1 mẫu dùng được → tạo ngay, không hỏi. Mã phiếu tự đề xuất (schema §8). Chưa có tên người khảo sát trong
   Cài đặt → hỏi 1 lần (dialog nhập tên, lưu Cài đặt) rồi điền `HD-surveyor`.
10. **GPS**: nút lớn "Lấy vị trí hiện tại" ở trên; hiển thị kết quả `lat, lng ±m`; ô lat/long ẩn sau nút "Nhập tay".
11. **sessionTime**: một dòng `18:41 → …` + nút ✎ (mở sheet sửa bắt đầu/kết thúc).
12. **Parts**: ≥ 3 part number/integer mà tất cả ngắn → 1 hàng 3 cột trên điện thoại (L × W × H).
13. **Lookup khi chưa có danh sách điểm**: nút đổi thành "Nạp danh sách điểm (CSV)" → mở thẳng bộ chọn tệp CSV (nạp xong mở sheet chọn).
14. **Mục lục**: mọi mục có dòng phụ thống kê: bắt buộc `x/y` (nếu có) · số lượt/dòng của bảng lặp (`3 lượt`, `2 người`) ·
    ảnh/video `12/15` (field media có ≥ 1 tệp / tổng field media). Biểu tượng 📷 khi còn field ảnh `required`/`minCount` chưa đủ.
15. **OCR nhãn**: field `text`/`multiline` có `photoRef` → nút "Đọc chữ từ ảnh": nhận dạng chữ (on-device, tiếng Việt + Latin) trên
    các ảnh của field tham chiếu, hiện danh sách dòng chữ; chạm dòng → điền (text: thay; multiline: nối thêm). Không có ảnh → mở camera của field tham chiếu trước.
16. **Đọc giọng nói**: field `multiline` (kể cả cột bảng) có nút 🎤: nói → chữ (ưu tiên on-device, ngôn ngữ vi-VN), nối vào cuối.
    Quyền micro + nhận dạng giọng nói; không có dịch vụ → ẩn nút.
17. **Chế độ một tay / đeo găng** (Cài đặt, mặc định tắt): vùng chạm tối thiểu 56, chữ +10 %, thanh hành động dưới cùng trong màn soạn
    (`‹ Mục trước · Kiểm tra · Mục sau ›`), FAB và nút chính ở nửa dưới màn; menu ⋯ của field thay bằng nhấn giữ thẻ.
18. **Bản đồ phiếu**: tab Phiếu khảo sát có nút chuyển **Danh sách / Bản đồ**. Bản đồ hiện mỗi phiếu có GPS (field `gps` đầu tiên có giá trị)
    là một ghim màu theo trạng thái (Nháp xám, Hoàn thành xanh, Cần sửa cam) + điểm trong danh sách điểm chưa có phiếu (ghim rỗng).
    Chạm ghim → thẻ tóm tắt → mở phiếu. iOS: MapKit. Android: osmdroid (OpenStreetMap), cần mạng để tải nền bản đồ; offline vẫn hiện ghim.

## M. Dự án
- **Tab**: `Phiếu khảo sát · Dự án · Mẫu · Cài đặt`.
- **Dự án hiện tại**: tiêu đề tab Phiếu khảo sát = tên dự án (chấm màu dự án) + ▾. Chạm → sheet chọn dự án (tìm kiếm, mỗi dòng: tên, mã,
  số phiếu Nháp/Hoàn thành), mục "Tất cả dự án", hai nhóm "Dự án công ty" / "Nội bộ", nút "Nhập gói dự án". Lựa chọn được nhớ. Danh sách phiếu, bản đồ, nút +
  đều theo dự án hiện tại (nút + hỏi dự án khi đang xem "Tất cả dự án"). **Mỗi phiếu luôn hiện avatar + tên dự án** (danh sách và đầu màn mục lục).
  Thẻ phiếu 3 dòng không xuống dòng: tiêu đề · avatar + tên dự án (không đủ chỗ → mã dự án) · ngày · trạng thái + ☁̸ chưa sao lưu + tiến độ.
- **Đăng nhập ERP** (bắt buộc, schema §9.5): email + mật khẩu ERP; tài khoản EVSELab và tổ chức đối tác (Kimsen…). Lần đầu cảnh báo
  dự án/phiếu cũ trên máy sẽ bị xóa (mẫu giữ). Cài đặt: mục "Tài khoản ERP" (tên, email · tổ chức) + Đăng xuất (hỏi lại; không xóa phiếu).
- **Tab Dự án**: dự án ERP của tài khoản, chia **Dự án công ty (N)** rồi **Nội bộ (N)** như ERP; kéo xuống để tải lại; lỗi mạng → dòng báo,
  vẫn hiện danh sách đã lưu. Mỗi thẻ 3 dòng: avatar · tên · "mã · khách hàng · N mẫu · N điểm" (bớt phần cuối khi không đủ chỗ) ·
  số phiếu nháp / cần sửa / hoàn thành · ngày gần nhất. Nút "Nhập gói dự án". **Không tạo/sửa/xóa/lưu trữ dự án** trong app.
- **Ảnh đại diện**: ảnh ERP (`avatar.jpg`, không đi kèm gói); không có → chữ cái đầu trên nền màu dự án (máy tự gán, 10 màu).
- **Chi tiết dự án**: thống kê (Nháp/Hoàn thành/Cần sửa, phiếu gần nhất) · **Mẫu của dự án** (thêm từ thư viện, gỡ, gợi ý bản mới) ·
  **Danh sách điểm** (nạp CSV, số điểm, xem/tìm, xóa; số điểm đã có phiếu / chưa) · Hành động: **Chia sẻ gói dự án**, **Xuất tổng hợp
  (Excel)** (+ tùy chọn kèm zip phiếu, lọc Hoàn thành), **Mở danh sách phiếu**. Đầu màn: loại (Dự án công ty / Nội bộ), khách hàng.
- **Chuyển phiếu sang dự án khác**: menu phiếu → "Chuyển dự án" (chỉ tới dự án có cùng mẫu; nếu không có → báo).
- **Mẫu (thư viện)**: trên cùng là dải "Tạo mẫu bằng AI · Bắt đầu ›" cao 1 hàng (chạm → sheet 2 bước Gửi / Dán); dưới là danh sách mẫu,
  mỗi mẫu đúng 2 dòng không xuống dòng: tên mẫu · "BB-TDP-01 · v1.2 · 180 trường" + avatar các dự án đang dùng; sau khi nhập/dán mẫu mới → hỏi "Thêm vào dự án …?" (chọn nhiều).
- Settings "Danh sách điểm" chuyển vào chi tiết dự án (Cài đặt bỏ mục này).
- Mở tệp `.pmproject` từ app khác → mở app, hiện xem trước (tên, mã, số mẫu, số điểm) + **chọn dự án ERP để nhập vào** + nút Nhập.
