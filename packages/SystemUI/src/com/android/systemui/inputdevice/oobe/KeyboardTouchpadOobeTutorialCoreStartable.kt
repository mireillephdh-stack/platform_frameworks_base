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

package com.android.systemui.inputdevice.oobe

import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.inputdevice.oobe.domain.interactor.OobeSchedulerInteractor
import com.android.systemui.shared.Flags.newTouchpadGesturesTutorial
import dagger.Lazy
import javax.inject.Inject

/** A [CoreStartable] to launch a scheduler for keyboard and touchpad OOBE education */
@SysUISingleton
class KeyboardTouchpadOobeTutorialCoreStartable
@Inject
constructor(private val oobeSchedulerInteractor: Lazy<OobeSchedulerInteractor>) : CoreStartable {
    override fun start() {
        if (newTouchpadGesturesTutorial()) {
            oobeSchedulerInteractor.get().start()
        }
    }
}
