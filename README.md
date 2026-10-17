# AIVoiceBrain

Android accessibility voice controller, package `com.local.aivoicecontrol`.
Android 8.0+ (API 26); compiles/targets API 34 with Java 17, Kotlin 1.9.22,
Android Gradle Plugin 8.2.2 and Gradle 8.6 in CI (a Gradle 8.2.1 wrapper is also included).

## Download the APK

Every branch push runs **Build AI Voice Brain APK** in GitHub Actions. It can
also be started with **Run workflow**. Open a successful run in the repository's
**Actions** tab and download **AI-Voice-Brain-APK** from **Artifacts**. Unzip it
to obtain `app-debug.apk`. Artifacts use the repository’s default retention period. This is a
standard debug-signed APK, not a release-store build. GitHub Actions must be
enabled for the repository.

## Build locally

With Java 17 and Android SDK installed:

```sh
./gradlew assembleDebug lintDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.
The first build requires internet to download Gradle, build plugins, Kotlin,
and Android SDK components. No third-party app libraries are declared (the
Kotlin plugin still adds its standard library).

## Phone setup and usage

Install the APK, grant microphone permission, allow battery exemption, and
explicitly enable **AI Voice Brain** in Android Accessibility settings.
Android does not allow this app to grant itself those permissions or enable
its own accessibility service. With a broken touchscreen, use a USB OTG mouse,
an existing accessibility input method, or an already-authorized ADB connection
for initial setup. Sideloaded apps may also require **Allow restricted settings**
from their app-info menu. No phone interaction is needed to create/build this
repository, but initial device authorization is still necessary.

Double-press Volume Up to listen. Commands include `back`, `home`, `notifications`,
`recent apps`, `scroll down`, `scroll up`, `type hello`, and `click Settings`.
Say `wake word on` for repeated speech recognition; prefix commands with
`Jarvis`, `hey bhai`, `sun bhai`, or `phone`. Say `wake word off` or
`stop listening` to stop. This is a repeated Android speech-recognition loop,
not a dedicated offline wake-word engine; battery use, background microphone
restrictions, recognition availability, and key filtering vary by device.
A compatible speech recognition service and text-to-speech engine are required.
Speech recognition may use the provider's network service.

## Optional local AI

Complex commands send the command and up to 3500 characters of visible screen
labels to `http://127.0.0.1:11434/api/generate`, using the Ollama model
`qwen2.5:1.5b`. **The APK does not bundle Ollama or model weights.** That endpoint
means the Android device itself, not your computer or GitHub runner. Run a
compatible server on-device, or with an already-authorized USB ADB connection
use `adb reverse tcp:11434 tcp:11434` to reach a computer's local Ollama server.
If unavailable, the app falls back to matching screen text; fast commands do
not need the LLM. Screen text can be sensitive; enable the service and connect
an LLM only if you trust the software and endpoint. LLM output can be wrong and
is executed without a confirmation screen.

## Compatibility notes

HTML entities in the supplied source were decoded into valid XML/Kotlin. The
obsolete manifest `package` attribute was omitted because AGP 8 uses Gradle
`namespace`; speech recognition and TTS package-visibility queries were added
for modern Android. The workflow uses `**` instead of `*` so pushes to branch
names containing slashes also trigger builds.
