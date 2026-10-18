# Phase 7 manual acceptance — pending device verification

These are manual checks for the owner after Steps 7A–7C. JVM tests and APK/lint checks do **not** establish device delivery. Use alarms a few minutes ahead through the normal app UI; no private-file deletion or device-clock change is needed.

## Through the normal app UI

1. Create and enable a weekday alarm for the next available day. Let it ring, tap **Dismiss**, and confirm the next selected regular occurrence remains shown and enabled.
2. On a recurring alarm, tap **Snooze 5 min**. Confirm sound stops, it rings again, and the next regular weekday remains unchanged. Check **Cancel snooze** on a separate run.
3. While a regular occurrence or snooze is pending, disable, delete, or edit and Save the alarm. Confirm only the intended future occurrences remain. Re-enable explicitly where appropriate.
4. Reboot, unlock once, and open the app. Confirm future enabled alarms and pending valid snoozes are restored; no completed one-shot rings again. Alarms due before first unlock are outside current support.
5. If exact-alarm access is absent, use the in-app guidance to open system settings, restore access, then return to the app. Confirm the blocked state clears and a future occurrence is shown. Notification and full-screen access are separate limitations.
6. With required access granted, let an alarm ring while locked with the display off and while offline. Check local sound, the Alarm Screen or notification fallback, and both **Dismiss** and **Snooze** notification actions. An old notification must not affect a later ring.
7. Arrange two alarms for the same minute. Only the first accepted occurrence should sound; the second is skipped. If recurring, its next regular date must remain scheduled.
8. Ordinary process death may restart a still-valid ring only within its original five-minute window; force-stop is different and prevents normal alarm delivery until the app is launched again. Do not force-stop merely to accept Phase 7. Ringing after process death, full-screen presentation, and manufacturer-specific background behavior remain device checks.

## Optional developer diagnostics (not required for UI acceptance)

- Inspect the alarm and service logs if a delivery fails; record the approximate scheduled and actual times, and whether the app was locked, offline, or recently restarted. Do not include message text or audio contents.
- For a failure, note separately whether exact-alarm access, app notifications, ringing channel, or full-screen intent access was unavailable. These do not all block sound in the same way.
- A debug build can verify stale/duplicate broadcasts and process recreation with controlled tooling, but these diagnostics are not requested for this milestone and must not modify the phone's clock or erase app data.
