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

The project is at the foundation stage. The Android application shell and
project conventions are in place; alarm, audio, persistence, and scheduling
features are not implemented yet.

## Planned stack

- Kotlin, Jetpack Compose, and Material 3
- AndroidX Navigation 3 with typed navigation keys
- ViewModel, immutable `StateFlow`, Coroutines, and Hilt
- Room for alarm and occurrence data
- Media3 for local audio preview and ringing playback
- `AlarmManager` for exact user-facing alarm occurrences

## Roadmap

1. **Foundation** — project setup, theme, Navigation 3, dependency injection,
   testing baseline, and an empty alarm list.
2. **Alarm editor** — create and edit an alarm, choose time and repeat days,
   preview the next occurrence, and protect unsaved changes.
3. **Local audio** — run an Edge TTS feasibility spike, generate and validate a
   durable local track, record voice messages, and preview either source.
4. **Persistence and scheduling** — store alarms with stable IDs, schedule the
   next exact occurrence, and reconcile schedules after edits and system events.
5. **Ringing** — reliable foreground playback, notification/full-screen entry,
   dismiss and idempotent snooze, plus a bundled fallback sound.
6. **Readiness and hardening** — contextual permission guidance, recovery from
   blocked capabilities, stale-delivery protection, and device-level checks.

Deferred ideas such as imported audio, voice selection, transcription,
multiple messages per alarm, and a remote audio pool are deliberately outside
the initial milestones.
