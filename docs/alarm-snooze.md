# Snooze and ringing presentation

## Five-minute snooze policy

Wake Up, Samurai schedules snooze with `AlarmManager.setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)` at the monotonic action time plus five minutes. It persists the occurrence ID, revision, elapsed deadline, boot count, and a wall-clock recovery instant as `SNOOZED`.

Within the same boot, device time and time-zone changes do not shift the five-minute delay; the displayed wall-clock time is refreshed from the remaining monotonic duration. After a reboot, the elapsed counter is invalid, so reconciliation uses the persisted wall-clock recovery instant and re-establishes a new elapsed deadline. An overdue snooze is expired rather than played late. The scheduled PendingIntent survives ordinary process recreation, while reboot/update/access changes are handled by [schedule recovery](alarm-recovery.md).

The platform occurrence is created first, then Room atomically moves the exact `RINGING` occurrence into the separate snooze slot. If that compare-and-set loses to Dismiss, timeout, or another Snooze, the newly created platform entry is cancelled. A successful replacement stops the current player/service; a failed or blocked replacement leaves the current ringing session active and dismissible. For recurring alarms, the next regular weekday remains scheduled independently; see [recurring alarms](alarm-recurring.md).

## Presentation and access

- The foreground alarm notification uses the high-importance `ringing_alarms_v2` channel, `CATEGORY_ALARM`, an occurrence-specific content/full-screen PendingIntent, and silent notification audio. Local service playback remains the sole alarm sound.
- The app checks runtime notification permission, app-level notification enablement, and channel importance separately. It creates the channel only if absent and directs the user to the applicable app/channel settings; it never rewrites user channel choices.
- `USE_FULL_SCREEN_INTENT` is declared and `canUseFullScreenIntent()` is checked on Android 14+. When access is missing, Android retains notification/heads-up fallback; the service does not call unrestricted `startActivity()`.
- `RingingActivity` uses `showWhenLocked` and `turnScreenOn`; it does not dismiss keyguard or bypass a PIN.

Exact-alarm access, runtime notification permission/channel availability, and full-screen access are checked separately and guided only in relevant alarm interactions. These conclusions follow the official Android documentation for [AlarmManager](https://developer.android.com/reference/android/app/AlarmManager), [notification channels](https://developer.android.com/develop/ui/compose/notifications/channels), and [full-screen intents](https://developer.android.com/reference/android/app/Notification.Builder#setFullScreenIntent(android.app.PendingIntent,boolean)).
