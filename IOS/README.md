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
