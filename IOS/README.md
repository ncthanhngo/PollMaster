# PollMaster iOS

Offline, template-driven field survey app (SwiftUI, iOS 17+, Swift 6, Observation).
Contract: `../docs/template-schema.md`, `../docs/ux-spec.md`; shared fixtures in `../shared`.

## Structure

- `PollMasterCore/` – local Swift package, no UI. Models (template, record, `JSONValue`), formula parser/evaluator
  (`Formula/`), validation + record factory (`Validation/`), JSON storage (`Storage/`), exporters
  (`Export/`: export.json, zip, xlsx, docx, pdf, csv). Depends on ZIPFoundation.
- `PollMaster/` – app target. `App/` (environment, tabs, routes), `Records/`, `Templates/`, `Settings/`,
  `Editor/` (editor model, section/field/table/row screens, stopwatch, review, signature), `Inputs/` (per-type inputs,
  media, camera), `Services/` (settings, location, media saving).
- `project.yml` – XcodeGen spec. `PollMaster.xcodeproj` is generated and git-ignored.
  `../shared/templates` is referenced as a resource folder (not copied) and auto-installed on first launch.

## Build / run / test

```sh
# core logic tests (macOS host, fast)
cd PollMasterCore && swift test

# app
xcodegen generate
xcodebuild -scheme PollMaster -destination 'generic/platform=iOS Simulator' build
# or open PollMaster.xcodeproj in Xcode and Run (set a signing team for a real device)
```

Debug builds accept `-pmCreate <templateId> [-pmSection <n>]` launch arguments to open a fresh record directly
(used for simulator screenshots). More DEBUG-only aids (`-pmFocus`, `-pmPartial`, `-pmReviewDraft`, `-pmFillStep`, `-pmMap`, `-pmSites`, `-pmOneHand`, `-pmScrollDown`) are listed in `PollMaster/App/DebugLaunch.swift`. Tests can keep the generated sample PDF with `PM_EXPORT_DIR=<dir> swift test`.

## Data

`Application Support/PollMaster/{templates/<id>@<version>.json, records/<id>/record.json + media/}`;
atomic writes, ~500 ms debounced autosave.

## Phát hành TestFlight

Bundle ID `com.evselab.pollmaster`, team `566SKQXVZT`, chứng chỉ Apple Distribution có sẵn trong keychain.

1. Lưu Issuer ID của khóa App Store Connect (một lần):
   `security add-generic-password -U -s pollmaster-asc-issuer -a pollmaster -w <issuer-uuid>`
2. Lần đầu: tạo app trên appstoreconnect.apple.com → Apps → + → New App (iOS, tên `PollMaster`, ngôn ngữ Vietnamese,
   Bundle ID `com.evselab.pollmaster`, SKU `pollmaster`). `scripts/asc-setup.py` báo nếu chưa có.
3. Commit, rồi `./release-testflight.sh` — đăng ký bundle id + tạo/cài profile "PollMaster App Store" (idempotent), chạy test,
   archive (số build = số commit), kiểm tra chữ ký, upload bằng altool.
