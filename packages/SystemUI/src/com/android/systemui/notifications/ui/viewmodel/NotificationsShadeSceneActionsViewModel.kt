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

package com.android.systemui.notifications.ui.viewmodel

import com.android.compose.animation.scene.Back
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.ui.viewmodel.SceneActionsViewModel
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.shade.shared.model.ShadeAlignment
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

/**
 * Models the UI state for the user actions that the user can perform to navigate to other scenes.
 *
 * Different from the [NotificationsShadeSceneContentViewModel] which models the _content_ of the
 * scene.
 */
class NotificationsShadeSceneActionsViewModel
@AssistedInject
constructor(
    private val shadeInteractor: ShadeInteractor,
) : SceneActionsViewModel() {

    override suspend fun hydrateActions(setActions: (Map<UserAction, UserActionResult>) -> Unit) {
        setActions(
            mapOf(
                if (shadeInteractor.shadeAlignment == ShadeAlignment.Top) {
                    Swipe.Up
                } else {
                    Swipe.Down
                } to SceneFamilies.Home,
                Back to SceneFamilies.Home,
            )
        )
    }

    @AssistedFactory
    interface Factory {
        fun create(): NotificationsShadeSceneActionsViewModel
    }
}
