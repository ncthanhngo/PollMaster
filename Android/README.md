# PollMaster – Android

Offline, template-driven field survey app (Kotlin, Jetpack Compose, Material 3). It reads the same
templates as the iOS twin and writes the same record/export format, see
`../docs/template-schema.md` and `../docs/ux-spec.md`.

- applicationId `com.ncthanhngo.pollmaster`, minSdk 26, target/compile 35
- Single module `:app`, single activity, Navigation Compose
- No network except the optional "Nhập từ URL" template import

## Structure

```
app/src/main/kotlin/com/ncthanhngo/pollmaster/
  core/            pure Kotlin, no Android classes (unit-tested on the JVM)
    model/         Template / Record / FieldValue (kotlinx.serialization), helpers
    formula/       parser + evaluator (`FormulaEngine`), data access (`FormulaSource`)
    validation/    `Validator` (required, KĐĐ reason, min/max, minCount, minRows, showWhen)
    format/        display text for values, titles, number/date formatting
    storage/       atomic JSON files, `RecordStore`, `TemplateStore`, `TemplateParser`, `DebouncedSaver`
    editor/        `EditorController` (all edits, autosave, complete/reopen), `RecordFactory`, stopwatch, media names
    export/        export.json, xlsx / docx (hand-written OOXML), csv, zip, neutral `ExportDoc` layout
  data/            Settings, repositories, `ExportService`
  platform/        media processing (EXIF, resize, JPEG), location, compass, share
  export/          `PdfExporter` (PdfDocument + StaticLayout), image helpers
  ui/              Compose screens: records, templates, settings, editor (outline, section, row form,
                   review, field cards/inputs, signature, stopwatch, export sheet)
app/src/test/      JUnit tests (decode sample template, all formula vectors, validation, storage,
                   autosave, stopwatch, exports)
```

Bundled templates are the files in `../shared/templates` (added to the assets via `sourceSets`, not copied).
Unit tests read `../shared/formula-test-vectors.json` and `../shared/templates/bb-tdp-01.json` by relative path.

## Toolchain (macOS, Homebrew)

```sh
brew install openjdk@17 gradle
brew install --cask android-commandlinetools
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses
sdkmanager --sdk_root=$ANDROID_HOME "platform-tools" "platforms;android-35" "build-tools;35.0.0"
echo "sdk.dir=$ANDROID_HOME" > local.properties     # gitignored
```

`gradle wrapper` has already been run (Gradle 8.11.1); always use `./gradlew`. JDK 17 is required
(newer JDKs are not supported by this AGP version).

## Build, test, lint

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew test            # JVM unit tests, no device needed
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
./gradlew lint            # report: app/build/reports/lint-results-debug.html
```

## Run on an emulator / device

```sh
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
sdkmanager --sdk_root=$ANDROID_HOME emulator "system-images;android-35;google_apis;arm64-v8a"
avdmanager create avd -n pollmaster -k "system-images;android-35;google_apis;arm64-v8a" -d pixel_6
$ANDROID_HOME/emulator/emulator -avd pollmaster &
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/platform-tools/adb shell am start -n com.ncthanhngo.pollmaster/.MainActivity
```

## Data on device

`filesDir/templates/<id>@<version>.json`, `filesDir/records/<recordId>/record.json` + `media/`.
Writes are atomic (temp file + rename); edits are autosaved after ~500 ms and flushed when the app stops.
Exports are created in `cacheDir/exports` and shared through a `FileProvider` (`ACTION_SEND_MULTIPLE`).
