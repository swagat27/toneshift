# ToneShift

Rewrite text inline in any Android app, without giving up Gboard.

ToneShift is a Flutter + Kotlin hybrid app that adds AI rewriting to the keyboard you already use. Highlight text anywhere and pick a tone from the popup menu, or tap a floating pill while typing. It uses your own Gemini API key, stored encrypted on your phone only.

## Download the APK

Go to the Releases page of this repo and download `app-release.apk` from the latest build, or download the `toneshift-release-apk` artifact from the latest GitHub Actions run (Actions tab, latest "Build ToneShift APK" run, Artifacts section).

Install the APK on your phone (allow "Install unknown apps" for your browser or Files app when Android asks).

## Setup (2 minutes)

1. Get a free Gemini API key: open Google AI Studio (aistudio.google.com), sign in, Create API key. Copy it.
2. Open ToneShift, paste the key, tap Save key, then Test connection.
3. Popup menu (no extra permission needed): go to WhatsApp, Notes, Gmail, anywhere. Type something, highlight it, and look for ToneShift in the text selection popup. Tap it, pick Shorten, Formal, Grammar Fix, or any preset. The highlighted text is replaced in place.
4. Floating pill (optional, one permission): in ToneShift tap "Enable floating pill", find ToneShift in Settings, Accessibility, Installed apps, and turn it on.
   - Android 13/14 note for sideloaded apps: if the Accessibility toggle is greyed out, go to Settings, Apps, ToneShift, tap the 3 dots at the top right, tap "Allow restricted settings", then go back to Accessibility and enable it.
   - Now a small "TS Rewrite" pill appears when a text field is focused. Drag it to move it. Tap it, pick a tone, and the whole field is rewritten. Tap Undo to restore your original.

## The custom instruction trick

End any draft with `//` and an instruction, in either flow:

- `cant come today sick // formal excuse to professor`
- `kal meeting hai bhai bhoolna mat // make it polite and professional`
- `project is delayed by 2 days because vendor is late // polite email to client, add subject line`

Everything before `//` is the draft, everything after is the instruction for that one rewrite.

## Presets

Built in: Shorten, Formal, Grammar Fix, Professional Bullet, Polite No.

Open ToneShift to edit any preset's instruction, add your own (for example Hinglish, LinkedIn Post, WhatsApp Casual), or reset to defaults. The popup menu and the pill use your edited presets automatically.

## How it works

Architecture, 3 parts, no custom keyboard:

1. `ProcessTextActivity` (Kotlin): registers for `android.intent.action.PROCESS_TEXT`. Android shows it in every app's selection menu and replaces the highlighted text with the result we return.
2. `ToneShiftAccessibilityService` (Kotlin): watches for a focused editable field, shows a draggable pill using `TYPE_ACCESSIBILITY_OVERLAY` (so no draw-over-apps permission is needed), reads the field only when you tap a tone, and writes the rewrite back with `ACTION_SET_TEXT`. Undo keeps the last original in memory only.
3. Flutter dashboard (`lib/main.dart`): API key, presets, setup status, and usage log (mode and character counts only, never your text). It talks to the native layer over a MethodChannel (`com.toneshift/native`), and the native layer owns storage in `EncryptedSharedPreferences` (`SecureStore.kt`), so background pieces never need to wake Flutter.

Gemini call (`GeminiClient.kt`): model `gemini-2.5-flash`, temperature 0.25, max 500 output tokens, and a strict system instruction: output only the rewritten text, no greetings, no explanations, no quotes, no markdown, preserve names, numbers, dates, and facts, preserve Hinglish unless Formal is asked for.

## Build it yourself

Prerequisites: Flutter SDK 3.24+, Android Studio or Android SDK, Java 17.

```bash
git clone https://github.com/swagat27/toneshift.git
cd toneshift
# First time only: generate the standard Android scaffolding (gradle wrapper, launcher icons)
# Back up the custom files first, flutter create can overwrite them:
cp -r android/app/src /tmp/toneshift-src-backup
cp android/app/build.gradle /tmp/app-build.gradle
flutter create --platforms=android --org com.toneshift --project-name toneshift .
cp -r /tmp/toneshift-src-backup/* android/app/src/
cp /tmp/app-build.gradle android/app/build.gradle
flutter pub get
flutter build apk --release
# APK at: build/app/outputs/flutter-apk/app-release.apk
```

Or just push to `main` and let GitHub Actions build it: the workflow in `.github/workflows/build-apk.yml` does the scaffolding step automatically and publishes a Release with the APK.

## Privacy and security

- Your API key is stored only in encrypted storage on your phone. It is never in this repo, never sent anywhere except Google Gemini's API as the `x-goog-api-key` header.
- Text is sent to Gemini only when you tap a tone button. Nothing is read in the background, no keystrokes are logged.
- The usage log stores only the preset name, input/output character counts, time, and success or failure. Never the text itself.
- This is a personal sideloaded app, not a Play Store app. Review the Accessibility permission screen before enabling the pill, it is your phone and your call.

## Troubleshooting

- ToneShift does not appear in the popup menu: highlight text in a normal editable field (Notes or WhatsApp), some apps like Chrome web pages and PDFs use their own selection menu. Try a different app first.
- Pill does not appear: check ToneShift is ON in Settings, Accessibility, Installed apps, then tap into a text field. Some banking apps block accessibility overlays.
- "API key is invalid (403)": create a fresh key in Google AI Studio, make sure you copied the whole key, save and test again in ToneShift.
- "Rate limit (429)": free Gemini keys have per-minute limits, wait 30 seconds and retry.
- Rewrite changed a name or date: it should not, the system prompt forbids it, but always give the result a quick glance before sending, same as any AI tool.

## Project structure

```
lib/main.dart                                                     Flutter dashboard
android/app/src/main/AndroidManifest.xml                          Both hooks registered here
android/app/src/main/kotlin/com/toneshift/toneshift/
  MainActivity.kt                                                 MethodChannel bridge to Flutter
  SecureStore.kt                                                  Encrypted key, presets, usage log
  GeminiClient.kt                                                 Gemini 2.5 Flash call, // parsing, cleanup
  ProcessTextActivity.kt                                          Hook 1: selection popup menu
  ToneShiftAccessibilityService.kt                                Hook 2: floating pill, Undo
android/app/src/main/res/layout/activity_process_text.xml         Popup sheet UI
android/app/src/main/res/layout/floating_pill.xml                 Pill UI
android/app/src/main/res/xml/accessibility_service_config.xml   Service config
.github/workflows/build-apk.yml                                   Automatic APK builds + Releases
```

Made for personal use by Swagat Baghel.
