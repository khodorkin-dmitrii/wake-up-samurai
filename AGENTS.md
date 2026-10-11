# AGENTS.md

## Project: Wake Up, Samurai

- Repository: `wake-up-samurai`.
- Tagline: **We have a day to live.**
- Product: a native Android alarm clock that plays a personal reason to wake up, a supportive message, or a joke.
- Core interaction: create an audio message, preview it, choose a time and repeat days, and hear it when the alarm rings.

The evening user leaves a message for their morning self. Playing a game, taking a walk, or enjoying a quiet hour are valid motivations. Keep the tone personal, friendly, and humorous; avoid generic productivity preaching.

This is also an Android portfolio project. Favor reliable behavior, clear ownership, maintainability, and idiomatic Android APIs over unnecessary abstraction.

## Companion playbook

Read the project-local [ANDROID_PROJECT_STARTER_PLAYBOOK.md](./.agents/skills/android-project-starter/references/ANDROID_PROJECT_STARTER_PLAYBOOK.md) before project setup, skills installation, navigation work, or substantial UI work.

This file defines Wake Up, Samurai's product and engineering rules. The playbook lives with the `android-project-starter` skill and supplies setup workflows, pinned Handbook Audio code references, reusable UI patterns, and regression checks. Its Handbook Audio examples are references, not requirements for this app.

- Adapt relevant theme, card, audio-control, navigation, and inset patterns.
- Do not copy Handbook Audio's catalog, playlists, transcripts, package name, or streaming-first architecture.
- Do not copy its dependency versions without checking compatibility.
- An explicit current user instruction takes precedence over either document. Explain material conflicts and proceed within the authorized scope; do not ask for routine implementation approval.
- Keep `AGENTS.md` in the repository root and the playbook with the `android-project-starter` skill. Do not ignore either unless the user explicitly requests local-only instructions.

## Initial scope

The initial product supports:

- an initially empty list of alarms;
- adding, editing, deleting, enabling, and disabling alarms;
- creating a local audio track from entered text using an experimental Edge TTS integration;
- recording a local voice message;
- previewing the resulting audio before saving;
- choosing a time and optional repeat days;
- alarm playback, dismissal, and snooze;
- contextual permission and system-access guidance;
- persistent alarms and recovery of their schedules.

Start with **one alarm referring to one completed local audio asset**. Internal IDs must remain stable independently of titles, text, paths, or list positions.

Deferred features:

- importing an audio file from the device;
- voice selection for synthesized speech;
- transcription of voice recordings;
- multiple messages per alarm and random/sequential selection;
- a small remote MP3 pool hosted on GitHub, with advance download and local storage;
- content categories such as personal reasons, support, and absurd wisdom.

Do not implement deferred features ahead of the requested milestone. Do not add accounts, subscriptions, analytics, a habit tracker, wake-up puzzles, or a speculative settings system.

## Screens and interaction contracts

### Alarm list

- Show time, repeat summary, a short label or message excerpt, and an enable switch.
- Provide an empty state and an obvious add action.
- Tapping the card opens editing; the switch only changes activation.
- Show actionable scheduling problems rather than silently presenting a blocked alarm as ready.
- Avoid unstable card heights, duplicate destinations, and destructive actions hidden behind ambiguous gestures.

### Create / edit alarm

Put the message before the schedule. The central prompt is **"What are we waking up for?"** (Russian: "Ради чего просыпаемся?").

Audio creation initially has two modes: **From text** and **Record voice**. Both produce the same preview card with playback controls, duration when known, and a regenerate/re-record action.

Text-to-speech flow:

- enter text;
- explicitly request generation;
- show progress without blocking the main thread;
- show the completed track or an actionable error with retry;
- mark an existing track as outdated when text or generation parameters change;
- prevent stale asynchronous results from replacing a newer draft.

Never imply that edited text is already reflected in the saved audio. Do not silently replace a valid track with an incomplete generation result.

Recording flow: request microphone access when needed, start, show elapsed time, stop, preview, then keep or re-record. Handle interruption and cancellation without retaining a broken file. Recording is initially a foreground editor interaction; background recording is outside scope.

Schedule controls:

- large readable time opening a standard Material time picker with dial/input support;
- seven day-of-week toggles;
- no selected days means one occurrence at the next matching local time;
- an explicit next-occurrence summary, such as "Tomorrow at 07:00";
- a clear save action and protection against accidental loss of an edited draft.

Saving a draft, enabling an alarm, and successfully scheduling it are distinct operations. A blocked schedule must be visible and recoverable. Only enable a personal-audio alarm once its referenced track is complete and readable.

### Ringing experience

- Show large time, the alarm label/message when available, and clear Dismiss/Snooze actions.
- Keep controls easy to use while sleepy, with sufficient touch targets and contrast.
- Playback must not depend on the ringing Activity remaining visible.
- Opening the ringing UI from a notification must resolve the actual active occurrence.
- Repeated actions must not create duplicate snoozes or affect another occurrence.
- Define clip repetition, snooze duration, and maximum ringing duration in the relevant milestone; do not assume that a short clip ending means the alarm was dismissed.

### Permissions and readiness

Ask for microphone permission on the first recording attempt, and guide system access when it becomes necessary to enable or present an alarm. Avoid a blanket permission wall on launch.

Treat microphone access, notification permission/channel state, exact-alarm capability, and full-screen intent capability as separate conditions. Recheck relevant state after returning from system settings. Explain the effect of a missing capability and provide a direct recovery action.

Use inline status or a small guidance sheet initially. Add a dedicated readiness screen only if the actual flow needs it. Do not promise that every missing notification/UI capability prevents sound; describe the real limitation.

## Architecture and stack

Use Kotlin, Jetpack Compose, Material 3, AndroidX Navigation 3, ViewModel, immutable `StateFlow<UiState>`, Coroutines/Flow, and Hilt.

- Use conventional MVVM and unidirectional state flow.
- Add use cases only for meaningful shared logic or coordination.
- Use constructor injection and interfaces at real architectural boundaries.
- Keep domain models independent from Compose, Room entities, network DTOs, and player internals.
- UI emits explicit actions; repositories/platform abstractions own data and system integration.
- Use typed Navigation 3 keys and stable IDs. Do not introduce Navigation 2 route-string patterns.
- Guard repeated taps and back actions using the playbook's navigation principles.

Start with one app module and clear packages, for example `data`, `domain`, `alarm`, `audio`, `tts`, `ui`, `navigation`, and `di`. Do not create modules solely to demonstrate modularization.

Persistence defaults:

- Room for structured alarm definitions, audio metadata, and occurrence state that must survive process death;
- DataStore for lightweight preferences such as theme;
- app-owned files for completed recordings and synthesized audio; no audio blobs in the database.

Use Media3 for local audio playback behind an abstraction. Alarm execution owns ringing playback outside screen ViewModels; editor preview has an explicit lifecycle and must not compete with ringing audio. Media-session controls are optional and must not accidentally become a way to leave an active alarm silently paused.

Add OkHttp and Kotlin Serialization when networking needs them; add Retrofit only when an actual HTTP API benefits from it. A TTS experiment does not itself establish a conventional REST backend.

Select compatible current stable dependencies during setup, document SDK choices, and use the project's version catalog. Do not invent package/application IDs or copy those of Handbook Audio.

## Scheduling and alarm execution

The local persisted alarm definition is the source of truth; Android's scheduled intents are a derived state.

- Use `AlarmManager`, with `setAlarmClock()` as the default API to evaluate for this user-facing alarm clock.
- Check the applicable exact-alarm capability and select a permission strategy appropriate to the supported SDKs. Do not assume `setAlarmClock()` bypasses permissions.
- Schedule the next exact occurrence of a recurring alarm instead of relying on an inexact repeating alarm.
- Do not use WorkManager, coroutine delays, UI timers, or a permanently running service as the wake-up scheduler.
- Keep calendar-time calculation separate from Android scheduling calls and inject a clock/time-zone provider for tests.
- Use local wall-clock time and repeat days for normal alarms. Explicitly define daylight-saving gaps/overlaps, time-zone changes, manual clock changes, and missed-occurrence behavior.
- Snooze is a separate occurrence; document its elapsed-time versus wall-clock policy.
- Reconcile persisted definitions with system scheduling after relevant lifecycle/system events and edits. Handle reboot, app replacement, time/time-zone changes, and applicable access changes without duplicate scheduling.
- Reject stale deliveries after disabling, deleting, or editing an alarm. Identify both the alarm and its scheduled occurrence/revision.
- Decide explicitly how simultaneous alarms are handled; never start competing audio players accidentally.

Keep receivers short. Use a platform-compliant foreground execution path for sustained ringing, with the required service type, notification, and prompt startup behavior verified for the selected target SDK. Release player resources and any bounded wake locks on all terminal paths.

Present ringing through an alarm notification and supported full-screen intent flow. Handle unavailable full-screen access with a notification-based alternative; do not assume background `startActivity()` is unrestricted.

Ordinary process death and force-stop are different. Do not claim alarms work while the device is powered off or after force-stop. Before-first-unlock support requires an explicit Direct Boot/storage decision; do not imply it is covered by ordinary reboot recovery.

Verify current platform behavior against official Android documentation when implementing the relevant milestone:

- [Schedule alarms](https://developer.android.com/develop/background-work/services/alarms)
- [Android 14 full-screen intent restrictions](https://developer.android.com/about/versions/14/behavior-changes-14#secure-fsi)

These references are starting points, not a substitute for checking the project's target SDK and newer behavior changes.

## Audio assets and TTS experiment

**Alarm playback must be entirely local.** Synthesis or downloads happen before activation, never when the alarm fires. Provide a bundled fallback sound for missing, corrupt, or unplayable personal audio.

- Keep completed alarm audio in durable app-owned storage, not an evictable cache directory.
- Write temporary output, validate it, and only then promote it to a completed asset.
- Preserve the old saved asset while a replacement is being generated or recorded.
- Clean up abandoned temporary files and unreferenced assets without deleting files still used by a saved alarm or active occurrence.
- Distinguish original text, generation parameters, and the completed asset's metadata.
- Establish alarm audio attributes, focus, volume behavior, vibration, and interruption policy deliberately. Do not silently change global volume or bypass Do Not Disturb.

The requested synthesis option is Edge TTS. The known [`edge-tts`](https://github.com/rany2/edge-tts) tool is a Python client for an online service, not an Android SDK or an app backend already available to this project.

Perform an early, small feasibility spike for **Android text → generation → valid local file → preview** before committing to an integration design. Report the tested method, latency, failures, and any infrastructure/runtime requirement. Do not silently introduce a Python runtime, create a server, or substitute another provider. A fake provider may support editor development but must be identified as a development placeholder.

Keep synthesis behind a small `SpeechGeneration` boundary. Support timeout, cancellation, retry, and result association with the correct draft revision. Explain to the user that entered text goes to an online service when generating speech; do not log personal text or recordings by default.

For the later remote pool, prefer a small versioned manifest with stable track IDs and HTTPS asset references. Download and validate in advance; any file selected for an enabled alarm must remain available even if ordinary download cache is evicted. Random playback must have a local fallback and never depend on internet availability at ringing time.

## UI reuse and skills

Use the companion playbook as the implementation reference for theme, insets, cards, audio preview controls, and navigation guards. Adapt the patterns to this app's identity and domain.

- Support System/Light/Dark themes and theme-aware system bars.
- Assign one owner to each inset; cover editor keyboard, list bottom padding, and ringing controls.
- Keep the root/window background consistent to avoid navigation/theme flashes.
- Use accessible labels, large controls, readable font scaling, and clear loading/error states.
- Begin with ordinary theme switching. Ambient backgrounds and circular reveal are optional later polish.
- Do not add a global MiniPlayer, transcript panel, playback-speed selector, or playlist UI merely because Handbook Audio has them.

During foundation setup, follow the playbook to verify Android CLI availability and install the relevant project-local skills: `android-cli`, `navigation-3`, `testing-setup`, and `edge-to-edge`. Verify current IDs and actual installation paths; do not use `--all` or edit official skill folders manually. Read installed `SKILL.md` files before applying their workflows.

The custom launcher-icon skill is separate and relevant only to icon work. Additional skills need a concrete task. Never let a generic skill override the user's explicit project instructions.

## Milestones and validation

Work through the separately agreed roadmap one milestone at a time. This document describes the intended product, not authorization to implement everything at once. If no current milestone is established, start with foundation setup and identify the next bounded task.

Test non-trivial behavior, especially:

- next-occurrence calculation, midnight/week rollover, DST and time-zone policy;
- scheduling reconciliation, stale delivery rejection, enable/disable/edit/delete;
- one-shot completion, snooze/dismiss idempotency, simultaneous occurrences;
- persistence recovery and missing-file fallback;
- generation errors/cancellation/stale results and editor dirty state;
- recording state transitions, preview ownership, permission/readiness recovery;
- navigation guards and card-versus-switch interaction.

Prefer targeted JVM tests and coroutine testing utilities. Use instrumentation only for behavior that needs Android integration. Do not add tests that merely mirror implementation, or default to screenshot/golden suites.

After meaningful code changes run the relevant checks, normally:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
git diff --check
```

On Windows use `gradlew.bat` / `./gradlew.bat` as appropriate. For documentation-only edits, validate the text and local references and run `git diff --check`; do not rebuild without a reason.

JVM tests and successful builds do not prove alarm delivery. Record device checks for a locked screen, background/process death, reboot recovery, missing access, repeated actions, and offline playback when those capabilities are implemented. Include portrait/landscape, light/dark, font scaling, keyboard, and system-navigation checks for affected UI. Report what was actually tested and any remaining limitation.

## Working rules and device installation

- Respond to the user in Russian for this project.
- Do not run visual tests on a device or emulator unless the user explicitly requests them.
- Inspect existing code and preserve established conventions.
- Keep changes focused and avoid speculative abstractions or unrelated refactors.
- Do not silently replace an agreed architectural choice.
- Do not commit, push, publish, or create a release unless requested.
- Keep secrets, signing keys, credentials, recordings, and personal generated audio out of Git.
- Report the implemented behavior, validation, decisions, and material limitations after each milestone.

When deploying to the connected Samsung device, do not use `gradlew installDebug`. Build the APK, then install explicitly for the primary Android user:

```powershell
adb install --user 0 -r -t app\build\outputs\apk\debug\app-debug.apk
```

Confirm the primary user ID with `adb shell pm list users` if needed, and adapt the APK path to the actual build variant. Do not install or enable the app for Samsung `DUAL_APP` or other secondary users/profiles. Do not uninstall, disable, or otherwise modify secondary-profile installations unless explicitly requested.
