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

package com.android.systemui.touchpad.tutorial.ui.gesture

import android.view.MotionEvent
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.FINISHED
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.IN_PROGRESS
import com.android.systemui.touchpad.tutorial.ui.gesture.GestureState.NOT_STARTED
import kotlin.math.abs

class BackGestureMonitor(
    override val gestureDistanceThresholdPx: Int,
    override val gestureStateChangedCallback: (GestureState) -> Unit
) : TouchpadGestureMonitor {

    private var xStart = 0f

    override fun processTouchpadEvent(event: MotionEvent) {
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                if (isThreeFingerTouchpadSwipe(event)) {
                    xStart = event.x
                    gestureStateChangedCallback(IN_PROGRESS)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (isThreeFingerTouchpadSwipe(event)) {
                    val distance = abs(event.x - xStart)
                    if (distance >= gestureDistanceThresholdPx) {
                        gestureStateChangedCallback(FINISHED)
                    } else {
                        gestureStateChangedCallback(NOT_STARTED)
                    }
                }
            }
        }
    }
}
