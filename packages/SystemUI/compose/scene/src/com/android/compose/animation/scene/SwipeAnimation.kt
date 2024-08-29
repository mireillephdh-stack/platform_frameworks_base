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

package com.android.compose.animation.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize
import com.android.compose.animation.scene.content.Content
import com.android.compose.animation.scene.content.Overlay
import com.android.compose.animation.scene.content.Scene
import com.android.compose.animation.scene.content.state.TransitionState
import com.android.compose.animation.scene.content.state.TransitionState.HasOverscrollProperties.Companion.DistanceUnspecified
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal fun createSwipeAnimation(
    layoutImpl: SceneTransitionLayoutImpl,
    result: UserActionResult,
    isUpOrLeft: Boolean,
    orientation: Orientation,
): SwipeAnimation<*> {
    fun <T : Content> swipeAnimation(fromContent: T, toContent: T): SwipeAnimation<T> {
        return SwipeAnimation(
            layoutImpl = layoutImpl,
            fromContent = fromContent,
            toContent = toContent,
            userActionDistanceScope = layoutImpl.userActionDistanceScope,
            orientation = orientation,
            isUpOrLeft = isUpOrLeft,
            requiresFullDistanceSwipe = result.requiresFullDistanceSwipe,
        )
    }

    val layoutState = layoutImpl.state
    return when (result) {
        is UserActionResult.ChangeScene -> {
            val fromScene = layoutImpl.scene(layoutState.currentScene)
            val toScene = layoutImpl.scene(result.toScene)
            ChangeCurrentSceneSwipeTransition(
                    layoutState = layoutState,
                    swipeAnimation = swipeAnimation(fromContent = fromScene, toContent = toScene),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }
        is UserActionResult.ShowOverlay -> {
            val fromScene = layoutImpl.scene(layoutState.currentScene)
            val overlay = layoutImpl.overlay(result.overlay)
            ShowOrHideOverlaySwipeTransition(
                    layoutState = layoutState,
                    _fromOrToScene = fromScene,
                    _overlay = overlay,
                    swipeAnimation = swipeAnimation(fromContent = fromScene, toContent = overlay),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }
        is UserActionResult.HideOverlay -> {
            val toScene = layoutImpl.scene(layoutState.currentScene)
            val overlay = layoutImpl.overlay(result.overlay)
            ShowOrHideOverlaySwipeTransition(
                    layoutState = layoutState,
                    _fromOrToScene = toScene,
                    _overlay = overlay,
                    swipeAnimation = swipeAnimation(fromContent = overlay, toContent = toScene),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }
        is UserActionResult.ReplaceByOverlay -> {
            val fromOverlay = layoutImpl.contentForUserActions() as Overlay
            val toOverlay = layoutImpl.overlay(result.overlay)
            ReplaceOverlaySwipeTransition(
                    layoutState = layoutState,
                    swipeAnimation =
                        swipeAnimation(fromContent = fromOverlay, toContent = toOverlay),
                    key = result.transitionKey,
                    replacedTransition = null,
                )
                .swipeAnimation
        }
    }
}

internal fun createSwipeAnimation(old: SwipeAnimation<*>): SwipeAnimation<*> {
    return when (val transition = old.contentTransition) {
        is TransitionState.Transition.ChangeCurrentScene -> {
            ChangeCurrentSceneSwipeTransition(transition as ChangeCurrentSceneSwipeTransition)
                .swipeAnimation
        }
        is TransitionState.Transition.ShowOrHideOverlay -> {
            ShowOrHideOverlaySwipeTransition(transition as ShowOrHideOverlaySwipeTransition)
                .swipeAnimation
        }
        is TransitionState.Transition.ReplaceOverlay -> {
            ReplaceOverlaySwipeTransition(transition as ReplaceOverlaySwipeTransition)
                .swipeAnimation
        }
    }
}

/** A helper class that contains the main logic for swipe transitions. */
internal class SwipeAnimation<T : Content>(
    val layoutImpl: SceneTransitionLayoutImpl,
    val fromContent: T,
    val toContent: T,
    private val userActionDistanceScope: UserActionDistanceScope,
    override val orientation: Orientation,
    override val isUpOrLeft: Boolean,
    val requiresFullDistanceSwipe: Boolean,
    private var lastDistance: Float = DistanceUnspecified,
    currentContent: T = fromContent,
    dragOffset: Float = 0f,
) : TransitionState.HasOverscrollProperties {
    /** The [TransitionState.Transition] whose implementation delegates to this [SwipeAnimation]. */
    lateinit var contentTransition: TransitionState.Transition

    var currentContent by mutableStateOf(currentContent)

    val progress: Float
        get() {
            // Important: If we are going to return early because distance is equal to 0, we should
            // still make sure we read the offset before returning so that the calling code still
            // subscribes to the offset value.
            val offset = offsetAnimation?.animatable?.value ?: dragOffset

            return computeProgress(offset)
        }

    fun computeProgress(offset: Float): Float {
        val distance = distance()
        if (distance == DistanceUnspecified) {
            return 0f
        }
        return offset / distance
    }

    val progressVelocity: Float
        get() {
            val animatable = offsetAnimation?.animatable ?: return 0f
            val distance = distance()
            if (distance == DistanceUnspecified) {
                return 0f
            }

            val velocityInDistanceUnit = animatable.velocity
            return velocityInDistanceUnit / distance.absoluteValue
        }

    override var bouncingContent: ContentKey? = null

    /** The current offset caused by the drag gesture. */
    var dragOffset by mutableFloatStateOf(dragOffset)

    /** The offset animation that animates the offset once the user lifts their finger. */
    private var offsetAnimation: OffsetAnimation? by mutableStateOf(null)

    val isUserInputOngoing: Boolean
        get() = offsetAnimation == null

    override val overscrollScope: OverscrollScope =
        object : OverscrollScope {
            override val density: Float
                get() = layoutImpl.density.density

            override val fontScale: Float
                get() = layoutImpl.density.fontScale

            override val absoluteDistance: Float
                get() = distance().absoluteValue
        }

    /** Whether [finish] was called on this animation. */
    var isFinishing = false
        private set

    constructor(
        other: SwipeAnimation<T>
    ) : this(
        layoutImpl = other.layoutImpl,
        fromContent = other.fromContent,
        toContent = other.toContent,
        userActionDistanceScope = other.userActionDistanceScope,
        orientation = other.orientation,
        isUpOrLeft = other.isUpOrLeft,
        requiresFullDistanceSwipe = other.requiresFullDistanceSwipe,
        lastDistance = other.lastDistance,
        currentContent = other.currentContent,
        dragOffset = other.dragOffset,
    )

    /**
     * The signed distance between [fromContent] and [toContent]. It is negative if [fromContent] is
     * above or to the left of [toContent].
     *
     * Note that this distance can be equal to [DistanceUnspecified] during the first frame of a
     * transition when the distance depends on the size or position of an element that is composed
     * in the content we are going to.
     */
    fun distance(): Float {
        if (lastDistance != DistanceUnspecified) {
            return lastDistance
        }

        val absoluteDistance =
            with(contentTransition.transformationSpec.distance ?: DefaultSwipeDistance) {
                userActionDistanceScope.absoluteDistance(
                    fromContent.targetSize,
                    orientation,
                )
            }

        if (absoluteDistance <= 0f) {
            return DistanceUnspecified
        }

        val distance = if (isUpOrLeft) -absoluteDistance else absoluteDistance
        lastDistance = distance
        return distance
    }

    /** Ends any previous [offsetAnimation] and runs the new [animation]. */
    private fun startOffsetAnimation(animation: () -> OffsetAnimation): OffsetAnimation {
        cancelOffsetAnimation()
        return animation().also { offsetAnimation = it }
    }

    /** Cancel any ongoing offset animation. */
    // TODO(b/317063114) This should be a suspended function to avoid multiple jobs running at
    // the same time.
    fun cancelOffsetAnimation() {
        val animation = offsetAnimation ?: return
        offsetAnimation = null

        dragOffset = animation.animatable.value
        animation.job.cancel()
    }

    fun animateOffset(
        // TODO(b/317063114) The CoroutineScope should be removed.
        coroutineScope: CoroutineScope,
        initialVelocity: Float,
        targetContent: T,
    ): OffsetAnimation {
        val initialProgress = progress
        // Skip the animation if we have already reached the target content and the overscroll does
        // not animate anything.
        val hasReachedTargetContent =
            (targetContent == toContent && initialProgress >= 1f) ||
                (targetContent == fromContent && initialProgress <= 0f)
        val skipAnimation =
            hasReachedTargetContent && !contentTransition.isWithinProgressRange(initialProgress)

        val targetContent =
            if (targetContent != currentContent && !canChangeContent(targetContent)) {
                currentContent
            } else {
                targetContent
            }

        val targetOffset =
            if (targetContent == fromContent) {
                0f
            } else {
                val distance = distance()
                check(distance != DistanceUnspecified) {
                    "distance is equal to $DistanceUnspecified"
                }
                distance
            }

        // If the effective current content changed, it should be reflected right now in the
        // current state, even before the settle animation is ongoing. That way all the
        // swipeables and back handlers will be refreshed and the user can for instance quickly
        // swipe vertically from A => B then horizontally from B => C, or swipe from A => B then
        // immediately go back B => A.
        if (targetContent != currentContent) {
            currentContent = targetContent
        }

        return startOffsetAnimation {
            val animatable = Animatable(dragOffset, OffsetVisibilityThreshold)
            val isTargetGreater = targetOffset > animatable.value
            val startedWhenOvercrollingTargetContent =
                if (targetContent == fromContent) initialProgress < 0f else initialProgress > 1f
            val job =
                coroutineScope
                    // Important: We start atomically to make sure that we start the coroutine even
                    // if it is cancelled right after it is launched, so that snapToContent() is
                    // correctly called. Otherwise, this transition will never be stopped and we
                    // will never settle to Idle.
                    .launch(start = CoroutineStart.ATOMIC) {
                        // TODO(b/327249191): Refactor the code so that we don't even launch a
                        // coroutine if we don't need to animate.
                        if (skipAnimation) {
                            snapToContent(targetContent)
                            dragOffset = targetOffset
                            return@launch
                        }

                        try {
                            val swipeSpec =
                                contentTransition.transformationSpec.swipeSpec
                                    ?: layoutImpl.state.transitions.defaultSwipeSpec
                            animatable.animateTo(
                                targetValue = targetOffset,
                                animationSpec = swipeSpec,
                                initialVelocity = initialVelocity,
                            ) {
                                if (bouncingContent == null) {
                                    val isBouncing =
                                        if (isTargetGreater) {
                                            if (startedWhenOvercrollingTargetContent) {
                                                value >= targetOffset
                                            } else {
                                                value > targetOffset
                                            }
                                        } else {
                                            if (startedWhenOvercrollingTargetContent) {
                                                value <= targetOffset
                                            } else {
                                                value < targetOffset
                                            }
                                        }

                                    if (isBouncing) {
                                        bouncingContent = targetContent.key

                                        // Immediately stop this transition if we are bouncing on a
                                        // content that does not bounce.
                                        if (!contentTransition.isWithinProgressRange(progress)) {
                                            snapToContent(targetContent)
                                        }
                                    }
                                }
                            }
                        } finally {
                            snapToContent(targetContent)
                        }
                    }

            OffsetAnimation(animatable, job)
        }
    }

    private fun canChangeContent(targetContent: Content): Boolean {
        val layoutState = layoutImpl.state
        return when (val transition = contentTransition) {
            is TransitionState.Transition.ChangeCurrentScene ->
                layoutState.canChangeScene(targetContent.key as SceneKey)
            is TransitionState.Transition.ShowOrHideOverlay -> {
                if (targetContent.key == transition.overlay) {
                    layoutState.canShowOverlay(transition.overlay)
                } else {
                    layoutState.canHideOverlay(transition.overlay)
                }
            }
            is TransitionState.Transition.ReplaceOverlay -> {
                val to = targetContent.key as OverlayKey
                val from =
                    if (to == transition.toOverlay) transition.fromOverlay else transition.toOverlay
                layoutState.canReplaceOverlay(from, to)
            }
        }
    }

    private fun snapToContent(content: T) {
        cancelOffsetAnimation()
        check(currentContent == content)
        layoutImpl.state.finishTransition(contentTransition)
    }

    fun finish(): Job {
        if (isFinishing) return requireNotNull(offsetAnimation).job
        isFinishing = true

        // If we were already animating the offset, simply return the job.
        offsetAnimation?.let {
            return it.job
        }

        // Animate to the current content.
        val animation =
            animateOffset(
                coroutineScope = layoutImpl.coroutineScope,
                initialVelocity = 0f,
                targetContent = currentContent,
            )
        check(offsetAnimation == animation)
        return animation.job
    }

    internal class OffsetAnimation(
        /** The animatable used to animate the offset. */
        val animatable: Animatable<Float, AnimationVector1D>,

        /** The job in which [animatable] is animated. */
        val job: Job,
    )
}

private object DefaultSwipeDistance : UserActionDistance {
    override fun UserActionDistanceScope.absoluteDistance(
        fromSceneSize: IntSize,
        orientation: Orientation,
    ): Float {
        return when (orientation) {
            Orientation.Horizontal -> fromSceneSize.width
            Orientation.Vertical -> fromSceneSize.height
        }.toFloat()
    }
}

private class ChangeCurrentSceneSwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<Scene>,
    override val key: TransitionKey?,
    replacedTransition: ChangeCurrentSceneSwipeTransition?,
) :
    TransitionState.Transition.ChangeCurrentScene(
        swipeAnimation.fromContent.key,
        swipeAnimation.toContent.key,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {

    constructor(
        other: ChangeCurrentSceneSwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        key = other.key,
        replacedTransition = other,
    )

    init {
        swipeAnimation.contentTransition = this
    }

    override val currentScene: SceneKey
        get() = swipeAnimation.currentContent.key

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override fun finish(): Job = swipeAnimation.finish()
}

private class ShowOrHideOverlaySwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<Content>,
    val _overlay: Overlay,
    val _fromOrToScene: Scene,
    override val key: TransitionKey?,
    replacedTransition: ShowOrHideOverlaySwipeTransition?,
) :
    TransitionState.Transition.ShowOrHideOverlay(
        _overlay.key,
        _fromOrToScene.key,
        swipeAnimation.fromContent.key,
        swipeAnimation.toContent.key,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {
    constructor(
        other: ShowOrHideOverlaySwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        _overlay = other._overlay,
        _fromOrToScene = other._fromOrToScene,
        key = other.key,
        replacedTransition = other,
    )

    init {
        swipeAnimation.contentTransition = this
    }

    override val isEffectivelyShown: Boolean
        get() = swipeAnimation.currentContent == _overlay

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override fun finish(): Job = swipeAnimation.finish()
}

private class ReplaceOverlaySwipeTransition(
    val layoutState: MutableSceneTransitionLayoutStateImpl,
    val swipeAnimation: SwipeAnimation<Overlay>,
    override val key: TransitionKey?,
    replacedTransition: ReplaceOverlaySwipeTransition?,
) :
    TransitionState.Transition.ReplaceOverlay(
        swipeAnimation.fromContent.key,
        swipeAnimation.toContent.key,
        replacedTransition,
    ),
    TransitionState.HasOverscrollProperties by swipeAnimation {
    constructor(
        other: ReplaceOverlaySwipeTransition
    ) : this(
        layoutState = other.layoutState,
        swipeAnimation = SwipeAnimation(other.swipeAnimation),
        key = other.key,
        replacedTransition = other,
    )

    init {
        swipeAnimation.contentTransition = this
    }

    override val effectivelyShownOverlay: OverlayKey
        get() = swipeAnimation.currentContent.key

    override val progress: Float
        get() = swipeAnimation.progress

    override val progressVelocity: Float
        get() = swipeAnimation.progressVelocity

    override val isInitiatedByUserInput: Boolean = true

    override val isUserInputOngoing: Boolean
        get() = swipeAnimation.isUserInputOngoing

    override fun finish(): Job = swipeAnimation.finish()
}
