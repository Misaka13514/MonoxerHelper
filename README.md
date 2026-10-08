# MonoxerHelper

An Xposed module for Monoxer (`com.monoxer`), developed against version
`2026.09.03.01` (versionCode 219094596).

The module ships no app UI: all controls live in a persistent notification
shown while Monoxer is running.

## Features

| Control                   | Behavior                                                                                                                                                                                                                                                                                                                                                                        |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Answer overlay            | After each question is shown, overlays a TextView below the status bar/cutout with the correct answer. Covers every study question type (multiple choice / shuffle (narabekae) / dictation / handwriting, etc.) and MiniTest.                                                                                                                                                   |
| Fully automatic answering | Study mode: multiple-choice questions are auto-answered by clicking the correct option; shuffle/dictation questions get the answer typed in and submitted; all other types (text/decision/speaking/formula/handwriting) are forced correct on submit via `QuestionResult.recordResult`. MiniTest: choice questions are auto-answered; all other types get full score on submit. |
| Answer delay              | Wait before auto-answering, cycled by a notification button: 0.4s / 1.2s / 3s. Values below roughly 1 second may trip the app's built-in mis-tap guard (retried automatically, up to 5 attempts).                                                                                                                                                                               |

Overlay gestures:

- **Tap** — turn the overlay off until re-enabled via the notification (which
  re-shows it for the current question).
- **Double-tap** — toggle auto answering; same setting as the notification's
  Auto button. Turning it on answers the question currently on screen right
  away; turning it off cancels its pending answer.
- **Long-press and drag** — move the overlay; the position sticks for the
  rest of the app session.

The control notification (toggle answers / toggle auto / cycle delay) appears
as soon as Monoxer starts and stays until the app is stopped. It is posted by
the hooked Monoxer process, so it needs Monoxer's notification permission.

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
