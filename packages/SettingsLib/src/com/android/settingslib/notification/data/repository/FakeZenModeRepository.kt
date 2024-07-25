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
import android.provider.Settings
import com.android.settingslib.notification.modes.TestModeBuilder
import com.android.settingslib.notification.modes.ZenMode
import java.time.Duration
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FakeZenModeRepository : ZenModeRepository {

    private val mutableNotificationPolicy = MutableStateFlow<NotificationManager.Policy?>(null)
    override val consolidatedNotificationPolicy: StateFlow<NotificationManager.Policy?>
        get() = mutableNotificationPolicy.asStateFlow()

    private val mutableZenMode = MutableStateFlow(Settings.Global.ZEN_MODE_OFF)
    override val globalZenMode: StateFlow<Int>
        get() = mutableZenMode.asStateFlow()

    private val mutableModesFlow: MutableStateFlow<List<ZenMode>> = MutableStateFlow(listOf())
    override val modes: Flow<List<ZenMode>>
        get() = mutableModesFlow.asStateFlow()

    init {
        updateNotificationPolicy()
    }

    fun updateNotificationPolicy(policy: NotificationManager.Policy?) {
        mutableNotificationPolicy.value = policy
    }

    fun updateZenMode(zenMode: Int) {
        mutableZenMode.value = zenMode
    }

    fun addModes(zenModes: List<ZenMode>) {
        mutableModesFlow.value += zenModes
    }

    fun addMode(id: String, active: Boolean = false) {
        mutableModesFlow.value += newMode(id, active)
    }

    fun removeMode(id: String) {
        mutableModesFlow.value = mutableModesFlow.value.filter { it.id != id }
    }

    override fun activateMode(zenMode: ZenMode, duration: Duration?) {
        activateMode(zenMode.id)
    }

    override fun deactivateMode(zenMode: ZenMode) {
        deactivateMode(zenMode.id)
    }

    fun activateMode(id: String) {
        updateModeActiveState(id = id, isActive = true)
    }

    fun deactivateMode(id: String) {
        updateModeActiveState(id = id, isActive = false)
    }

    // Update the active state while maintaining the mode's position in the list
    private fun updateModeActiveState(id: String, isActive: Boolean) {
        val modes = mutableModesFlow.value.toMutableList()
        val index = modes.indexOfFirst { it.id == id }
        if (index < 0) {
            throw IllegalArgumentException("mode $id not found")
        }
        modes[index] = TestModeBuilder(modes[index]).setActive(isActive).build()
        mutableModesFlow.value = modes
    }
}

fun FakeZenModeRepository.updateNotificationPolicy(
    priorityCategories: Int = 0,
    priorityCallSenders: Int = NotificationManager.Policy.PRIORITY_SENDERS_ANY,
    priorityMessageSenders: Int = NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
    suppressedVisualEffects: Int = NotificationManager.Policy.SUPPRESSED_EFFECTS_UNSET,
    state: Int = NotificationManager.Policy.STATE_UNSET,
    priorityConversationSenders: Int = NotificationManager.Policy.CONVERSATION_SENDERS_NONE,
) =
    updateNotificationPolicy(
        NotificationManager.Policy(
            priorityCategories,
            priorityCallSenders,
            priorityMessageSenders,
            suppressedVisualEffects,
            state,
            priorityConversationSenders,
        )
    )

private fun newMode(id: String, active: Boolean = false): ZenMode {
    return TestModeBuilder().setId(id).setName("Mode $id").setActive(active).build()
}
