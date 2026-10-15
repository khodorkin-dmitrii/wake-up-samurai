# Wake Up, Samurai

> **We have a day to live.**

Wake Up, Samurai is a native Android alarm clock for messages that actually
mean something to you. In the evening, leave a personal reason to wake up, a
supportive note, or a joke for your morning self. At alarm time, the completed
audio is played locally so ringing does not depend on an internet connection.

The initial experience is intentionally focused: create one message from text
or record it with the microphone, preview it, choose a time and repeat days,
then save and enable the alarm.

## Status

Steps 1–3 provide the Compose foundation, experimental direct Edge TTS generation
and Media3 preview, plus Room-backed alarm definitions with stable IDs, reusable
audio-asset metadata, time/repeat editing, and next-occurrence calculation.

Step 5A adds the first bounded execution path: a newly saved, enabled one-shot
alarm is scheduled with `AlarmManager.setAlarmClock()`, then rings from its local
audio in a foreground service. Recurring execution, reboot recovery, snooze and
full-screen UI remain deliberately out of scope. Completed audio lives under
durable app-owned storage and is not removed when a preview is replaced or an
alarm is deleted.

## Foundation stack and SDK choices

- `minSdk 24`: keeps broad device coverage while remaining above Hilt's current
  minimum. No Step 1 API requires a higher minimum.
- `compileSdk 37` and `targetSdk 37`: required by the current stable Compose,
  Lifecycle, and Navigation 3 releases selected for the foundation.
- AGP `9.1.1`, Gradle `9.3.1`, and Kotlin `2.2.10` are retained from the
  generated project as a known-compatible build toolchain.
- Compose is aligned by the stable BOM `2026.09.00`; Material 3 uses the BOM
  rather than an independently pinned version.
- Navigation 3 `1.2.0` uses serializable typed `NavKey` objects and a saveable
  back stack. Navigation 2 route strings are not used.
- Lifecycle `2.11.0`, Activity `1.13.0`, Coroutines `1.11.0`, Hilt/Dagger
  `2.60.1`, and KSP `2.3.12` provide ViewModel, immutable `StateFlow`, and
  constructor-injection foundations.
- The project remains a single `app` module. Current code is separated into
  screen, navigation, and theme packages; data/domain packages will be added
  only when their milestones introduce real responsibilities.

Room 2.8.5 stores alarm definitions and audio metadata with an exported schema.
Media3 and OkHttp support the bounded Step 2 preview/generation experiment.
Core-library desugaring 2.1.5 makes the pure `java.time` schedule policy
available down to the existing `minSdk 24` without raising the SDK floor.
DataStore remains absent because this milestone does not need it. Android alarm
scheduling uses the framework `AlarmManager`; it introduces no additional library.

## One-shot alarm access policy

The app uses `setAlarmClock()` for this alarm-clock use case. On Android 12+
it checks `AlarmManager.canScheduleExactAlarms()` and, when needed, opens the
system **Alarms & reminders** special-access screen for
`SCHEDULE_EXACT_ALARM`. `setAlarmClock()` does not bypass that access check.

On Android 13+ the app also asks for `POST_NOTIFICATIONS` when the user enables
an alarm: the foreground service can start without that runtime permission, but
the user needs the visible ongoing notification and its **Dismiss** action for
this milestone. A denial leaves the configured alarm in a visible blocked state;
the app never claims it was scheduled. Android 14+ media playback requirements
are declared with `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and
`foregroundServiceType="mediaPlayback"`.

The foreground service loops the selected readable local file for at most five
minutes, or uses a bundled short fallback tone if the file cannot be read. A
delivery carries an occurrence UUID and revision, so stale/repeated intents are
rejected. While one alarm rings, a second concurrent one is marked skipped;
this temporary policy avoids competing players and will be revisited with
multi-alarm handling. The source of truth is still Room; system alarms are
derived only after an explicit Save/enable action. Existing saved alarms are not
activated by the Step 5A upgrade.

## Roadmap

- [x] **Foundation** — project setup, theme, Navigation 3, dependency injection,
   testing baseline, and an empty alarm list.
- [x] **Alarm editor** — create and edit an alarm, choose time and repeat days,
   preview the next occurrence, and protect unsaved changes.
- [x] **Local audio** — run an Edge TTS feasibility spike, generate and validate a
   durable local track, record voice messages, and preview either source.
- [ ] **Persistence and scheduling** — store alarms with stable IDs, schedule the
   next exact occurrence, and reconcile schedules after edits and system events.
- [ ] **Ringing** — reliable foreground playback, notification/full-screen entry,
   dismiss and idempotent snooze, plus a bundled fallback sound.
- [ ] **Readiness and hardening** — contextual permission guidance, recovery from
   blocked capabilities, stale-delivery protection, and device-level checks.

Deferred ideas such as imported audio, voice selection, transcription,
multiple messages per alarm, and a remote audio pool are deliberately outside
the initial milestones.
