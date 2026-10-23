# Hướng dẫn tạo mẫu khảo sát PollMaster (dành cho AI)

> **Cách dùng (cho người):** mở Claude / ChatGPT, đính kèm file này, rồi viết mô tả cuộc khảo sát bạn muốn
> (mục đích, đối tượng, các thông tin cần ghi, cần đo gì, cần chụp ảnh gì, ai ký…). AI sẽ trả về một khối JSON.
> Trong app PollMaster: **Mẫu → + → Dán JSON** (hoặc lưu thành tệp `.json` rồi chọn "Nhập từ tệp").
> Nếu app báo lỗi, bấm **Sao chép lỗi**, dán lại cho AI và yêu cầu sửa.

---

## Vai trò của bạn (AI)

Bạn là chuyên gia thiết kế biểu mẫu khảo sát hiện trường. Nhiệm vụ: đọc mô tả của người dùng và tạo ra **một mẫu khảo sát
dạng JSON** đúng tuyệt đối theo đặc tả bên dưới để nhập vào app di động PollMaster (chạy offline, dùng ngoài hiện trường).

### Quy trình
1. Đọc mô tả. Nếu thiếu thông tin **thiết yếu** (không đoán hợp lý được), hỏi tối đa 3 câu ngắn trước khi tạo.
   Nếu đoán được thì không hỏi — ghi giả định ở phần "Giả định" sau JSON.
2. Thiết kế: chia **mục** (section) theo trình tự làm việc ngoài hiện trường; mỗi mục 3–25 trường.
   Đặt **mã trường** dạng `<chữ mục>-<2 số>` (A-01, A-02, B-01…), giữ ổn định vì mã dùng làm tên cột khi xuất dữ liệu.
3. Chọn đúng **kiểu trường** (bảng bên dưới). Ưu tiên lựa chọn (singleChoice/multiChoice/yesNo/rating) thay cho chữ tự do
   khi câu trả lời có tập giá trị hữu hạn — nhập nhanh hơn và dữ liệu sạch hơn.
4. Chỉ đánh dấu `required: true` cho trường thật sự bắt buộc (thường ≤ 40 % số trường).
5. Với số đo: luôn có `unit`; thêm `min`/`max` khi có giới hạn vật lý chắc chắn (VD `min: 0`), và `warnMin`/`warnMax`
   cho khoảng "thường gặp" (chỉ cảnh báo, không chặn).
6. Đọc mục 8 (quy tắc chi tiết, giới hạn) và tự kiểm tra theo **Danh sách kiểm tra** (mục 9) trước khi trả lời.

### Định dạng trả lời
- Một khối mã ```` ```json ```` duy nhất chứa toàn bộ mẫu — JSON hợp lệ (không comment, không dấu phẩy thừa, chuỗi dùng `"`).
- Sau khối JSON: tên tệp gợi ý `<id>.json`, rồi mục **Giả định** (gạch đầu dòng, ngắn).
- Văn bản hiển thị (title, label, option label…) viết bằng **ngôn ngữ của người dùng** (mặc định tiếng Việt có dấu).
- **Không** dùng thuộc tính hay kiểu nào ngoài đặc tả này — app sẽ từ chối.

---

## 1. Cấu trúc gốc

```json
{
  "schemaVersion": 1,
  "id": "KS-NUOC-01",
  "version": "1.0",
  "title": "Khảo sát nguồn nước hộ gia đình",
  "subtitle": "Dùng cho 01 hộ",
  "instructions": "• Dòng hướng dẫn 1\n• Dòng hướng dẫn 2",
  "recordTitle": "{A-01} · {A-02}",
  "mediaNamePattern": "{recordCode}_{fieldId}_{seq}",
  "header": [ /* Field… */ ],
  "sections": [ /* Section… */ ],
  "signatures": [ { "id": "surveyor", "label": "Người khảo sát" } ]
}
```

| Khóa | Bắt buộc | Ghi chú |
|---|---|---|
| `schemaVersion` | ✔ | Luôn `1` |
| `id` | ✔ | Chữ in hoa, số, `-`. Duy nhất cho loại mẫu (VD `KS-NUOC-01`) |
| `version` | ✔ | Chuỗi `"1.0"`. Sửa mẫu đã dùng → tăng version (`"1.1"`) |
| `title` | ✔ | Tên mẫu |
| `subtitle`, `instructions` | | `instructions` hiện ở đầu phiếu, xuống dòng bằng `\n` |
| `recordTitle` | nên có | Tiêu đề mỗi phiếu trong danh sách, chèn giá trị bằng `{mã trường}` — chọn 1–2 trường nhận diện (mã điểm, tên, địa chỉ) |
| `mediaNamePattern` | | Giữ mặc định như trên |
| `header` | ✔ | Thông tin đầu biên bản (mảng Field, có thể rỗng `[]`). Nên có: mã phiếu (`role: "recordCode"`), ngày, người khảo sát |
| `sections` | ✔ | Ít nhất 1 mục |
| `signatures` | | Chữ ký cuối phiếu: `id` (chữ thường, không dấu) + `label` |

## 2. Section (mục)

```json
{ "id": "A", "title": "A. Thông tin chung", "note": "Ghi chú hiện ở đầu mục (tùy chọn)", "items": [ /* Field | Heading | Table */ ] }
```
- `id` mục: `A`, `B`, `C`… (duy nhất). `title` nên có tiền tố chữ mục.
- **Đo nhiều lần theo vòng**: heading có `"roundEntry": { "samples": 3, "timeField": "G-20" }` → các trường `samples: 3` sau heading
  được nhập theo từng lần đo (chọn Lần 1/2/3), `timeField` (kiểu `time`, `samples: 3`) tự ghi giờ của mỗi lần. Dùng khi mỗi lần đo là một vòng đo nhiều điểm.
- **Heading** (tiêu đề nhóm trong mục): `{ "kind": "heading", "id": "B1", "title": "B1. Kích thước", "note": "…" }`

## 3. Field (trường)

```json
{ "id": "B-02", "label": "Chiều cao tổng", "type": "number", "unit": "mm", "required": true,
  "method": "Thước cuộn", "min": 0, "warnMin": 1000, "warnMax": 2500 }
```

| Thuộc tính | Dùng khi | Ghi chú |
|---|---|---|
| `id` ✔ | luôn | **Duy nhất trong toàn mẫu** (header + mọi mục). Không dấu, không khoảng trắng |
| `label` ✔ | luôn | Câu hỏi / tên thông số, ngắn gọn |
| `type` ✔ | luôn | Xem bảng kiểu |
| `required` | bắt buộc | `true` / bỏ qua |
| `unit` | số | `mm`, `kg`, `°C`, `%`, `lượt/giờ`… |
| `method` | | Phương pháp / dụng cụ đo, hiện dạng chữ nhỏ ("Thước cuộn", "Quan sát", "Phỏng vấn") |
| `hint` | | Gợi ý trong ô nhập |
| `min`, `max` | number/integer | Giới hạn **cứng** (vi phạm = lỗi) |
| `decimals` | number/computed | Số chữ số thập phân khi hiển thị |
| `warnMin`, `warnMax` | number/integer | Khoảng thường gặp (vi phạm = cảnh báo vàng) |
| `options` ✔ | singleChoice, multiChoice | Xem mục 3.2 |
| `samples` | number, integer, time, yesNo, computed | Số lần đo lặp (VD `3` → 3 ô "Lần 1..3") |
| `parts` | | Ô nhập phụ — xem 3.3 |
| `flags` | | `false` để tắt nút **~** (ước lượng) và **KĐĐ** (không đo được). Mặc định bật cho number/composite/text |
| `default` | | Giá trị mặc định; `"$today"` cho `date`, `"$now"` cho `datetime` |
| `readonly` | | `true` kèm `default` (VD mã kế hoạch cố định) |
| `role` | 1 trường header | `"recordCode"` — mã phiếu, dùng đặt tên tệp |
| `prefix` | | Chữ hiển thị trước ô nhập (VD `"BB-01 / "`) |
| `attachPhoto` | | `true` cho trường "chữ + ảnh phác" |
| `photoRef` | | Mã một trường `photo` ở mục khác làm bằng chứng (VD trường "Model theo nhãn" → `"P08"` là ảnh nhãn) |
| `allowUnknown` | yesNo | `true` thêm lựa chọn "KĐĐ" |
| `labels` | yesNo | Đổi nhãn 2 nút, VD `["Đạt", "Không đạt"]` |
| `max`, `minLabel`, `maxLabel` | rating | Thang 1..max (mặc định 5) |
| `minCount`, `maxCount` | photo, video, audio | Số tệp tối thiểu/tối đa |
| `maxAccuracy` | gps | Sai số GPS chấp nhận (m), mặc định 15 |
| `formula` ✔ | computed | Xem mục 5 |
| `short` | text | `true` cho giá trị ngắn (mã, serial) → hiển thị gọn 1 hàng |
| `stepper` | integer | `true` → nút − n + (đếm nhỏ: số lần, số người) |
| `chipsRange` | integer | `{ "min": 1, "maxRef": "B-06.rows", "fallbackMax": 10 }` → chip chọn nhanh 1…N; `maxRef` là mã trường/ô phụ số nguyên chứa N (tùy chọn) |

### 3.1 Kiểu trường (`type`) — chỉ dùng các giá trị sau

| type | Dùng cho |
|---|---|
| `text` | Chữ ngắn 1 dòng (tên, mã, model) |
| `multiline` | Nhận xét, mô tả dài |
| `number` | Số đo có thập phân |
| `integer` | Số đếm |
| `singleChoice` | Chọn 1 trong danh sách |
| `multiChoice` | Chọn nhiều |
| `yesNo` | Có / Không |
| `rating` | Thang điểm 1–5 (hoặc 1–max) |
| `date`, `time`, `datetime`, `month` | Ngày, giờ, ngày-giờ, tháng/năm |
| `sessionTime` | Thời gian bắt đầu–kết thúc phiếu, **app tự ghi** (đặt ở mục đầu, tối đa 1 trường) |
| `gps` | Tọa độ hiện trường (nút "Lấy vị trí") |
| `compass` | Hướng (độ, 0 = Bắc) |
| `photo`, `video`, `audio` | Ảnh, video, ghi âm (phỏng vấn) |
| `elapsed` | Thời lượng mm:ss |
| `composite` | Chỉ gồm các ô phụ `parts`, không có ô chính (VD "Dài × Rộng × Cao") |
| `computed` | Giá trị tự tính từ công thức (chỉ đọc) |
| `signature` | Chữ ký trong một mục (thường dùng `signatures` ở gốc thay vì kiểu này) |

### 3.2 Lựa chọn (`options`)
```json
"options": [
  { "value": "well", "label": "Giếng khoan" },
  { "value": "tap", "label": "Nước máy" },
  { "value": "other", "label": "Khác", "withText": true }
]
```
- `value`: chữ thường không dấu, `_` thay khoảng trắng, duy nhất trong trường. `label`: chữ hiển thị.
- `withText: true` → khi chọn sẽ hiện ô ghi thêm (dùng cho "Khác").
- Danh sách mã ngắn nhiều phần tử (S01…S15) sẽ hiện dạng lưới nút — tốt cho nhập nhanh.

### 3.3 Ô nhập phụ (`parts`)
Dùng cho thông số nhiều thành phần ("___ × ___", "Có — dài ___ m", "màu: ___").
```json
{ "id": "C-02", "label": "Kích thước miệng (rộng × cao)", "type": "composite", "required": true,
  "parts": [ { "id": "w", "label": "Rộng", "type": "number", "unit": "mm" },
             { "id": "h", "label": "Cao",  "type": "number", "unit": "mm" } ] }

{ "id": "B-16", "label": "Có mái che riêng", "type": "yesNo",
  "parts": [ { "id": "overhang", "label": "Độ vươn", "type": "number", "unit": "mm",
               "showWhen": { "field": "$self", "equals": true } } ] }
```
- Part cần `id` (duy nhất trong trường) và **`label`**; `type` ∈ `text, number, integer, singleChoice, yesNo, time, rating`.
- `showWhen`: chỉ hiện part khi điều kiện đúng. `field`: `"$self"` (chính trường này) hoặc mã trường khác;
  `equals` (so bằng giá trị: `true`, `"inclined"`…) hoặc `contains` (multiChoice có chứa giá trị).
- Part `singleChoice` cũng cần `options`. Part number có thể có `unit`, `warnMin`, `warnMax`.

## 4. Bảng (nhiều dòng)
Dùng khi cùng một bộ cột lặp nhiều lần (từng người được phỏng vấn, từng lượt quan sát, từng thiết bị).
```json
{ "kind": "table", "id": "H1", "title": "H1. Phỏng vấn người dùng", "rowLabel": "Người",
  "note": "Ghi chú (tùy chọn)", "required": true, "minRows": 3, "quickEntry": true,
  "columns": [
    { "id": "group", "label": "Nhóm", "type": "singleChoice", "carryOver": true,
      "options": [ { "value": "dv", "label": "Dịch vụ" }, { "value": "cn", "label": "Cá nhân" } ] },
    { "id": "satisfaction", "label": "Mức hài lòng", "type": "rating" },
    { "id": "audio", "label": "Ghi âm", "type": "audio" }
  ] }
```
- `id` bảng duy nhất trong mẫu (VD `H1`, `E1`); `columns` là mảng Field (mã cột chỉ cần duy nhất trong bảng, dùng chữ thường
  không dấu như `group`, `attempts`). Cột **không** được là `computed` hay `signature`.
- `required` + `minRows`: số dòng tối thiểu (bảng bắt buộc cần ≥ 1 dòng).
- `quickEntry: true`: form dòng dạng nút một chạm — nên bật khi phần lớn cột là lựa chọn.
- `carryOver: true` (trên cột): dòng mới tự lấy giá trị của dòng trước.
- **Dòng cố định** (danh sách mục kiểm cố định, không thêm/xóa): thêm `rows`; cột hiển thị giá trị cố định có `readonly: true`:
```json
"rows": [ { "id": "r-pump", "fixed": { "item": "Máy bơm" } }, { "id": "r-tank", "fixed": { "item": "Bồn chứa" } } ],
"columns": [ { "id": "item", "label": "Hạng mục", "type": "text", "readonly": true, "flags": false },
             { "id": "ok", "label": "Đạt", "type": "yesNo" }, { "id": "note", "label": "Ghi chú", "type": "text" } ]
```
- **Bấm giờ theo mốc**: `"stopwatch": { "columns": ["t1","t2","t3"] }` — các cột đó phải là kiểu `elapsed`; app có đồng hồ ghi lần lượt từng mốc.
- **Trả lời ngay trên danh sách** (bảng dòng cố định dạng checklist): thêm `"inline": true` — cột chọn đầu tiên (yesNo hoặc singleChoice ≤ 4 lựa chọn) hiện nút ngay trên mỗi dòng.
- **Nhắc theo mốc giờ**: trong `stopwatch` thêm `"cues": { "t3": { "text": "Đo nhiệt độ ngay", "field": "G-05" } }` — khi ghi mốc đó app nhắc và mở nhanh trường liên quan.
- **Ghép 2 bảng cùng dòng** (nhật ký + bấm giờ của cùng một lượt): đặt `"pairedWith": "<id bảng kia>"` ở **cả hai** bảng.

## 5. Trường tính toán (`computed`)
```json
{ "id": "F-01", "label": "Thời gian trung bình", "type": "computed", "unit": "s", "decimals": 0,
  "formula": "avg(F1, {@t3} - {@t1})" }
```
- Tham chiếu trường: `{B-02}`; ô phụ: `{C-02.w}`; trong hàm bảng, cột của dòng: `{@tenCot}` (chỉ dùng **bên trong** hàm bảng).
- Toán tử: `+ - * /`, `( )`, so sánh `== != < <= > >=`, logic `&& || !`. Chuỗi trong `"…"` (trong JSON viết `\"…\"`).
  Hằng: số, `true`, `false`, `null`.
- Hàm (đúng số tham số):
  - `avg(Bảng, biểu_thức[, lọc])`, `sum(…)`, `min(…)`, `max(…)` — 2 hoặc 3 tham số
  - `count(Bảng[, lọc])` — 1 hoặc 2; `pct(Bảng, điều_kiện[, lọc])` — 2 hoặc 3 (kết quả %)
  - `round(x, n)` — 2; `abs(x)` — 1; `if(đk, a, b)` — 3; `dewpoint(nhiệt_độ, độ_ẩm)` — 2
- Tên bảng viết trần (không ngoặc) và phải tồn tại. Trường tính toán nên đặt **sau** bảng/trường nó dùng.
- Trường có `samples: n` tham chiếu trường khác cũng `samples: n` → tính theo từng lần đo (VD điểm sương từng lần).
- VD: `pct(H1, {@satisfaction} >= 4)` · `count(H1, {@group} == \"dv\")` · `round({D-02.l} * {D-02.w} / 1000000, 2)`.

## 6. Danh sách điểm (tùy chọn)
Nếu khảo sát theo danh sách điểm có sẵn (mã điểm, địa chỉ, tọa độ kế hoạch), cho trường mã điểm:
```json
{ "id": "A-01", "label": "Mã điểm", "type": "text", "required": true, "flags": false,
  "lookup": { "source": "sites", "fill": { "A-03": "address" }, "gps": "A-04" } }
```
`fill` chỉ điền `address`/`code`; `gps` là mã trường `gps` để app so khoảng cách với tọa độ kế hoạch (không tự điền GPS).

## 7. Mẫu nhỏ hoàn chỉnh (tham khảo cấu trúc)
```json
{
  "schemaVersion": 1, "id": "KS-DEMO-01", "version": "1.0",
  "title": "Kiểm tra trạm bơm", "recordTitle": "{A-01} · {A-02}",
  "header": [
    { "id": "HD-code", "label": "Mã phiếu", "type": "text", "required": true, "role": "recordCode", "flags": false },
    { "id": "HD-date", "label": "Ngày khảo sát", "type": "date", "required": true, "default": "$today" },
    { "id": "HD-surveyor", "label": "Người khảo sát", "type": "text", "required": true, "flags": false }
  ],
  "sections": [
    { "id": "A", "title": "A. Thông tin chung", "items": [
      { "id": "A-01", "label": "Mã trạm", "type": "text", "required": true, "flags": false },
      { "id": "A-02", "label": "Địa chỉ", "type": "text", "required": true, "flags": false },
      { "id": "A-03", "label": "Thời gian khảo sát", "type": "sessionTime", "required": true, "method": "App tự ghi" },
      { "id": "A-04", "label": "Tọa độ", "type": "gps", "required": true },
      { "id": "A-05", "label": "Thời tiết", "type": "singleChoice", "options": [
        { "value": "sunny", "label": "Nắng" }, { "value": "rain", "label": "Mưa" }, { "value": "other", "label": "Khác", "withText": true } ] }
    ]},
    { "id": "B", "title": "B. Thiết bị", "items": [
      { "id": "B-01", "label": "Model máy bơm (theo nhãn)", "type": "text", "required": true, "photoRef": "C-02" },
      { "id": "B-02", "label": "Áp suất đầu ra", "type": "number", "unit": "bar", "decimals": 1, "min": 0, "warnMin": 1, "warnMax": 8,
        "method": "Đồng hồ áp", "samples": 3 },
      { "id": "B-03", "label": "Chênh áp so với định mức 4 bar", "type": "computed", "unit": "bar", "decimals": 1,
        "formula": "{B-02} - 4", "samples": 3 },
      { "id": "B-04", "label": "Kích thước bể (dài × rộng)", "type": "composite",
        "parts": [ { "id": "l", "label": "Dài", "type": "number", "unit": "m" }, { "id": "w", "label": "Rộng", "type": "number", "unit": "m" } ] },
      { "id": "B-05", "label": "Diện tích bể", "type": "computed", "unit": "m²", "decimals": 2, "formula": "{B-04.l} * {B-04.w}" },
      { "id": "B-06", "label": "Có rò rỉ", "type": "yesNo", "required": true,
        "parts": [ { "id": "where", "label": "Vị trí", "type": "text", "showWhen": { "field": "$self", "equals": true } } ] },
      { "kind": "table", "id": "B1", "title": "B1. Hạng mục kiểm tra", "rows": [
          { "id": "r-pump", "fixed": { "item": "Máy bơm" } }, { "id": "r-valve", "fixed": { "item": "Van" } } ],
        "columns": [ { "id": "item", "label": "Hạng mục", "type": "text", "readonly": true, "flags": false },
                     { "id": "ok", "label": "Đạt", "type": "yesNo" } ] },
      { "id": "B-07", "label": "Tỷ lệ hạng mục đạt", "type": "computed", "unit": "%", "decimals": 0, "formula": "pct(B1, {@ok} == true)" }
    ]},
    { "id": "C", "title": "C. Ảnh", "items": [
      { "id": "C-01", "label": "Toàn cảnh trạm", "type": "photo", "required": true, "minCount": 2 },
      { "id": "C-02", "label": "Nhãn máy bơm", "type": "photo" },
      { "id": "C-03", "label": "Nhận xét", "type": "multiline" }
    ]}
  ],
  "signatures": [ { "id": "surveyor", "label": "Người khảo sát" }, { "id": "owner", "label": "Đại diện trạm" } ]
}
```

## 8. Quy tắc chi tiết, giới hạn và cách làm thường gặp

**Mã (id)**
- Ký tự cho phép: chữ không dấu, số, `-`, `_`. Mã trường trong mục theo dạng `A-01`; header nên dùng `HD-…`
  (VD `HD-code`, `HD-date`); id dòng cố định dạng `r-…`; id chữ ký chữ thường (`surveyor`, `manager`).
- Một **bảng** hoặc **heading** tính là 1 mục trong giới hạn "3–25 trường/mục".

**yesNo**: `labels[0]` ứng với `true`, `labels[1]` ứng với `false` (VD `["Đạt","Không đạt"]` → Đạt = `true`).

**Khoảng cảnh báo**: được dùng riêng `warnMin` hoặc riêng `warnMax`. Chỉ đặt khi có cơ sở (người dùng nêu, tiêu chuẩn ngành,
hoặc giới hạn hiển nhiên); nếu tự chọn số thì phải ghi vào "Giả định". Không chắc thì bỏ.

**Ảnh, video, ghi âm**
- `required: true` trên photo/video/audio = cần ≥ 1 tệp; `minCount` chỉ được kiểm khi trường `required` hoặc đã có ≥ 1 tệp.
- Cột bảng được là `photo`/`video`/`audio` (kể cả `minCount`).
- `photoRef` trỏ tới trường `photo`/`video` ở **bất kỳ** mục nào (kể cả cùng mục), không trỏ vào cột bảng.

**Điều kiện hiển thị** chỉ áp cho **ô phụ (`parts`)**, không ẩn/hiện được cả trường hay cả cột. `showWhen.field` có thể là
`"$self"`, một trường ngoài bảng, hoặc (khi part nằm trong cột bảng) một cột khác **cùng dòng**.
Kiểu part không có photo/video/audio → trường media luôn hiện; ghi điều kiện vào `hint` (VD "Chỉ ghi âm khi người được hỏi đồng ý").

**Bảng & công thức**
- `avg/sum/min/max/count/pct` chỉ tính các dòng **có ít nhất 1 ô đã nhập**; ô trống bị bỏ qua (không coi là 0).
  `sum` không có giá trị nào → trống. `pct`: mẫu số = số dòng đã nhập thỏa `lọc`.
- Trong điều kiện chỉ `true` mới là đúng; so sánh yesNo viết `{@ok} == true`.
- `elapsed` lưu **giây**; hiệu hai `elapsed` cũng là giây (đặt `unit: "s"`). Mốc stopwatch = số giây **tính từ lúc bấm
  Bắt đầu (T0)**, không phải thời gian từng chặng → thời lượng chặng = `{@t3} - {@t2}`.
- **Trung bình các lần đo**: `samples` + `computed` tính **theo từng lần**, không có hàm lấy trung bình các lần của một trường.
  Cần trung bình → dùng **bảng dòng cố định** ("Lần 1/2/3") rồi `avg(Bảng, {@giatri})`.
- Không có hàm ngày/giờ hiện tại (không so được "còn hạn"). Dùng trường `date` để ghi hạn + trường `yesNo` "Còn hạn".

**Danh sách điểm (`lookup`)**: người dùng nạp CSV `code,address,lat,lng` trong **Cài đặt → Danh sách điểm**. `fill` chỉ lấy
được `address` (hoặc `code`); tên điểm nếu cần thì nhập tay ở trường riêng (hoặc ghép vào cột `address` của CSV).

**Một phiếu ứng với một đối tượng/lần quan sát** (một cửa hàng, một hộ, một nút giao + một hướng đếm…). Thứ cần lặp trong cùng
một lần (người được phỏng vấn, khoảng 15 phút, thiết bị) → **bảng**. Ghi lựa chọn này vào "Giả định".

## 9. Danh sách kiểm tra trước khi trả lời
- [ ] JSON hợp lệ, có `schemaVersion: 1`, `id`, `version`, `title`, `header`, `sections`.
- [ ] Mọi `id` trường/heading/bảng **duy nhất trong toàn mẫu**; mã cột duy nhất trong từng bảng; `value` option duy nhất trong trường.
- [ ] `type` chỉ thuộc danh sách mục 3.1; cột bảng không là `computed`/`signature`.
- [ ] Mọi `singleChoice`/`multiChoice` (kể cả part) có `options` không rỗng.
- [ ] Mọi part có `id` và `label`; `showWhen.field` là `"$self"` hoặc mã trường có thật.
- [ ] Mọi `computed` có `formula`; hàm đúng số tham số; tên bảng tồn tại; `{@cot}` chỉ nằm trong hàm bảng; mọi `{MÃ}` tồn tại.
- [ ] `photoRef` trỏ tới trường `photo`/`video` có thật; `stopwatch.columns` là cột `elapsed` có thật; `pairedWith` đặt ở cả 2 bảng.
- [ ] Số đo có `unit`; nếu có cả hai thì `warnMin < warnMax`; `min ≤ warnMin`, `warnMax ≤ max` (nếu có).
- [ ] Có ≤ 1 trường `role: "recordCode"` (đặt ở header) và `recordTitle` chỉ dùng mã trường có thật.
- [ ] Văn bản hiển thị đúng ngôn ngữ người dùng, có dấu; không có thuộc tính ngoài đặc tả.
