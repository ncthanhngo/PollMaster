# PollMaster — Template, Record & Export Contract (schemaVersion 1)

Đây là **hợp đồng chung** giữa app iOS và Android. Hai app phải đọc cùng file mẫu,
ghi cùng định dạng record và xuất cùng nội dung. Mọi thay đổi ở đây phải áp cho cả 2.

- Mẫu ví dụ: `shared/templates/bb-tdp-01.json`
- Test vector công thức: `shared/formula-test-vectors.json` (cả 2 app chạy trong unit test)

## 1. Template

```jsonc
{
  "schemaVersion": 1,
  "id": "BB-TDP-01",                 // duy nhất; import trùng id+version → hỏi ghi đè
  "version": "1.0",
  "title": "Biên bản khảo sát thông số kỹ thuật",
  "subtitle": "…",                   // optional
  "instructions": "…",               // optional, text nhiều dòng, hiển thị ở màn hình đầu phiếu
  "recordTitle": "{HD-code} – {A-01}", // optional; {fieldId} thay bằng giá trị hiển thị
  "mediaNamePattern": "{recordCode}_{fieldId}_{seq}", // optional, mặc định như này
  "header": [ Field, ... ],          // trường đầu biên bản
  "sections": [ Section, ... ],
  "signatures": [ { "id": "surveyor", "label": "Người khảo sát" }, ... ]
}
```

`{recordCode}` = giá trị field có `"role": "recordCode"` (header), ký tự không hợp lệ cho
tên file (`/ \ : * ? " < > |` và khoảng trắng) đổi thành `-`. `{seq}` = số thứ tự 2 chữ số (01, 02…).

### Section

```jsonc
{ "id": "A", "title": "Thông tin chung", "note": "…optional…", "items": [ Item, ... ] }
```

`Item` là một trong:
- **Field** (không có `kind` hoặc `"kind": "field"`)
- **Heading**: `{ "kind": "heading", "id": "C1", "title": "Hình học khoang", "note": "…optional…" }`
- **Table**: xem §1.3

### 1.1 Field

| Thuộc tính | Kiểu | Ý nghĩa |
|---|---|---|
| `id` | string | Mã trường (A-01…). Duy nhất trong toàn template (gồm header, cột bảng thì duy nhất trong bảng). Giữ nguyên khi xuất. |
| `label` | string | Nhãn |
| `type` | string | Xem bảng kiểu |
| `required` | bool | Mặc định false |
| `unit` | string? | Đơn vị hiển thị hậu tố (mm, °C…) |
| `method` | string? | Phương pháp / dụng cụ (hiển thị dạng gợi ý nhỏ) |
| `hint` | string? | Placeholder / chú thích |
| `flags` | bool? | Cho phép đánh dấu **~** (ước lượng) và **KĐĐ** (không đo được, bắt buộc nhập lý do). Mặc định `true` cho `number`, `composite`, `text`; `false` cho kiểu khác. |
| `samples` | int? | Số lần đo (G2: 3). Giá trị thành mảng độ dài n; UI hiển thị n ô "Lần 1..n". Required ⇒ ít nhất 1 lần có giá trị. |
| `parts` | Part[]? | Ô nhập phụ (xem §1.2). |
| `default` | any? | Giá trị mặc định khi tạo phiếu. Chuỗi đặc biệt: `"$today"` (date hôm nay), `"$now"` (datetime). |
| `readonly` | bool? | Không cho sửa (dùng với default) |
| `role` | string? | `"recordCode"` — field dùng làm mã phiếu |
| `prefix` | string? | Tiền tố hiển thị trước ô nhập (VD "BB-TDP-01 / ") — không lưu vào value |
| `attachPhoto` | bool? | Cho đính kèm ảnh phác/ảnh minh họa vào field (dạng "Chữ + ảnh"); lưu ở `FieldValue.media` |

Kiểu (`type`) và dạng `v` lưu trong record:

| type | UI | `v` |
|---|---|---|
| `text` | 1 dòng | string |
| `multiline` | nhiều dòng | string |
| `number` | bàn phím số; `min`,`max`,`decimals` optional | number |
| `integer` | số nguyên | number |
| `singleChoice` | radio/segmented; `options` | string (option `value`) |
| `multiChoice` | checkbox; `options` | string[] |
| `yesNo` | 2 nút Có/Không; `labels` optional `["Có","Không"]`; `allowUnknown: true` thêm nút "KĐĐ"→ lưu `"unknown"` | `true` / `false` / `"unknown"` |
| `rating` | 1..`max` (mặc định 5); `minLabel`,`maxLabel` | number |
| `date` | date picker | `"YYYY-MM-DD"` |
| `time` | time picker | `"HH:mm"` |
| `datetime` | date+time | ISO-8601 có offset |
| `sessionTime` | **App tự ghi**: start = lúc tạo phiếu, end = lúc bấm Hoàn thành; cho sửa tay | `{ "start": ISO, "end": ISO \| null }` |
| `month` | tháng/năm | `"YYYY-MM"` |
| `gps` | nút "Lấy vị trí" + hiển thị lat/long/độ chính xác; cho nhập tay | `{ "lat": n, "lng": n, "accuracy": n \| null, "capturedAt": ISO }` |
| `compass` | độ 0–359; nút đọc la bàn thiết bị nếu có | number |
| `photo` | chụp/chọn nhiều ảnh; `minCount`,`maxCount` optional | Media[] |
| `video` | quay/chọn video | Media[] |
| `elapsed` | mm:ss (giây) | number (giây) |
| `composite` | chỉ có `parts`, không có ô chính | `null` |
| `computed` | readonly, tính từ `formula` (§3); `decimals` | không lưu; tính khi hiển thị/xuất |
| `signature` | khung ký tay | `{ "file": "media/…png" }` |

`options`: `[{ "value": "gas_station", "label": "Cây xăng" }, { "value": "other", "label": "Khác", "withText": true }]`.
`withText: true` ⇒ khi chọn, hiện ô nhập; text lưu ở `FieldValue.other[value]`.

Media: `{ "file": "media/<tên>.jpg", "takenAt": ISO, "lat": n|null, "lng": n|null }` — đường dẫn tương đối thư mục record.

### 1.2 Part (ô nhập phụ)

Dùng cho dạng "___ × ___", "Có — vươn ___ mm", "màu: ___".

```jsonc
{ "id": "w", "label": "Rộng", "type": "number", "unit": "mm",
  "showWhen": { "field": "$self", "equals": true } }   // optional
```

- `type` ∈ `text | number | integer | singleChoice | yesNo | time | rating`.
- `showWhen.field`: `"$self"` = field chứa part, hoặc id field khác. Điều kiện: `equals` (so sánh với `v`) hoặc `contains` (multiChoice có chứa).
- Giá trị lưu `FieldValue.parts[partId]`. Part ẩn thì không validate và vẫn giữ giá trị (không xuất khi ẩn).
- Field required có parts: thỏa khi `v` có giá trị (hoặc với `composite`: mọi part không ẩn có giá trị), hoặc cờ KĐĐ có lý do.

### 1.3 Table

```jsonc
{
  "kind": "table", "id": "E1", "title": "Nhật ký từng lượt đổi pin", "note": "…",
  "rowLabel": "Lượt",               // optional, tiêu đề mỗi dòng: "Lượt 1", "Lượt 2"…
  "minRows": 0,                     // validate khi required
  "required": false,
  "rows": [ { "id": "S04-5", "fixed": { "kb": "S04", "levelValue": 5 } } ], // optional: bảng dòng cố định
  "stopwatch": { "columns": ["T1","T2","T3","T4","T5","T6","T7","T8"] },   // optional, §1.4
  "columns": [ Field, ... ]          // Field như §1.1, không dùng type table/computed/signature
}
```

- Không có `rows` ⇒ bảng **lặp**: người dùng thêm/xóa/nhân bản dòng, row id = UUID.
- Có `rows` ⇒ **dòng cố định**, không thêm/xóa. Column có `"readonly": true` hiển thị giá trị từ `fixed[columnId]`.
  Các key khác trong `fixed` không hiển thị nhưng công thức đọc được qua `{@key}`.
- UI: danh sách thẻ dòng (tóm tắt 2–3 cột đầu), chạm để mở form sửa dòng.

### 1.4 Stopwatch

Bảng có `stopwatch`: trong form dòng có nút **Bắt đầu (T0)** rồi **Mốc tiếp theo**, mỗi lần bấm ghi
số giây kể từ T0 vào cột kế tiếp còn trống theo thứ tự `columns`. Có nút hoàn tác mốc cuối.
Đồng hồ chạy theo thời gian hệ thống (không phụ thuộc màn hình sáng/tắt). Trạng thái T0 lưu ở
`row.stopwatchStart` (ISO) để sống sót khi app bị kill.

## 2. Record (lưu trên máy)

Thư mục: `<AppData>/records/<recordId>/record.json` + `media/`. Template đã import:
`<AppData>/templates/<id>@<version>.json`. Autosave debounce ~500 ms, ghi nguyên tử (file tạm rồi rename).

```jsonc
{
  "schemaVersion": 1,
  "id": "uuid",
  "templateId": "BB-TDP-01",
  "templateVersion": "1.0",
  "status": "draft",                // "draft" | "completed"
  "createdAt": ISO, "updatedAt": ISO, "completedAt": ISO | null,
  "values": { "<fieldId>": FieldValue },             // header + section fields
  "tables": { "<tableId>": [ Row ] },
  "signatures": { "<signatureId>": { "file": "media/sig_surveyor.png", "name": "Nguyễn Văn A", "signedAt": ISO } }
}
```

```jsonc
// FieldValue — mọi key optional, bỏ key khi rỗng
{
  "v": <xem bảng kiểu>,              // với samples: mảng độ dài n, phần tử null nếu trống
  "flag": "estimate" | "notMeasurable",
  "reason": "string",               // bắt buộc khi notMeasurable
  "note": "string",                 // cột Ghi chú
  "other": { "<optionValue>": "text" },
  "parts": { "<partId>": <value> },
  "media": [ Media ]                // khi field có attachPhoto
}
// Row
{ "id": "uuid | fixed row id", "values": { "<columnId>": FieldValue }, "stopwatchStart": ISO? }
```

Giá trị "trống": thiếu key, `null`, `""`, `[]`.

## 3. Công thức (`type: "computed"`)

Cú pháp (khoảng trắng tự do):

```
expr    := or
or      := and ("||" and)*
and     := cmp ("&&" cmp)*
cmp     := add (("=="|"!="|"<"|"<="|">"|">=") add)?
add     := mul (("+"|"-") mul)*
mul     := unary (("*"|"/") unary)*
unary   := "-" unary | "!" unary | primary
primary := NUMBER | STRING | "true" | "false" | "null"
         | "{" REF "}" | IDENT "(" args? ")" | "(" expr ")"
args    := arg ("," arg)*          ; arg = expr, hoặc IDENT trần (tên bảng) ở tham số đầu của hàm bảng
REF     := fieldId ["." partId]     ; VD {G-34}, {B-11.diag}
         | "@" columnId             ; cột dòng hiện tại trong hàm bảng (gồm key `fixed`)
STRING  := "…" (hỗ trợ \" và \\)
```

Ngữ nghĩa:
- Field trống → `null`. Số học/so sánh thứ tự có `null` → `null`. `==`/`!=` với null: `null == null` là true.
  `&&`/`||`/`!` coi `null` là false. Chia 0 → `null`.
- `{X}` đọc `v` của field X (header/section). yesNo → `true`/`false`/`"unknown"`. Cờ KĐĐ → `null`.
- **Samples**: computed có `samples: n` được tính n lần; lần i, ref tới field có samples trả phần tử i,
  ref tới field không samples trả `v` bình thường. Kết quả là mảng n.
- `{@col}` chỉ hợp lệ trong tham số biểu thức của hàm bảng; đọc `values[col].v`, nếu không có thì `fixed[col]`.
- Ref tới field `computed` khác được phép (tính đệ quy; vòng lặp → `null`).

Hàm:

| Hàm | Ý nghĩa |
|---|---|
| `avg(T, expr[, filter])`, `sum`, `min`, `max` | Trên các dòng của bảng T thỏa `filter` (mặc định true), bỏ qua kết quả `null`. Không có giá trị → `null` (`sum` → `null`). |
| `count(T[, filter])` | Số dòng **không rỗng** (có ít nhất 1 value) thỏa filter |
| `pct(T, cond[, filter])` | 100 × (#dòng không rỗng thỏa filter && cond) / (#dòng không rỗng thỏa filter); mẫu 0 → `null` |
| `dewpoint(t, rh)` | Magnus: a=17.62, b=243.12; γ = ln(rh/100) + a·t/(b+t); Td = b·γ/(a−γ). rh ≤ 0 → null |
| `round(x, n)` | Làm tròn half-away-from-zero n chữ số |
| `abs(x)`, `if(c, a, b)` | |

Hiển thị: làm tròn theo `decimals` của field (mặc định 2), bỏ số 0 thừa. Xuất JSON: giữ số gốc chưa làm tròn
được phép lệch < 1e-9 — test vector so sánh với sai số 1e-6.

## 4. Validation & hoàn thành

- Thiếu bắt buộc: field `required` trống (và không có `flag: notMeasurable` + `reason`), table `required` có ít
  hơn `minRows` dòng không rỗng, photo có `minCount` chưa đủ, KĐĐ thiếu lý do, number ngoài `min`/`max`.
- Màn **Kiểm tra & hoàn thành** liệt kê lỗi theo section, chạm để nhảy tới trường. Chữ ký trong `signatures`.
- Quy ước đã chốt (2 app phải giống nhau):
  - Table `required`: cần ≥ `max(minRows, 1)` dòng không rỗng; table không `required` không kiểm số dòng.
    Cột bắt buộc / min-max trong bảng chỉ kiểm trên dòng không rỗng.
  - `minCount` của photo/video chỉ kiểm khi field `required` hoặc đã có ≥ 1 file.
  - Chữ ký không bắt buộc để hoàn thành.
  - Field có `samples` được tham chiếu từ computed không có `samples` → `null`.
  - Ghi JSON: key có giá trị `null` được phép bỏ hoặc ghi `null`; khi đọc phải chấp nhận cả hai.
  - UI: badge mã chỉ hiện cho field header/section, không hiện cho cột bảng.
- **Hoàn thành** chỉ bật khi không còn lỗi. Khi hoàn thành: `status=completed`, `completedAt`, set `end` cho
  `sessionTime`. Phiếu completed vẫn mở sửa được sau khi xác nhận "Mở lại" (status về draft).

## 5. Xuất

Màn xuất: chọn 1+ định dạng → tạo file → share sheet. Tên file gốc: `{recordCode|recordId}_{yyyyMMdd-HHmm}`.

1. **JSON + media (`.zip`)**: `record.json` (đúng §2) + `export.json` + thư mục `media/`.
   `export.json` = `{ "template": {id, version, title}, "record": {id, status, createdAt, updatedAt, completedAt},
   "fields": { "<id>": { "label", "unit", "value", "flag", "reason", "note", "other", "parts" } },
   "computed": { "<id>": number|array|null }, "tables": { "<tableId>": [ { "id", "<colId>": value, … } ] } }`.
   `value` là `v` thô; media là tên file.
2. **Excel (`.xlsx`)**: sheet `Thông tin` (cột: Mục, Mã, Thông số, Giá trị, Đơn vị, Cờ, Lý do, Ghi chú) gồm header,
   field và computed — giá trị hiển thị dạng chữ (option label, "Có/Không", parts nối "Rộng: 10 mm; Cao: 5 mm",
   samples nối " | "). Mỗi bảng 1 sheet (tên = id bảng, ≤31 ký tự), hàng đầu là label cột. Số giữ kiểu số khi là số thuần.
   Kèm thêm **CSV** của sheet `Thông tin` (UTF-8 có BOM) khi người dùng chọn "CSV".
3. **PDF (`.pdf`)**, khổ A4 dọc: tiêu đề, subtitle, bảng header, từng section dạng bảng (Mã | Thông số | Giá trị | Ghi chú),
   bảng dữ liệu dạng lưới, ảnh thu nhỏ (tối đa 2/hàng) ở cuối mỗi section có ảnh, trang chữ ký. Font hỗ trợ tiếng Việt.
   Số trang ở chân trang.
4. **Word (`.docx`)**: sinh WordprocessingML với cùng bố cục như PDF (bảng có viền, tiêu đề in đậm), ảnh nhúng, chữ ký.
   Không phụ thuộc file Word gốc.

Hiển thị giá trị trong mọi định dạng: cờ estimate thêm tiền tố `~`, KĐĐ hiển thị `KĐĐ (lý do)`.

## 6. Quy tắc thống nhất (bắt buộc cả 2 app — chốt sau review chéo)

### 6.1 Ghi / đọc record
- Luôn ghi đủ: `schemaVersion`, `status`, `values`, `tables`, `signatures` (map rỗng ghi `{}`), `row.values`.
  Key optional có giá trị null được bỏ. Khi đọc: mọi key ngoài `id` đều tolerant (thiếu → mặc định).
- Thời điểm: ghi `yyyy-MM-dd'T'HH:mm:ssXXX` (luôn có giây, không phần thập phân, VD `2026-10-23T10:00:00+07:00`).
  Đọc phải chấp nhận cả dạng không giây và có phần thập phân. Lỗi parse 1 giá trị không được làm mất phần còn lại
  (VD 1 media hỏng không làm biến mất cả danh sách; record lỗi hiển thị trong danh sách với trạng thái "Lỗi đọc", không âm thầm ẩn).
- Bảng dòng cố định: không bắt buộc lưu sẵn dòng. UI, công thức, xuất **luôn duyệt theo `template.rows`** (đúng thứ tự),
  ghép với dòng đã lưu cùng id; dòng lưu có id lạ bị bỏ qua.

### 6.2 Công thức
- Truthy: **chỉ boolean `true`**. Số, chuỗi, `"unknown"`, null đều là false trong `&&`, `||`, `!`, `if`, filter, cond.
- `avg/sum/min/max/count/pct` chỉ duyệt **dòng không rỗng** (có ≥ 1 value đã lưu; giá trị `fixed` không tính là có dữ liệu).
- Template import **từ chối** công thức không hợp lệ: lỗi cú pháp, hàm lạ, sai số tham số, `{@x}` ngoài hàm bảng,
  tên bảng không tồn tại. Nếu runtime vẫn lỗi → toàn công thức = null.
- `round` phải chính xác thập phân (`round(1/3, 2) == 0.33` là true).

### 6.3 Validation
- Rỗng = thiếu / null / chuỗi chỉ có khoảng trắng / mảng rỗng. Áp cho cả `reason`.
- Tiến độ `x/y`: một mục bắt buộc chỉ tính "xong" khi không còn issue nào trên nó (KĐĐ thiếu lý do = chưa xong).
- `showWhen.contains` với giá trị không phải mảng → so sánh bằng.
- Field `signature` required được validate (cần file). Dòng lưu có id lạ trong bảng cố định không validate.
- Thông điệp (đúng nguyên văn): `Bắt buộc nhập` · `Cần nhập lý do KĐĐ` · `Giá trị phải ≥ {min}` · `Giá trị phải ≤ {max}` ·
  `Cần tối thiểu {n} tệp (hiện có {k})` · `Cần tối thiểu {n} dòng (hiện có {k})`.

### 6.4 Tên file
- `{recordCode}` lấy từ `v` thô của field role recordCode (không phải chuỗi hiển thị). Làm sạch: mỗi **chuỗi liên tiếp**
  ký tự không hợp lệ (`/ \ : * ? " < > |` và khoảng trắng) → một `-`, bỏ `-` đầu/cuối. Rỗng → record id.
- `{fieldId}` của cột bảng = `{tableId}-{columnId}`. `{seq}` = số nhỏ nhất ≥ 1 chưa dùng cho field đó, 2 chữ số.
- Ảnh `.jpg`; video giữ phần mở rộng gốc viết thường (`.mp4`/`.mov`). Chữ ký: `media/sig_{signatureId}.png`.
- Tên file xuất: `{recordCode đã làm sạch | recordId}_{yyyyMMdd-HHmm}`.

### 6.5 Hiển thị (UI, xlsx, CSV, PDF, DOCX dùng chung 1 hàm)
| Kiểu | Hiển thị |
|---|---|
| number/integer | có `decimals` → làm tròn half-away rồi bỏ 0 thừa; không có → giá trị lưu nguyên (biểu diễn ngắn nhất) |
| computed | làm tròn theo `decimals` (mặc định 2), bỏ 0 thừa; samples nối ` \| ` |
| option `withText` | `Khác: xyz` |
| yesNo | `Có` / `Không` / `KĐĐ` (theo `labels` nếu có) |
| giá trị chính + parts | `Có; Vươn: 50 mm` (nối bằng `; `); chỉ có parts → `Rộng: 10 mm; Cao: 5 mm` |
| rating | `4/5` |
| date / month / datetime | `dd/MM/yyyy` · `MM/yyyy` · `dd/MM/yyyy HH:mm` (múi giờ thiết bị) |
| sessionTime | `dd/MM/yyyy HH:mm – dd/MM/yyyy HH:mm`; chưa có end → `dd/MM/yyyy HH:mm – …` |
| gps | `10.500000, 106.250000 (±5 m)` (6 chữ số; không có accuracy thì bỏ ngoặc) |
| elapsed | `mm:ss` (≥ 1 giờ: `h:mm:ss`) |
| photo/video/signature | danh sách tên file (không có `media/`), nối `, ` |
- `recordTitle`: thay placeholder; sau đó cắt các ký tự ` –-/·` thừa ở đầu/cuối; nếu mọi placeholder rỗng (hoặc không có `recordTitle`) → `Phiếu mới · {createdAt dd/MM/yyyy HH:mm}`.
- xlsx: ô số chỉ khi giá trị là số thuần (number/integer/computed/rating giữ text `4/5`), giá trị = số **đã làm tròn** như hiển thị.
  Tên sheet: ký tự `[ ] : * ? / \` → `_`, cắt 31 ký tự, trùng thì thêm ` (2)`, ` (3)`.
- export.json: `value` rỗng → `null` (kể cả photo rỗng); thời điểm record copy nguyên chuỗi đã lưu; bảng cố định xuất đủ
  mọi dòng của template theo thứ tự.
- PDF/DOCX: bảng lặp có cột đầu `#` = `{rowLabel} {n}`; bảng cố định không có cột thêm. Chân trang `Trang n/N` ở cả PDF và DOCX.

### 6.6 Import template
- singleChoice/multiChoice không có `options` → từ chối. `rows[].fixed` thiếu → `{}`. `part.type` thiếu → `text`;
  `part.label` thiếu → từ chối. Lỗi import báo đường dẫn JSON (VD `sections[2].items[5].options`).

## 7. Mở rộng cho hiện trường (schemaVersion vẫn 1 — mọi key mới đều optional)

### 7.1 Thuộc tính field mới
| Thuộc tính | Ý nghĩa |
|---|---|
| `photoRef` | id field photo/video ở mục khác làm bằng chứng (VD `"P08"`). Thẻ field hiện thumbnail của field đó + nút "Chụp P08" (ảnh lưu vào field được tham chiếu, không vào field hiện tại). Thẻ field photo được tham chiếu hiện "Dùng cho: B-01, A-07…". |
| `warnMin`, `warnMax` | Khoảng hợp lý: ngoài khoảng → **cảnh báo** vàng dưới ô ("Ngoài khoảng thường gặp 1000–2500 mm — kiểm tra lại") và một lần hỏi xác nhận khi rời ô. Không chặn hoàn thành. Áp cho number/integer, cả samples và parts number (part cũng có thể có warnMin/warnMax). |
| `maxAccuracy` | (gps) mét, mặc định 15. Độ chính xác kém hơn → cảnh báo + nút "Lấy lại". |
| `lookup` | `{ "source": "sites", "fill": { "A-03": "address" }, "gps": "A-04" }` — field text chọn từ **danh sách điểm** (§7.3); chọn xong điền các field trong `fill` (chỉ `address`/`code`; **không** điền GPS — tọa độ kế hoạch chỉ dùng để tính khoảng cách, GPS phải đo thật). `gps` = id field GPS để so khoảng cách với tọa độ kế hoạch (chỉ điền khi field đích đang trống, hoặc hỏi ghi đè). Vẫn cho gõ tay. |
| `carryOver` | (cột bảng) dòng mới tự lấy giá trị cột này từ dòng trước. |

Kiểu mới **`audio`**: nút ghi âm / dừng, danh sách bản ghi (thời lượng, phát lại, xóa). `v` = Media[] (`.m4a`), tên file theo `mediaNamePattern`. Hiển thị = tên file.

### 7.2 Thuộc tính table mới
- `pairedWith: "<tableId>"` — hai bảng lặp ghép đôi: thêm dòng ở bảng này tạo dòng **cùng id** ở bảng kia và ngược lại; xóa/nhân bản áp cả hai. Form dòng ("Ghi lượt") hiển thị cột của cả hai bảng (bảng có stopwatch đặt trên cùng). Lưu và xuất vẫn là 2 bảng riêng.
- `quickEntry: true` — form dòng dạng gọn: singleChoice/yesNo hiển thị lưới nút một chạm (không dropdown), nhãn trên cùng hàng.

### 7.3 Danh sách điểm (sites)
Cài đặt → "Danh sách điểm" → nhập CSV UTF-8 (có hoặc không BOM), header: `code,address,lat,lng` (thêm cột khác bị bỏ qua).
Lưu `<AppData>/sites.json` = `[{ "code", "address", "lat", "lng" }]`. Chọn điểm có tọa độ + đã lấy GPS → hiện
"Cách điểm kế hoạch N m" (cảnh báo nếu > 100 m).

### 7.4 Record — key mới
- `FieldValue.review`: ghi chú của người kiểm tra (string).
- `record.returned: true` khi người kiểm tra trả lại (status về `draft`). Bỏ khi hoàn thành lại.
- `record.lastPosition: { "section": "<sectionId>", "field": "<fieldId>" }` — vị trí soạn gần nhất.
- `record.backupAt`: ISO — lần sao lưu tự động gần nhất.
Hiển thị/xuất: `review` xuất vào export.json (`fields.<id>.review`) và cột "Kiểm tra" trong sheet `Thông tin`.

### 7.5 Validation bổ sung
- Cảnh báo (`warnMin/warnMax`, GPS accuracy, cách điểm kế hoạch) là **warning**, không tính vào lỗi/tiến độ;
  màn Kiểm tra liệt kê riêng nhóm "Cần xem lại" (màu vàng). Thông điệp: `Ngoài khoảng thường gặp {warnMin}–{warnMax} {unit}` ·
  `Độ chính xác GPS kém (±{n} m)` · `Cách điểm kế hoạch {n} m`.
- Field có `review` chưa được xử lý không chặn hoàn thành nhưng liệt kê nhóm "Ghi chú kiểm tra".
