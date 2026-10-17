package com.yavin.wakeupsamurai.ui.ringing

import com.yavin.wakeupsamurai.alarm.RingingAlarmController
import com.yavin.wakeupsamurai.alarm.RingingOccurrence
import com.yavin.wakeupsamurai.alarm.SnoozeResult
import com.yavin.wakeupsamurai.domain.alarm.Alarm
import com.yavin.wakeupsamurai.domain.alarm.AlarmRepository
import com.yavin.wakeupsamurai.domain.alarm.AlarmSchedulingState
import com.yavin.wakeupsamurai.domain.alarm.AlarmWithAudio
import java.time.Instant
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RingingViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun showsOnlyTheMatchingActiveOccurrenceAndClosesAfterTerminalState() = runTest(dispatcher) {
        val occurrence = RingingOccurrence("alarm", "occurrence", 3)
        val repository = FakeRepository(ringingAlarm(occurrence))
        val viewModel = RingingViewModel(repository, FakeController())

        viewModel.observe(occurrence)
        runCurrent()
        assertTrue(viewModel.uiState.value is RingingUiState.Active)

        repository.rows.value = listOf(AlarmWithAudio(ringingAlarm(occurrence.copy(revision = 4)), null))
        runCurrent()
        assertEquals(RingingUiState.Ended, viewModel.uiState.value)
    }

    @Test
    fun repeatedDismissUsesOneSharedOccurrenceCommand() = runTest(dispatcher) {
        val occurrence = RingingOccurrence("alarm", "occurrence", 3)
        val controller = FakeController()
        val viewModel = RingingViewModel(FakeRepository(ringingAlarm(occurrence)), controller)

        viewModel.observe(occurrence)
        runCurrent()
        viewModel.dismiss()
        viewModel.dismiss()

        assertEquals(listOf(occurrence), controller.dismissals)
    }

    @Test
    fun repeatedSnoozeUsesOneOccurrenceAwareCommand() = runTest(dispatcher) {
        val occurrence = RingingOccurrence("alarm", "occurrence", 3)
        val controller = FakeController(SnoozeResult.AccessRequired("Access required"))
        val viewModel = RingingViewModel(FakeRepository(ringingAlarm(occurrence)), controller)

        viewModel.observe(occurrence)
        runCurrent()
        viewModel.snooze()
        viewModel.snooze()
        runCurrent()

        assertEquals(listOf(occurrence), controller.snoozes)
        assertTrue((viewModel.uiState.value as RingingUiState.Active).actionError != null)
    }

    private fun ringingAlarm(occurrence: RingingOccurrence) = Alarm(
        id = occurrence.alarmId,
        label = "Breakfast",
        messageText = "Pancakes await.",
        time = LocalTime.of(7, 15),
        repeatDays = emptySet(),
        configuredEnabled = true,
        resolvedOneShotAt = Instant.EPOCH,
        audioAssetId = "audio",
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        scheduleRevision = occurrence.revision,
        scheduledOccurrenceId = occurrence.occurrenceId,
        schedulingState = AlarmSchedulingState.RINGING,
    )
}

private class FakeController(
    private val snoozeResult: SnoozeResult = SnoozeResult.Stale,
) : RingingAlarmController {
    val dismissals = mutableListOf<RingingOccurrence>()
    val snoozes = mutableListOf<RingingOccurrence>()
    override fun dismiss(occurrence: RingingOccurrence) { dismissals += occurrence }
    override suspend fun snooze(occurrence: RingingOccurrence): SnoozeResult {
        snoozes += occurrence
        return snoozeResult
    }
}

private class FakeRepository(initial: Alarm) : AlarmRepository {
    val rows = MutableStateFlow(listOf(AlarmWithAudio(initial, null)))
    override fun observeAll(): Flow<List<AlarmWithAudio>> = rows
    override suspend fun get(id: String): AlarmWithAudio? = rows.value.firstOrNull { it.alarm.id == id }
    override suspend fun save(alarm: Alarm) = Unit
    override suspend fun delete(id: String) = Unit
    override suspend fun claimScheduledOccurrence(alarmId: String, occurrenceId: String, revision: Long) = false
    override suspend fun finishOccurrence(
        alarmId: String,
        occurrenceId: String,
        revision: Long,
        finalState: AlarmSchedulingState,
    ) = false
    override suspend fun replaceRingingWithSnooze(
        alarmId: String, ringingOccurrenceId: String, ringingRevision: Long,
        snoozeOccurrenceId: String, snoozeRevision: Long, snoozedAtEpochMillis: Long,
    ) = false
}
