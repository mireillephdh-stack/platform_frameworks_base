/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.android.settingslib.notification.data.repository

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.provider.Settings.Global
import androidx.test.filters.SmallTest
import com.android.settingslib.flags.Flags
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@SmallTest
class ZenModeRepositoryTest {

    @Mock private lateinit var context: Context

    @Mock private lateinit var notificationManager: NotificationManager

    @Captor private lateinit var receiverCaptor: ArgumentCaptor<BroadcastReceiver>

    private lateinit var underTest: ZenModeRepository

    private val testScope: TestScope = TestScope()

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        underTest =
            ZenModeRepositoryImpl(
                context,
                notificationManager,
                testScope.backgroundScope,
                testScope.testScheduler,
            )
    }

    @DisableFlags(android.app.Flags.FLAG_MODES_API, Flags.FLAG_VOLUME_PANEL_BROADCAST_FIX)
    @Test
    fun consolidatedPolicyChanges_repositoryEmits_flagsOff() {
        testScope.runTest {
            val values = mutableListOf<NotificationManager.Policy?>()
            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy1)
            underTest.consolidatedNotificationPolicy
                .onEach { values.add(it) }
                .launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy2)
            triggerIntent(NotificationManager.ACTION_NOTIFICATION_POLICY_CHANGED)
            runCurrent()

            assertThat(values)
                .containsExactlyElementsIn(listOf(null, testPolicy1, testPolicy2))
                .inOrder()
        }
    }

    @EnableFlags(android.app.Flags.FLAG_MODES_API, Flags.FLAG_VOLUME_PANEL_BROADCAST_FIX)
    @Test
    fun consolidatedPolicyChanges_repositoryEmits_flagsOn() {
        testScope.runTest {
            val values = mutableListOf<NotificationManager.Policy?>()
            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy1)
            underTest.consolidatedNotificationPolicy
                .onEach { values.add(it) }
                .launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.consolidatedNotificationPolicy).thenReturn(testPolicy2)
            triggerIntent(NotificationManager.ACTION_CONSOLIDATED_NOTIFICATION_POLICY_CHANGED)
            runCurrent()

            assertThat(values)
                .containsExactlyElementsIn(listOf(null, testPolicy1, testPolicy2))
                .inOrder()
        }
    }

    @Test
    fun zenModeChanges_repositoryEmits() {
        testScope.runTest {
            val values = mutableListOf<Int?>()
            `when`(notificationManager.zenMode).thenReturn(Global.ZEN_MODE_OFF)
            underTest.globalZenMode.onEach { values.add(it) }.launchIn(backgroundScope)
            runCurrent()

            `when`(notificationManager.zenMode).thenReturn(Global.ZEN_MODE_ALARMS)
            triggerIntent(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            runCurrent()

            assertThat(values)
                .containsExactlyElementsIn(
                    listOf(null, Global.ZEN_MODE_OFF, Global.ZEN_MODE_ALARMS))
                .inOrder()
        }
    }

    private fun triggerIntent(action: String) {
        verify(context).registerReceiver(receiverCaptor.capture(), any())
        receiverCaptor.value.onReceive(context, Intent(action))
    }

    private companion object {
        val testPolicy1 =
            NotificationManager.Policy(
                /* priorityCategories = */ 1,
                /* priorityCallSenders =*/ 1,
                /* priorityMessageSenders = */ 1,
            )
        val testPolicy2 =
            NotificationManager.Policy(
                /* priorityCategories = */ 2,
                /* priorityCallSenders =*/ 2,
                /* priorityMessageSenders = */ 2,
            )
    }
}
