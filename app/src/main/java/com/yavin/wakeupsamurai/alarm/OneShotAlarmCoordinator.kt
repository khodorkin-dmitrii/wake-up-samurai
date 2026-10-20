package com.yavin.wakeupsamurai.alarm

import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.DeliveryClaim
import com.yavin.wakeupsamurai.domain.alarm.NextOccurrenceCalculator
import com.yavin.wakeupsamurai.domain.alarm.SystemTimeProvider
import com.yavin.wakeupsamurai.domain.alarm.TimeProvider
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first

sealed interface AlarmSaveResult {
    data class Scheduled(val alarm: Alarm) : AlarmSaveResult
    data class BlockedExactAlarmAccess(val alarm: Alarm) : AlarmSaveResult
    data class BlockedNotificationPermission(val alarm: Alarm) : AlarmSaveResult
    data class BlockedNotificationsDisabled(val alarm: Alarm) : AlarmSaveResult
    data class BlockedAlarmChannel(val alarm: Alarm) : AlarmSaveResult
    data class SavedWithoutSchedule(val alarm: Alarm) : AlarmSaveResult
    data class Failed(val message: String) : AlarmSaveResult
}

enum class DeliveryDisposition { RING, SKIPPED, STALE }

enum class ReconcileReason { FOREGROUND, BOOT, PACKAGE_REPLACED, CLOCK_CHANGED, ACCESS_GRANTED }

data class RecoverableRinging(val occurrence: RingingOccurrence, val remainingMillis: Long)

/** Persists intent before deriving exact platform occurrences. Regular and snooze entries have separate slots. */
@Singleton
class OneShotAlarmCoordinator @Inject constructor(
    private val repository: AlarmRepository,
    private val scheduler: OneShotAlarmScheduler,
    private val timeProvider: TimeProvider = SystemTimeProvider(),
    private val calculator: NextOccurrenceCalculator = NextOccurrenceCalculator(),
    private val bootClock: AlarmBootClock,
) {
    private val mutex = Mutex()
    private val unverifiedBootClaims = mutableSetOf<RingingOccurrence>()

    suspend fun save(alarm: Alarm): AlarmSaveResult = mutex.withLock { saveLocked(alarm) }

    private suspend fun saveLocked(alarm: Alarm): AlarmSaveResult {
        val previous = repository.get(alarm.id)?.alarm
        val revision = maxOf(
            previous?.scheduleRevision ?: 0L,
            previous?.snoozeRevision ?: 0L,
            previous?.ringingRevision ?: 0L,
        ) + 1L
        val now = timeProvider.now()
        val oneShotAt = if (alarm.configuredEnabled && alarm.repeatDays.isEmpty()) {
            alarm.resolvedOneShotAt?.takeIf { it.isAfter(now) }
                ?: calculator.oneShotStrictlyAfter(now, timeProvider.zoneId(), alarm.time)
        } else null
        val reset = alarm.copy(
            resolvedOneShotAt = if (alarm.repeatDays.isEmpty()) oneShotAt ?: alarm.resolvedOneShotAt else null,
            scheduleRevision = revision,
            scheduledOccurrenceId = null,
            scheduledAt = null,
            snoozeOccurrenceId = null,
            snoozeRevision = null,
            snoozedAt = null,
            ringingOccurrenceId = null,
            ringingRevision = null,
            regularScheduledBootId = null,
            snoozeScheduledBootId = null,
            snoozeElapsedDeadlineMillis = null,
            ringingBootId = null,
            ringingDeadlineElapsedMillis = null,
            lastRegularDeliveredAt = null,
            lastRingingFailure = null,
            // Unlike legacy NOT_SCHEDULED rules, an interrupted explicit Save needs recovery.
            schedulingState = if (alarm.configuredEnabled) AlarmSchedulingState.FAILED else AlarmSchedulingState.NOT_SCHEDULED,
        )
        val persistedReset = repository.save(reset)
        previous?.nextOccurrence()?.let(scheduler::cancel)
        previous?.pendingSnooze()?.let(scheduler::cancel)

        if (!persistedReset.configuredEnabled) return AlarmSaveResult.SavedWithoutSchedule(persistedReset)
        when (scheduler.access()) {
            SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED -> return blocked(persistedReset, AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS)
            SchedulingAccess.NOTIFICATION_PERMISSION_REQUIRED -> return blocked(persistedReset, AlarmSchedulingState.BLOCKED_NOTIFICATION_PERMISSION)
            SchedulingAccess.NOTIFICATIONS_DISABLED -> return blocked(persistedReset, AlarmSchedulingState.BLOCKED_NOTIFICATION_PERMISSION, notificationsDisabled = true)
            SchedulingAccess.ALARM_CHANNEL_DISABLED -> return blocked(persistedReset, AlarmSchedulingState.FAILED, channelDisabled = true)
            SchedulingAccess.GRANTED -> Unit
        }

        val nextAt = if (persistedReset.repeatDays.isEmpty()) {
            requireNotNull(persistedReset.resolvedOneShotAt)
        } else {
            calculator.recurringStrictlyAfter(now, timeProvider.zoneId(), persistedReset.time, persistedReset.repeatDays)
                ?: return AlarmSaveResult.Failed("Could not calculate the next repeat.")
        }
        val occurrence = ScheduledOccurrence(persistedReset.id, UUID.randomUUID().toString(), revision + 1L, nextAt.toEpochMilli())
        return when (val result = scheduler.schedule(occurrence)) {
            PlatformScheduleResult.Scheduled -> {
                val saved = persistedReset.copy(
                    resolvedOneShotAt = if (persistedReset.repeatDays.isEmpty()) nextAt else null,
                    scheduleRevision = occurrence.revision,
                    scheduledOccurrenceId = occurrence.occurrenceId,
                    scheduledAt = nextAt,
                    regularScheduledBootId = bootClock.bootId(),
                    schedulingState = AlarmSchedulingState.SCHEDULED,
                )
                try {
                    AlarmSaveResult.Scheduled(repository.save(saved))
                } catch (exception: Exception) {
                    scheduler.cancel(occurrence)
                    AlarmSaveResult.Failed(exception.message ?: "Could not save the scheduled alarm.")
                }
            }
            PlatformScheduleResult.ExactAlarmAccessRequired -> blocked(persistedReset, AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS)
            is PlatformScheduleResult.Failed -> {
                repository.save(persistedReset.copy(schedulingState = AlarmSchedulingState.FAILED))
                AlarmSaveResult.Failed(result.message)
            }
        }
    }

    suspend fun retry(alarmId: String): AlarmSaveResult = mutex.withLock {
        val alarm = repository.get(alarmId)?.alarm ?: return@withLock AlarmSaveResult.Failed("Alarm not found.")
        if (!alarm.configuredEnabled) return@withLock AlarmSaveResult.SavedWithoutSchedule(alarm)
        if (alarm.schedulingState in setOf(AlarmSchedulingState.SCHEDULED, AlarmSchedulingState.SNOOZED,
                AlarmSchedulingState.RINGING)) {
            return@withLock AlarmSaveResult.Scheduled(alarm)
        }
        if (alarm.repeatDays.isEmpty() && alarm.resolvedOneShotAt?.isAfter(timeProvider.now()) != true) {
            alarm.nextOccurrence()?.let(scheduler::cancel)
            val missed = alarm.copy(configuredEnabled = false, scheduledOccurrenceId = null, scheduledAt = null,
                regularScheduledBootId = null, schedulingState = AlarmSchedulingState.MISSED)
            repository.save(missed)
            return@withLock AlarmSaveResult.Failed("The one-shot time passed while scheduling was unavailable. Enable it again to choose a new time.")
        }
        saveLocked(alarm)
    }

    suspend fun delete(alarmId: String) = mutex.withLock {
        val previous = repository.get(alarmId)?.alarm
        repository.delete(alarmId)
        previous?.nextOccurrence()?.let(scheduler::cancel)
        previous?.pendingSnooze()?.let(scheduler::cancel)
    }

    /** A regular repeat advances once after acceptance, including when another ringing session causes a skip. */
    suspend fun acceptDelivery(alarmId: String, occurrenceId: String, revision: Long): DeliveryDisposition = mutex.withLock {
        val bootId = bootClock.bootId()
        val elapsed = bootClock.elapsedRealtimeMillis()
        val otherRinging = repository.observeAll().first().map { it.alarm }
            .filter { it.ringingOccurrenceId != null }
            .onEach { settleInterruptedRinging(it, bootId, elapsed) }
            .any { current ->
                val refreshed = repository.get(current.id)?.alarm
                refreshed?.ringingOccurrenceId != null &&
                    (refreshed.id != alarmId || refreshed.ringingOccurrenceId != occurrenceId)
            }
        val previous = repository.get(alarmId)?.alarm
        val regularAt = previous?.scheduledAt
        val claim = repository.claimScheduledOccurrence(
            alarmId, occurrenceId, revision, bootId, elapsed + MAX_RINGING_MILLIS,
        )
        if ((claim == DeliveryClaim.REGULAR || claim == DeliveryClaim.SKIPPED_REGULAR) &&
            previous != null && previous.repeatDays.isNotEmpty()
        ) scheduleNextRegular(previous, regularAt)
        when (claim) {
            DeliveryClaim.REGULAR, DeliveryClaim.SNOOZE -> {
                if (otherRinging) {
                    repository.finishOccurrence(alarmId, occurrenceId, revision, AlarmSchedulingState.SKIPPED)
                    DeliveryDisposition.SKIPPED
                } else {
                    if (bootId < 0L) unverifiedBootClaims += RingingOccurrence(alarmId, occurrenceId, revision)
                    DeliveryDisposition.RING
                }
            }
            DeliveryClaim.SKIPPED_REGULAR, DeliveryClaim.SKIPPED_SNOOZE -> DeliveryDisposition.SKIPPED
            DeliveryClaim.STALE -> DeliveryDisposition.STALE
        }
    }

    private suspend fun scheduleNextRegular(previous: Alarm, deliveredAt: Instant?) {
        val current = repository.get(previous.id)?.alarm ?: return
        val now = timeProvider.now()
        val anchor = maxOf(now, deliveredAt ?: now)
        val nextAt = calculator.recurringStrictlyAfter(anchor, timeProvider.zoneId(), current.time, current.repeatDays)
        if (nextAt == null) {
            repository.markRegularSchedulingFailed(current.id, current.scheduleRevision, AlarmSchedulingState.FAILED)
            return
        }
        val occurrence = ScheduledOccurrence(
            current.id,
            UUID.randomUUID().toString(),
            maxOf(current.scheduleRevision, current.snoozeRevision ?: 0L, current.ringingRevision ?: 0L) + 1L,
            nextAt.toEpochMilli(),
        )
        val hasExactAccess = scheduler.canScheduleExactAlarms()
        val result = if (hasExactAccess) scheduler.schedule(occurrence) else null
        when {
            result == PlatformScheduleResult.Scheduled -> {
                if (!repository.installNextRegular(
                        current.id, current.scheduleRevision, occurrence.occurrenceId,
                        occurrence.revision, occurrence.triggerAtEpochMillis, bootClock.bootId(),
                    )
                ) scheduler.cancel(occurrence)
            }
            result == PlatformScheduleResult.ExactAlarmAccessRequired ||
                !hasExactAccess -> repository.markRegularSchedulingFailed(
                    current.id, current.scheduleRevision, AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS,
                )
            result is PlatformScheduleResult.Failed ->
                repository.markRegularSchedulingFailed(current.id, current.scheduleRevision, AlarmSchedulingState.FAILED)
        }
    }

    /** A five-minute monotonic deadline, with a wall-clock recovery instant for the next boot. */
    suspend fun snooze(ringing: RingingOccurrence): SnoozeResult = mutex.withLock {
        val alarm = repository.get(ringing.alarmId)?.alarm ?: return@withLock SnoozeResult.Stale
        if (alarm.ringingOccurrenceId != ringing.occurrenceId || alarm.ringingRevision != ringing.revision ||
            !alarm.configuredEnabled
        ) return@withLock SnoozeResult.Stale
        if (alarm.snoozeOccurrenceId != null) return@withLock SnoozeResult.Failed("Another snooze is already pending. Cancel it first.")
        when (scheduler.access()) {
            SchedulingAccess.EXACT_ALARM_ACCESS_REQUIRED -> return@withLock SnoozeResult.AccessRequired("Allow alarms & reminders to snooze this alarm.")
            SchedulingAccess.NOTIFICATION_PERMISSION_REQUIRED -> return@withLock SnoozeResult.AccessRequired("Allow notifications to keep the snoozed alarm accessible.")
            SchedulingAccess.NOTIFICATIONS_DISABLED -> return@withLock SnoozeResult.AccessRequired("Enable app notifications to keep the snoozed alarm accessible.")
            SchedulingAccess.ALARM_CHANNEL_DISABLED -> return@withLock SnoozeResult.AccessRequired("Enable the Ringing alarms channel to snooze this alarm.")
            SchedulingAccess.GRANTED -> Unit
        }
        val next = ScheduledOccurrence(
            alarm.id,
            UUID.randomUUID().toString(),
            maxOf(alarm.scheduleRevision, alarm.snoozeRevision ?: 0L, alarm.ringingRevision ?: 0L) + 1L,
            timeProvider.now().plusMillis(SNOOZE_DURATION_MILLIS).toEpochMilli(),
            bootClock.elapsedRealtimeMillis() + SNOOZE_DURATION_MILLIS,
        )
        when (val platform = scheduler.schedule(next)) {
            PlatformScheduleResult.Scheduled -> Unit
            PlatformScheduleResult.ExactAlarmAccessRequired -> return@withLock SnoozeResult.AccessRequired("Allow alarms & reminders to snooze this alarm.")
            is PlatformScheduleResult.Failed -> return@withLock SnoozeResult.Failed(platform.message)
        }
        val persisted = repository.replaceRingingWithSnooze(
            alarm.id, ringing.occurrenceId, ringing.revision,
            next.occurrenceId, next.revision, next.triggerAtEpochMillis,
            requireNotNull(next.elapsedDeadlineMillis), bootClock.bootId(),
        )
        if (!persisted) {
            scheduler.cancel(next)
            return@withLock SnoozeResult.Stale
        }
        SnoozeResult.Snoozed(next)
    }

    suspend fun cancelSnooze(alarmId: String): Boolean = mutex.withLock {
        val alarm = repository.get(alarmId)?.alarm ?: return@withLock false
        val pending = alarm.pendingSnooze() ?: return@withLock false
        if (!repository.clearPendingSnooze(alarmId, pending.occurrenceId, pending.revision)) return@withLock false
        scheduler.cancel(pending)
        true
    }

    suspend fun finishOccurrence(
        alarmId: String, occurrenceId: String, revision: Long, finalState: AlarmSchedulingState,
        failureMessage: String? = null,
    ): Boolean = mutex.withLock {
        unverifiedBootClaims.remove(RingingOccurrence(alarmId, occurrenceId, revision))
        repository.finishOccurrence(alarmId, occurrenceId, revision, finalState, failureMessage)
    }

    suspend fun ringingStatus(occurrence: RingingOccurrence): RecoverableRinging? = mutex.withLock {
        val alarm = repository.get(occurrence.alarmId)?.alarm ?: return@withLock null
        if (alarm.ringingOccurrenceId != occurrence.occurrenceId || alarm.ringingRevision != occurrence.revision) {
            return@withLock null
        }
        val bootId = bootClock.bootId()
        val elapsed = bootClock.elapsedRealtimeMillis()
        settleInterruptedRinging(alarm, bootId, elapsed)
        val current = repository.get(occurrence.alarmId)?.alarm ?: return@withLock null
        if (current.ringingOccurrenceId != occurrence.occurrenceId || current.ringingRevision != occurrence.revision) {
            return@withLock null
        }
        RecoverableRinging(occurrence, requireNotNull(current.ringingDeadlineElapsedMillis) - elapsed)
    }

    /** One source-of-truth pass, serialized with all alarm mutations and delivery claims. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun reconcile(reason: ReconcileReason = ReconcileReason.FOREGROUND): List<RecoverableRinging> = mutex.withLock {
        val now = timeProvider.now()
        val bootId = bootClock.bootId()
        val elapsed = bootClock.elapsedRealtimeMillis()
        val recoverable = mutableListOf<RecoverableRinging>()
        for (row in repository.observeAll().first()) {
            var alarm = repository.get(row.alarm.id)?.alarm ?: continue
            if (!alarm.configuredEnabled) continue
            // Legacy recurring rules not explicitly saved after 7A remain dormant.
            if (alarm.schedulingState == AlarmSchedulingState.NOT_SCHEDULED &&
                alarm.scheduledOccurrenceId == null && alarm.snoozeOccurrenceId == null
            ) continue
            if (alarm.ringingOccurrenceId != null) {
                settleInterruptedRinging(alarm, bootId, elapsed)
                alarm = repository.get(alarm.id)?.alarm ?: continue
                if (!alarm.configuredEnabled) continue
                if (alarm.ringingOccurrenceId != null) {
                    val occurrence = RingingOccurrence(alarm.id, alarm.ringingOccurrenceId,
                        requireNotNull(alarm.ringingRevision))
                    if (recoverable.isEmpty()) recoverable += RecoverableRinging(
                        occurrence, requireNotNull(alarm.ringingDeadlineElapsedMillis) - elapsed,
                    ) else repository.finishOccurrence(alarm.id, occurrence.occurrenceId,
                        occurrence.revision, AlarmSchedulingState.SKIPPED)
                    alarm = repository.get(alarm.id)?.alarm ?: continue
                }
            }
            reconcileRegular(alarm, now, bootId)
            alarm = repository.get(alarm.id)?.alarm ?: continue
            reconcileSnooze(alarm, now, elapsed, bootId)
        }
        recoverable
    }

    private suspend fun settleInterruptedRinging(alarm: Alarm, bootId: Long, elapsed: Long) {
        val occurrenceId = alarm.ringingOccurrenceId ?: return
        val revision = alarm.ringingRevision ?: return
        val deadline = alarm.ringingDeadlineElapsedMillis
        val sameBoot = if (bootId >= 0L) alarm.ringingBootId == bootId else
            RingingOccurrence(alarm.id, occurrenceId, revision) in unverifiedBootClaims
        val terminal = when {
            !sameBoot -> AlarmSchedulingState.MISSED
            deadline == null -> AlarmSchedulingState.FAILED
            elapsed >= deadline -> AlarmSchedulingState.COMPLETED
            else -> null
        } ?: return
        unverifiedBootClaims.remove(RingingOccurrence(alarm.id, occurrenceId, revision))
        repository.finishOccurrence(alarm.id, occurrenceId, revision, terminal,
            if (terminal == AlarmSchedulingState.FAILED) "Alarm playback was interrupted before its deadline could be verified." else null)
    }

    private suspend fun reconcileRegular(alarm: Alarm, now: Instant, bootId: Long) {
        if (alarm.repeatDays.isEmpty() && alarm.scheduledOccurrenceId == null &&
            (alarm.snoozeOccurrenceId != null || alarm.ringingOccurrenceId != null)
        ) return // The original one-shot was consumed; only its snooze/ring remains.
        val expected = if (alarm.repeatDays.isEmpty()) alarm.resolvedOneShotAt else
            calculator.recurringStrictlyAfter(
                maxOf(now, alarm.lastRegularDeliveredAt ?: now), timeProvider.zoneId(), alarm.time, alarm.repeatDays,
            )
        val old = alarm.nextOccurrence()
        if (alarm.repeatDays.isEmpty() && (expected == null || !expected.isAfter(now))) {
            if (old != null) scheduler.cancel(old)
            if (alarm.snoozeOccurrenceId == null && alarm.ringingOccurrenceId == null) {
                repository.save(alarm.copy(
                    configuredEnabled = false, scheduledOccurrenceId = null, scheduledAt = null,
                    regularScheduledBootId = null, schedulingState = AlarmSchedulingState.MISSED,
                ))
            }
            return
        }
        if (expected == null) return
        // A pending recurring occurrence in the past is missed; compute the next future weekday.
        val changed = old == null || alarm.scheduledAt != expected
        // Always re-arm the same PendingIntent: AlarmManager replaces it, including after force-stop/relaunch.
        if (old != null && changed) scheduler.cancel(old)
        val next = if (changed) ScheduledOccurrence(
            alarm.id, UUID.randomUUID().toString(),
            maxOf(alarm.scheduleRevision, alarm.snoozeRevision ?: 0L, alarm.ringingRevision ?: 0L) + 1L,
            expected.toEpochMilli(),
        ) else requireNotNull(old)
        val pending = alarm.copy(
            scheduleRevision = next.revision, scheduledOccurrenceId = next.occurrenceId,
            scheduledAt = expected, regularScheduledBootId = null,
            schedulingState = AlarmSchedulingState.FAILED,
        )
        repository.save(pending)
        if (!scheduler.canScheduleExactAlarms()) {
            repository.save(pending.copy(schedulingState = AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS))
            return
        }
        when (scheduler.schedule(next)) {
            PlatformScheduleResult.Scheduled -> repository.save(pending.copy(
                regularScheduledBootId = bootId,
                schedulingState = when {
                    pending.ringingOccurrenceId != null -> AlarmSchedulingState.RINGING
                    pending.snoozeOccurrenceId != null -> AlarmSchedulingState.SNOOZED
                    else -> AlarmSchedulingState.SCHEDULED
                },
            ))
            PlatformScheduleResult.ExactAlarmAccessRequired -> repository.save(
                pending.copy(schedulingState = AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS),
            )
            is PlatformScheduleResult.Failed -> repository.save(pending.copy(schedulingState = AlarmSchedulingState.FAILED))
        }
    }

    private suspend fun reconcileSnooze(alarm: Alarm, now: Instant, elapsed: Long, bootId: Long) {
        val old = alarm.pendingSnooze() ?: return
        val sameBoot = bootId >= 0L && alarm.snoozeScheduledBootId == bootId &&
            alarm.snoozeElapsedDeadlineMillis != null
        val remaining = if (sameBoot) requireNotNull(alarm.snoozeElapsedDeadlineMillis) - elapsed
            else old.triggerAtEpochMillis - now.toEpochMilli()
        if (remaining <= 0L) {
            scheduler.cancel(old)
            repository.clearPendingSnooze(alarm.id, old.occurrenceId, old.revision)
            if (alarm.repeatDays.isEmpty()) {
                repository.get(alarm.id)?.alarm?.let { repository.save(it.copy(schedulingState = AlarmSchedulingState.MISSED)) }
            }
            return
        }
        val deadline = if (sameBoot) requireNotNull(alarm.snoozeElapsedDeadlineMillis) else elapsed + remaining
        val wallAt = now.plusMillis(remaining)
        val pending = alarm.copy(
            snoozedAt = wallAt, snoozeElapsedDeadlineMillis = deadline,
            snoozeScheduledBootId = bootId,
            schedulingState = AlarmSchedulingState.FAILED,
        )
        repository.save(pending)
        if (!scheduler.canScheduleExactAlarms()) {
            repository.save(pending.copy(schedulingState = AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS))
            return
        }
        val next = old.copy(triggerAtEpochMillis = wallAt.toEpochMilli(), elapsedDeadlineMillis = deadline)
        when (scheduler.schedule(next)) {
            PlatformScheduleResult.Scheduled -> repository.save(pending.copy(snoozeScheduledBootId = bootId,
                schedulingState = if (pending.ringingOccurrenceId != null) AlarmSchedulingState.RINGING else AlarmSchedulingState.SNOOZED))
            PlatformScheduleResult.ExactAlarmAccessRequired -> repository.save(pending.copy(
                schedulingState = AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS))
            is PlatformScheduleResult.Failed -> repository.save(pending.copy(
                schedulingState = AlarmSchedulingState.FAILED))
        }
    }

    private suspend fun blocked(
        alarm: Alarm,
        state: AlarmSchedulingState,
        notificationsDisabled: Boolean = false,
        channelDisabled: Boolean = false,
    ): AlarmSaveResult {
        val saved = alarm.copy(schedulingState = state)
        val persisted = repository.save(saved)
        return when {
            notificationsDisabled -> AlarmSaveResult.BlockedNotificationsDisabled(persisted)
            channelDisabled -> AlarmSaveResult.BlockedAlarmChannel(persisted)
            state == AlarmSchedulingState.BLOCKED_EXACT_ALARM_ACCESS -> AlarmSaveResult.BlockedExactAlarmAccess(persisted)
            else -> AlarmSaveResult.BlockedNotificationPermission(persisted)
        }
    }

    private companion object {
        const val SNOOZE_DURATION_MILLIS = 5 * 60 * 1_000L
        const val MAX_RINGING_MILLIS = 5 * 60 * 1_000L
    }
}

sealed interface SnoozeResult {
    data class Snoozed(val occurrence: ScheduledOccurrence) : SnoozeResult
    data class AccessRequired(val message: String) : SnoozeResult
    data class Failed(val message: String) : SnoozeResult
    data object Stale : SnoozeResult
}
