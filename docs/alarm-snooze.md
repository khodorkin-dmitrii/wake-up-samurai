# Snooze and ringing presentation

## Five-minute snooze policy

Wake Up, Samurai schedules snooze with the same `AlarmManager.setAlarmClock()` path as an ordinary one-shot alarm. At the moment the user taps **Snooze 5 min**, the app calculates an exact wall-clock trigger as `now + five minutes` and persists a new occurrence ID, revision and trigger instant as `SNOOZED`.

`setAlarmClock()` is an `RTC_WAKEUP` API, so this milestone deliberately uses wall-clock time; it does not claim elapsed-realtime semantics. The new occurrence survives ordinary process recreation because its identity and pending time are in Room. A device time change can move the wall-clock instant; broader time-change/reboot reconciliation remains deferred.

The platform occurrence is created first, then Room atomically replaces the exact `RINGING` occurrence. If that compare-and-set loses to Dismiss, timeout, or another Snooze, the newly created platform entry is cancelled. A successful replacement stops the current player/service; a failed or blocked replacement leaves the current ringing session active and dismissible.

## Presentation and access

- The foreground alarm notification uses the high-importance `ringing_alarms_v2` channel, `CATEGORY_ALARM`, an occurrence-specific content/full-screen PendingIntent, and silent notification audio. Local service playback remains the sole alarm sound.
- The app checks runtime notification permission, app-level notification enablement, and channel importance separately. It creates the channel only if absent and directs the user to the applicable app/channel settings; it never rewrites user channel choices.
- `USE_FULL_SCREEN_INTENT` is declared and `canUseFullScreenIntent()` is checked on Android 14+. When access is missing, Android retains notification/heads-up fallback; the service does not call unrestricted `startActivity()`.
- `RingingActivity` uses `showWhenLocked` and `turnScreenOn`; it does not dismiss keyguard or bypass a PIN.

Exact-alarm access, runtime notification permission/channel availability, and full-screen access are checked separately and guided only in relevant alarm interactions. These conclusions follow the official Android documentation for [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager), [notification channels](https://developer.android.com/develop/ui/compose/notifications/channels), and [full-screen intents](https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,boolean)).
