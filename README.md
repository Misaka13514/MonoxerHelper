# MonoxerHelper

An Xposed module for Monoxer (`com.monoxer`), developed against version
`2026.09.03.01` (versionCode 219094596).

The module ships no app UI: all controls live in a persistent notification
shown while Monoxer is running.

## Features

- **Answer overlay** — the correct answer appears in a small overlay near the
  top of the screen for every question: all study question types (multiple
  choice, shuffle, dictation, handwriting, ...) plus MiniTest questions. Drag
  it anywhere — the spot is remembered across app restarts — and give it one
  of two looks from the notification: a dark pill with bold yellow text, or
  bare black text with no background.
- **Auto answering** — questions answer themselves. The right choice is
  picked, shuffle and dictation answers are typed in and submitted, and the
  study types that cannot be answered programmatically (text / decision /
  speaking / formula / handwriting) come out correct when you submit them. In
  MiniTest, choice questions are answered for you and the rest score full
  marks on submit.
- **Answer delay** — how long to wait before auto answering, cycled from the
  notification: 0.4s / 1.2s / 3s. Below roughly 1 second the app's mis-tap
  guard may reject a tap; the module retries quietly, up to 5 times.
- **MiniTest passcode** — on a passcode entry page, the overlay shows the
  test's passcode (nothing is shown when the test needs no passcode).
- **Panic stop** — a single tap on the overlay hides it and turns auto
  answering off instantly; re-enable either from the notification at any
  time.

Overlay gestures:

- **Tap** — panic stop: hide the overlay and turn auto answering off.
- **Double-tap** — toggle auto answering.
- **Long-press and drag** — move the overlay.

## Building

```bash
nix develop -c ./build.sh     # output: MonoxerHelper.apk (signed)
nix fmt                       # format the Java sources
```

`build.sh` itself assumes the toolchain from the flake is already in the
environment (`ANDROID_HOME`, `javac`, `keytool`); any other JDK 17+ Android
build-tools 34 setup works too. The first nix run downloads about 2 GB of
Android SDK components. No Android Studio or Gradle required.

## Installing

1. `adb install MonoxerHelper.apk`
2. Enable the module in LSPosed (Vector) with scope `com.monoxer` (the manifest
   declares `xposedscope`, so it is suggested automatically)
3. Grant Monoxer notification permission (needed by the control notification):
   `adb shell pm grant com.monoxer android.permission.POST_NOTIFICATIONS`
4. Force-stop and reopen Monoxer

## Troubleshooting

Filter LSPosed logs by `MonoxerHelper`:

- Every displayed question logs one line (`question <Type>: show=... auto=...`).
- `notifications disabled for the target app` — run the `pm grant` command
  above.
- Each failed hook is logged individually (e.g. after a Monoxer update changes
  class/method names, you will see `failed to hook ...`); the reflection names
  in `MainHook`/`AutoAnswer` then need updating against the new dex.

## Design notes

- **No IPC surface.** The module declares no activities, services, receivers
  or providers. All code runs inside the hooked process; settings persist in
  the target app's own storage; the notification and its buttons are handled
  by a runtime-registered receiver inside that same process. (A binder channel
  to the module app is not an option anyway: Android 11+ package visibility
  filtering hides the module from the target, so binding to it fails.)
- **Pure reflection.** The module's classes are loaded by an isolated
  ClassLoader that cannot resolve the target app's classes, so the target is
  never referenced as a type — only by class/method names via `XposedHelpers`.

## Project layout

- `src/` — module code: entry `MainHook`, overlay `AnswerOverlay`,
  auto-answering `AutoAnswer`, control notification `TargetNotification`,
  settings `Prefs`
- `stubs/` — compile-only stubs of the classic Xposed API, not packaged into
  the APK (the framework provides the real implementation at runtime)

## Known limitations

- text/decision/speaking/formula/handwriting question types cannot be answered
  programmatically; only the overlay and forced verdict are available
- Same for non-choice MiniTest questions (dictation, formula, ...)
- If a future app update changes the class/method layout, update the reflection
  names in `MainHook`/`AutoAnswer` against the new dex
