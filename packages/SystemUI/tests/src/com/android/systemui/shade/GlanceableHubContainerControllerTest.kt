/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shade

import android.graphics.Insets
import android.graphics.Rect
import android.os.PowerManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.Flags
import com.android.systemui.Flags.FLAG_GLANCEABLE_HUB_BACK_GESTURE
import com.android.systemui.SysuiTestCase
import com.android.systemui.ambient.touch.TouchHandler
import com.android.systemui.ambient.touch.TouchMonitor
import com.android.systemui.ambient.touch.dagger.AmbientTouchComponent
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.communal.data.repository.FakeCommunalSceneRepository
import com.android.systemui.communal.data.repository.fakeCommunalSceneRepository
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.communal.domain.interactor.setCommunalAvailable
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.communal.ui.compose.CommunalContent
import com.android.systemui.communal.ui.viewmodel.CommunalViewModel
import com.android.systemui.communal.util.CommunalColors
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.shared.model.sceneDataSourceDelegator
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.statusbar.notification.stack.notificationStackScrollLayoutController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyFloat
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy

@ExperimentalCoroutinesApi
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class GlanceableHubContainerControllerTest : SysuiTestCase() {
    private val kosmos: Kosmos =
        testKosmos().apply {
            // UnconfinedTestDispatcher makes testing simpler due to CommunalInteractor flows using
            // SharedFlow
            testDispatcher = UnconfinedTestDispatcher()
        }

    @Mock private lateinit var communalViewModel: CommunalViewModel
    @Mock private lateinit var powerManager: PowerManager
    @Mock private lateinit var touchMonitor: TouchMonitor
    @Mock private lateinit var communalColors: CommunalColors
    @Mock private lateinit var communalContent: CommunalContent
    private lateinit var ambientTouchComponentFactory: AmbientTouchComponent.Factory

    private lateinit var parentView: FrameLayout
    private lateinit var containerView: View
    private lateinit var testableLooper: TestableLooper

    private lateinit var communalRepository: FakeCommunalSceneRepository
    private lateinit var underTest: GlanceableHubContainerController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        communalRepository = kosmos.fakeCommunalSceneRepository

        ambientTouchComponentFactory =
            object : AmbientTouchComponent.Factory {
                override fun create(
                    lifecycleOwner: LifecycleOwner,
                    touchHandlers: Set<TouchHandler>
                ): AmbientTouchComponent =
                    object : AmbientTouchComponent {
                        override fun getTouchMonitor(): TouchMonitor = touchMonitor
                    }
            }

        with(kosmos) {
            underTest =
                GlanceableHubContainerController(
                    communalInteractor,
                    communalViewModel,
                    keyguardInteractor,
                    shadeInteractor,
                    powerManager,
                    communalColors,
                    ambientTouchComponentFactory,
                    communalContent,
                    kosmos.sceneDataSourceDelegator,
                    kosmos.notificationStackScrollLayoutController
                )
        }
        testableLooper = TestableLooper.get(this)

        overrideResource(R.dimen.communal_right_edge_swipe_region_width, RIGHT_SWIPE_REGION_WIDTH)
        overrideResource(R.dimen.communal_top_edge_swipe_region_height, TOP_SWIPE_REGION_WIDTH)
        overrideResource(
            R.dimen.communal_bottom_edge_swipe_region_height,
            BOTTOM_SWIPE_REGION_WIDTH
        )

        // Make communal available so that communalInteractor.desiredScene accurately reflects
        // scene changes instead of just returning Blank.
        mSetFlagsRule.enableFlags(Flags.FLAG_COMMUNAL_HUB)
        with(kosmos.testScope) {
            launch { kosmos.setCommunalAvailable(true) }
            testScheduler.runCurrent()
        }

        initAndAttachContainerView()
    }

    @After
    fun tearDown() {
        ViewUtils.detachView(parentView)
    }

    @Test
    fun initView_calledTwice_throwsException() =
        with(kosmos) {
            testScope.runTest {
                underTest =
                    GlanceableHubContainerController(
                        communalInteractor,
                        communalViewModel,
                        keyguardInteractor,
                        shadeInteractor,
                        powerManager,
                        communalColors,
                        ambientTouchComponentFactory,
                        communalContent,
                        kosmos.sceneDataSourceDelegator,
                        kosmos.notificationStackScrollLayoutController
                    )

                // First call succeeds.
                underTest.initView(context)

                // Second call throws.
                assertThrows(RuntimeException::class.java) { underTest.initView(context) }
            }
        }

    @Test
    fun lifecycle_initializedAfterConstruction() =
        with(kosmos) {
            val underTest =
                GlanceableHubContainerController(
                    communalInteractor,
                    communalViewModel,
                    keyguardInteractor,
                    shadeInteractor,
                    powerManager,
                    communalColors,
                    ambientTouchComponentFactory,
                    communalContent,
                    kosmos.sceneDataSourceDelegator,
                    kosmos.notificationStackScrollLayoutController,
                )

            assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.INITIALIZED)
        }

    @Test
    fun lifecycle_createdAfterViewCreated() =
        with(kosmos) {
            val underTest =
                GlanceableHubContainerController(
                    communalInteractor,
                    communalViewModel,
                    keyguardInteractor,
                    shadeInteractor,
                    powerManager,
                    communalColors,
                    ambientTouchComponentFactory,
                    communalContent,
                    kosmos.sceneDataSourceDelegator,
                    kosmos.notificationStackScrollLayoutController,
                )

            // Only initView without attaching a view as we don't want the flows to start collecting
            // yet.
            underTest.initView(View(context))

            assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
        }

    @Test
    fun lifecycle_startedAfterFlowsUpdate() {
        // Flows start collecting due to test setup, causing the state to advance to STARTED.
        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
    }

    @Test
    fun lifecycle_resumedAfterCommunalShows() {
        // Communal is open.
        goToScene(CommunalScenes.Communal)

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)
    }

    @Test
    fun lifecycle_startedAfterCommunalCloses() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.RESUMED)

                // Communal closes.
                goToScene(CommunalScenes.Blank)

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun lifecycle_startedAfterPrimaryBouncerShows() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Bouncer is visible.
                fakeKeyguardBouncerRepository.setPrimaryShow(true)
                testableLooper.processAllMessages()

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun lifecycle_startedAfterAlternateBouncerShows() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Bouncer is visible.
                fakeKeyguardBouncerRepository.setAlternateVisible(true)
                testableLooper.processAllMessages()

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun lifecycle_createdAfterDisposeView() {
        // Container view disposed.
        underTest.disposeView()

        assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.CREATED)
    }

    @Test
    fun lifecycle_startedAfterShadeShows() =
        with(kosmos) {
            testScope.runTest {
                // Communal is open.
                goToScene(CommunalScenes.Communal)

                // Shade shows up.
                shadeTestUtil.setQsExpansion(1.0f)
                testableLooper.processAllMessages()

                assertThat(underTest.lifecycle.currentState).isEqualTo(Lifecycle.State.STARTED)
            }
        }

    @Test
    fun editMode_communalAvailable() =
        with(kosmos) {
            testScope.runTest {
                val available by collectLastValue(underTest.communalAvailable())
                setCommunalAvailable(false)

                assertThat(available).isFalse()
                communalInteractor.setEditModeOpen(true)
                assertThat(available).isTrue()
            }
        }

    @Test
    @DisableFlags(FLAG_GLANCEABLE_HUB_BACK_GESTURE)
    fun gestureExclusionZone_setAfterInit() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                assertThat(containerView.systemGestureExclusionRects)
                    .containsExactly(
                        Rect(
                            /* left= */ 0,
                            /* top= */ TOP_SWIPE_REGION_WIDTH,
                            /* right= */ CONTAINER_WIDTH,
                            /* bottom= */ CONTAINER_HEIGHT - BOTTOM_SWIPE_REGION_WIDTH
                        ),
                        Rect(
                            /* left= */ 0,
                            /* top= */ 0,
                            /* right= */ 0,
                            /* bottom= */ CONTAINER_HEIGHT
                        )
                    )
            }
        }

    @Test
    @EnableFlags(FLAG_GLANCEABLE_HUB_BACK_GESTURE)
    fun gestureExclusionZone_setAfterInit_backGestureEnabled() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                assertThat(containerView.systemGestureExclusionRects)
                    .containsExactly(
                        Rect(
                            /* left= */ FAKE_INSETS.left,
                            /* top= */ TOP_SWIPE_REGION_WIDTH,
                            /* right= */ CONTAINER_WIDTH - FAKE_INSETS.right,
                            /* bottom= */ CONTAINER_HEIGHT - BOTTOM_SWIPE_REGION_WIDTH
                        ),
                        Rect(
                            /* left= */ 0,
                            /* top= */ 0,
                            /* right= */ FAKE_INSETS.right,
                            /* bottom= */ CONTAINER_HEIGHT
                        )
                    )
            }
        }

    @Test
    fun gestureExclusionZone_unsetWhenShadeOpen() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                // Exclusion rect is set.
                assertThat(containerView.systemGestureExclusionRects).isNotEmpty()

                // Shade shows up.
                shadeTestUtil.setQsExpansion(1.0f)
                testableLooper.processAllMessages()

                // Exclusion rects are unset.
                assertThat(containerView.systemGestureExclusionRects).isEmpty()
            }
        }

    @Test
    fun gestureExclusionZone_unsetWhenBouncerOpen() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                // Exclusion rect is set.
                assertThat(containerView.systemGestureExclusionRects).isNotEmpty()

                // Bouncer is visible.
                fakeKeyguardBouncerRepository.setPrimaryShow(true)
                testableLooper.processAllMessages()

                // Exclusion rects are unset.
                assertThat(containerView.systemGestureExclusionRects).isEmpty()
            }
        }

    @Test
    fun gestureExclusionZone_unsetWhenHubClosed() =
        with(kosmos) {
            testScope.runTest {
                goToScene(CommunalScenes.Communal)

                // Exclusion rect is set.
                assertThat(containerView.systemGestureExclusionRects).isNotEmpty()

                // Leave the hub.
                goToScene(CommunalScenes.Blank)

                // Exclusion rect is unset.
                assertThat(containerView.systemGestureExclusionRects).isEmpty()
            }
        }

    @Test
    fun fullScreenSwipeGesture_doNotProcessTouchesInNotificationStack() =
        with(kosmos) {
            testScope.runTest {
                // Communal is closed.
                goToScene(CommunalScenes.Blank)
                `when`(
                        notificationStackScrollLayoutController.isBelowLastNotification(
                            anyFloat(),
                            anyFloat()
                        )
                    )
                    .thenReturn(false)
                assertThat(underTest.onTouchEvent(DOWN_EVENT)).isFalse()
            }
        }

    private fun initAndAttachContainerView() {
        val mockInsets =
            mock<WindowInsets> {
                on { getInsets(WindowInsets.Type.systemGestures()) } doReturn FAKE_INSETS
            }

        containerView = spy(View(context)) { on { rootWindowInsets } doReturn mockInsets }

        parentView = FrameLayout(context)

        parentView.addView(underTest.initView(containerView))

        // Attach the view so that flows start collecting.
        ViewUtils.attachView(parentView, CONTAINER_WIDTH, CONTAINER_HEIGHT)
        // Attaching is async so processAllMessages is required for view.repeatWhenAttached to run.
        testableLooper.processAllMessages()
    }

    private fun goToScene(scene: SceneKey) {
        communalRepository.changeScene(scene)
        testableLooper.processAllMessages()
    }

    companion object {
        private const val CONTAINER_WIDTH = 100
        private const val CONTAINER_HEIGHT = 100
        private const val RIGHT_SWIPE_REGION_WIDTH = 20
        private const val TOP_SWIPE_REGION_WIDTH = 12
        private const val BOTTOM_SWIPE_REGION_WIDTH = 14
        private val FAKE_INSETS = Insets.of(10, 20, 30, 50)

        /**
         * A touch down event right in the middle of the screen, to avoid being in any of the swipe
         * regions.
         */
        private val DOWN_EVENT =
            MotionEvent.obtain(
                0L,
                0L,
                MotionEvent.ACTION_DOWN,
                CONTAINER_WIDTH.toFloat() / 2,
                CONTAINER_HEIGHT.toFloat() / 2,
                0
            )
    }
}
