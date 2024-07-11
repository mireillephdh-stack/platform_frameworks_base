/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.chips.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testScope
import com.android.systemui.mediaprojection.data.model.MediaProjectionState
import com.android.systemui.mediaprojection.data.repository.fakeMediaProjectionRepository
import com.android.systemui.mediaprojection.taskswitcher.FakeActivityTaskManager.Companion.createTask
import com.android.systemui.res.R
import com.android.systemui.screenrecord.data.model.ScreenRecordModel
import com.android.systemui.screenrecord.data.repository.screenRecordRepository
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.NORMAL_PACKAGE
import com.android.systemui.statusbar.chips.mediaprojection.domain.interactor.MediaProjectionChipInteractorTest.Companion.setUpPackageManagerForMediaProjection
import com.android.systemui.statusbar.chips.ui.model.OngoingActivityChipModel
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.ongoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class OngoingActivityChipsViewModelTest : SysuiTestCase() {
    private val kosmos = Kosmos().also { it.testCase = this }
    private val testScope = kosmos.testScope
    private val systemClock = kosmos.fakeSystemClock

    private val screenRecordState = kosmos.screenRecordRepository.screenRecordState
    private val mediaProjectionState = kosmos.fakeMediaProjectionRepository.mediaProjectionState
    private val callRepo = kosmos.ongoingCallRepository

    private val underTest = kosmos.ongoingActivityChipsViewModel

    @Before
    fun setUp() {
        setUpPackageManagerForMediaProjection(kosmos)
    }

    @Test
    fun chip_allHidden_hidden() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chip)

            assertThat(latest).isEqualTo(OngoingActivityChipModel.Hidden)
        }

    @Test
    fun chip_screenRecordShow_restHidden_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_screenRecordShowAndCallShow_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording

            callRepo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 34, intent = null))

            val latest by collectLastValue(underTest.chip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_screenRecordShowAndShareToAppShow_screenRecordShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(OngoingCallModel.NoCall)

            val latest by collectLastValue(underTest.chip)

            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_shareToAppShowAndCallShow_shareToAppShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)
            callRepo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 34, intent = null))

            val latest by collectLastValue(underTest.chip)

            assertIsShareToAppChip(latest)
        }

    @Test
    fun chip_screenRecordAndShareToAppAndCastToOtherHideAndCallShown_callShown() =
        testScope.runTest {
            screenRecordState.value = ScreenRecordModel.DoingNothing
            // MediaProjection covers both share-to-app and cast-to-other-device
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            callRepo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 34, intent = null))

            val latest by collectLastValue(underTest.chip)

            assertIsCallChip(latest)
        }

    @Test
    fun chip_higherPriorityChipAdded_lowerPriorityChipReplaced() =
        testScope.runTest {
            // Start with just the lower priority call chip
            callRepo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 34, intent = null))
            mediaProjectionState.value = MediaProjectionState.NotProjecting
            screenRecordState.value = ScreenRecordModel.DoingNothing

            val latest by collectLastValue(underTest.chip)

            assertIsCallChip(latest)

            // WHEN the higher priority media projection chip is added
            mediaProjectionState.value =
                MediaProjectionState.Projecting.SingleTask(
                    NORMAL_PACKAGE,
                    hostDeviceName = null,
                    createTask(taskId = 1),
                )

            // THEN the higher priority media projection chip is used
            assertIsShareToAppChip(latest)

            // WHEN the higher priority screen record chip is added
            screenRecordState.value = ScreenRecordModel.Recording

            // THEN the higher priority screen record chip is used
            assertIsScreenRecordChip(latest)
        }

    @Test
    fun chip_highestPriorityChipRemoved_showsNextPriorityChip() =
        testScope.runTest {
            // WHEN all chips are active
            screenRecordState.value = ScreenRecordModel.Recording
            mediaProjectionState.value =
                MediaProjectionState.Projecting.EntireScreen(NORMAL_PACKAGE)

            callRepo.setOngoingCallState(OngoingCallModel.InCall(startTimeMs = 34, intent = null))

            val latest by collectLastValue(underTest.chip)

            // THEN the highest priority screen record is used
            assertIsScreenRecordChip(latest)

            // WHEN the higher priority screen record is removed
            screenRecordState.value = ScreenRecordModel.DoingNothing

            // THEN the lower priority media projection is used
            assertIsShareToAppChip(latest)

            // WHEN the higher priority media projection is removed
            mediaProjectionState.value = MediaProjectionState.NotProjecting

            // THEN the lower priority call is used
            assertIsCallChip(latest)
        }

    /** Regression test for b/347726238. */
    @Test
    fun chip_timerDoesNotResetAfterSubscribersRestart() =
        testScope.runTest {
            var latest: OngoingActivityChipModel? = null

            val job1 = underTest.chip.onEach { latest = it }.launchIn(this)

            // Start a chip with a timer
            systemClock.setElapsedRealtime(1234)
            screenRecordState.value = ScreenRecordModel.Recording

            runCurrent()

            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(1234)

            // Stop subscribing to the chip flow
            job1.cancel()

            // Let time pass
            systemClock.setElapsedRealtime(5678)

            // WHEN we re-subscribe to the chip flow
            val job2 = underTest.chip.onEach { latest = it }.launchIn(this)

            runCurrent()

            // THEN the old start time is still used
            assertThat((latest as OngoingActivityChipModel.Shown.Timer).startTimeMs).isEqualTo(1234)

            job2.cancel()
        }

    companion object {
        fun assertIsScreenRecordChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_screenrecord)
        }

        fun assertIsShareToAppChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res).isEqualTo(R.drawable.ic_present_to_all)
        }

        fun assertIsCallChip(latest: OngoingActivityChipModel?) {
            assertThat(latest).isInstanceOf(OngoingActivityChipModel.Shown::class.java)
            val icon = (latest as OngoingActivityChipModel.Shown).icon
            assertThat((icon as Icon.Resource).res)
                .isEqualTo(com.android.internal.R.drawable.ic_phone)
        }
    }
}
