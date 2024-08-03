/*
 * Copyright (C) 2022 The Android Open Source Project
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
package com.android.wm.shell.windowdecor

import android.app.ActivityManager.RunningTaskInfo
import android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD
import android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED
import android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM
import android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN
import android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW
import android.app.WindowConfiguration.WINDOWING_MODE_PINNED
import android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED
import android.app.WindowConfiguration.WindowingMode
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Handler
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import android.testing.TestableContext
import android.testing.TestableLooper.RunWithLooper
import android.util.SparseArray
import android.view.Choreographer
import android.view.Display.DEFAULT_DISPLAY
import android.view.IWindowManager
import android.view.InputChannel
import android.view.InputMonitor
import android.view.InsetsSource
import android.view.InsetsState
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets.Type.navigationBars
import android.view.WindowInsets.Type.statusBars
import android.window.WindowContainerTransaction
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession
import com.android.dx.mockito.inline.extended.StaticMockitoSession
import com.android.internal.jank.InteractionJankMonitor
import com.android.window.flags.Flags
import com.android.wm.shell.R
import com.android.wm.shell.RootTaskDisplayAreaOrganizer
import com.android.wm.shell.ShellTaskOrganizer
import com.android.wm.shell.ShellTestCase
import com.android.wm.shell.TestRunningTaskInfoBuilder
import com.android.wm.shell.TestShellExecutor
import com.android.wm.shell.apptoweb.AppToWebGenericLinksParser
import com.android.wm.shell.common.DisplayChangeController
import com.android.wm.shell.common.DisplayController
import com.android.wm.shell.common.DisplayInsetsController
import com.android.wm.shell.common.DisplayLayout
import com.android.wm.shell.common.MultiInstanceHelper
import com.android.wm.shell.common.ShellExecutor
import com.android.wm.shell.common.SyncTransactionQueue
import com.android.wm.shell.common.desktopmode.DesktopModeTransitionSource
import com.android.wm.shell.desktopmode.DesktopTasksController
import com.android.wm.shell.desktopmode.DesktopTasksController.SnapPosition
import com.android.wm.shell.freeform.FreeformTaskTransitionStarter
import com.android.wm.shell.shared.desktopmode.DesktopModeStatus
import com.android.wm.shell.splitscreen.SplitScreenController
import com.android.wm.shell.sysui.KeyguardChangeListener
import com.android.wm.shell.sysui.ShellCommandHandler
import com.android.wm.shell.sysui.ShellController
import com.android.wm.shell.sysui.ShellInit
import com.android.wm.shell.transition.Transitions
import com.android.wm.shell.windowdecor.DesktopModeWindowDecorViewModel.DesktopModeOnInsetsChangedListener
import java.util.Optional
import java.util.function.Consumer
import java.util.function.Supplier
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentCaptor.forClass
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue

/**
 * Tests of [DesktopModeWindowDecorViewModel]
 * Usage: atest WMShellUnitTests:DesktopModeWindowDecorViewModelTests
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@RunWithLooper
class DesktopModeWindowDecorViewModelTests : ShellTestCase() {
    @JvmField
    @Rule
    val setFlagsRule = SetFlagsRule()

    @JvmField
    @Rule
    val mCheckFlagsRule: CheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Mock private lateinit var mockDesktopModeWindowDecorFactory:
            DesktopModeWindowDecoration.Factory
    @Mock private lateinit var mockMainHandler: Handler
    @Mock private lateinit var mockMainChoreographer: Choreographer
    @Mock private lateinit var mockTaskOrganizer: ShellTaskOrganizer
    @Mock private lateinit var mockDisplayController: DisplayController
    @Mock private lateinit var mockSplitScreenController: SplitScreenController
    @Mock private lateinit var mockDisplayLayout: DisplayLayout
    @Mock private lateinit var displayInsetsController: DisplayInsetsController
    @Mock private lateinit var mockSyncQueue: SyncTransactionQueue
    @Mock private lateinit var mockDesktopTasksController: DesktopTasksController
    @Mock private lateinit var mockInputMonitor: InputMonitor
    @Mock private lateinit var mockTransitions: Transitions
    @Mock private lateinit var mockInputMonitorFactory:
            DesktopModeWindowDecorViewModel.InputMonitorFactory
    @Mock private lateinit var mockShellController: ShellController
    @Mock private lateinit var mockShellExecutor: ShellExecutor
    @Mock private lateinit var mockRootTaskDisplayAreaOrganizer: RootTaskDisplayAreaOrganizer
    @Mock private lateinit var mockShellCommandHandler: ShellCommandHandler
    @Mock private lateinit var mockWindowManager: IWindowManager
    @Mock private lateinit var mockInteractionJankMonitor: InteractionJankMonitor
    @Mock private lateinit var mockGenericLinksParser: AppToWebGenericLinksParser
    private val bgExecutor = TestShellExecutor()
    @Mock private lateinit var mockMultiInstanceHelper: MultiInstanceHelper
    private lateinit var spyContext: TestableContext

    private val transactionFactory = Supplier<SurfaceControl.Transaction> {
        SurfaceControl.Transaction()
    }
    private val windowDecorByTaskIdSpy = spy(SparseArray<DesktopModeWindowDecoration>())

    private lateinit var mockitoSession: StaticMockitoSession
    private lateinit var shellInit: ShellInit
    private lateinit var desktopModeOnInsetsChangedListener: DesktopModeOnInsetsChangedListener
    private lateinit var displayChangingListener: DisplayChangeController.OnDisplayChangingListener
    private lateinit var desktopModeWindowDecorViewModel: DesktopModeWindowDecorViewModel

    @Before
    fun setUp() {
        mockitoSession =
            mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(DesktopModeStatus::class.java)
                .spyStatic(DragPositioningCallbackUtility::class.java)
                .startMocking()
        doReturn(true).`when` { DesktopModeStatus.isDesktopModeSupported(Mockito.any()) }

        spyContext = spy(mContext)
        doNothing().`when`(spyContext).startActivity(any())
        shellInit = ShellInit(mockShellExecutor)
        windowDecorByTaskIdSpy.clear()
        desktopModeWindowDecorViewModel = DesktopModeWindowDecorViewModel(
                spyContext,
                mockShellExecutor,
                mockMainHandler,
                mockMainChoreographer,
                bgExecutor,
                shellInit,
                mockShellCommandHandler,
                mockWindowManager,
                mockTaskOrganizer,
                mockDisplayController,
                mockShellController,
                displayInsetsController,
                mockSyncQueue,
                mockTransitions,
                Optional.of(mockDesktopTasksController),
                mockGenericLinksParser,
                mockMultiInstanceHelper,
                mockDesktopModeWindowDecorFactory,
                mockInputMonitorFactory,
                transactionFactory,
                mockRootTaskDisplayAreaOrganizer,
                windowDecorByTaskIdSpy,
                mockInteractionJankMonitor
        )
        desktopModeWindowDecorViewModel.setSplitScreenController(mockSplitScreenController)
        whenever(mockDisplayController.getDisplayLayout(any())).thenReturn(mockDisplayLayout)
        whenever(mockDisplayLayout.stableInsets()).thenReturn(STABLE_INSETS)
        whenever(mockInputMonitorFactory.create(any(), any())).thenReturn(mockInputMonitor)

        // InputChannel cannot be mocked because it passes to InputEventReceiver.
        val inputChannels = InputChannel.openInputChannelPair(TAG)
        inputChannels.first().dispose()
        whenever(mockInputMonitor.inputChannel).thenReturn(inputChannels[1])

        shellInit.init()

        val insetListenerCaptor =
            argumentCaptor<DesktopModeWindowDecorViewModel.DesktopModeOnInsetsChangedListener>()
        verify(displayInsetsController)
            .addInsetsChangedListener(anyInt(), insetListenerCaptor.capture())
        desktopModeOnInsetsChangedListener = insetListenerCaptor.firstValue

        val displayChangingListenerCaptor =
            argumentCaptor<DisplayChangeController.OnDisplayChangingListener>()
        verify(mockDisplayController)
            .addDisplayChangingController(displayChangingListenerCaptor.capture())
        displayChangingListener = displayChangingListenerCaptor.firstValue
    }

    @After
    fun tearDown() {
        mockitoSession.finishMocking()
    }

    @Test
    fun testDeleteCaptionOnChangeTransitionWhenNecessary() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val taskSurface = SurfaceControl()
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task, taskSurface)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))

        task.setWindowingMode(WINDOWING_MODE_UNDEFINED)
        task.setActivityType(ACTIVITY_TYPE_UNDEFINED)
        onTaskChanging(task, taskSurface)

        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
        verify(decoration).close()
    }

    @Test
    fun testCreateCaptionOnChangeTransitionWhenNecessary() {
        val task = createTask(
                windowingMode = WINDOWING_MODE_UNDEFINED,
                activityType = ACTIVITY_TYPE_UNDEFINED
        )
        val taskSurface = SurfaceControl()
        setUpMockDecorationForTask(task)

        onTaskChanging(task, taskSurface)
        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))

        task.setWindowingMode(WINDOWING_MODE_FREEFORM)
        task.setActivityType(ACTIVITY_TYPE_STANDARD)
        onTaskChanging(task, taskSurface)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_ADDITIONAL_WINDOWS_ABOVE_STATUS_BAR)
    fun testCreateAndDisposeEventReceiver() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        setUpMockDecorationForTask(task)

        onTaskOpening(task)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(task)

        verify(mockInputMonitorFactory).create(any(), any())
        verify(mockInputMonitor).dispose()
    }

    @Test
    @DisableFlags(Flags.FLAG_ENABLE_ADDITIONAL_WINDOWS_ABOVE_STATUS_BAR)
    fun testEventReceiversOnMultipleDisplays() {
        val secondaryDisplay = createVirtualDisplay() ?: return
        val secondaryDisplayId = secondaryDisplay.display.displayId
        val task = createTask(displayId = DEFAULT_DISPLAY, windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask = createTask(
                displayId = secondaryDisplayId,
                windowingMode = WINDOWING_MODE_FREEFORM
        )
        val thirdTask = createTask(
                displayId = secondaryDisplayId,
                windowingMode = WINDOWING_MODE_FREEFORM
        )
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(thirdTask)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(task)
        secondaryDisplay.release()

        verify(mockInputMonitorFactory, times(2)).create(any(), any())
        verify(mockInputMonitor, times(1)).dispose()
    }

    @Test
    fun testBackEventHasRightDisplayId() {
        val secondaryDisplay = createVirtualDisplay() ?: return
        val secondaryDisplayId = secondaryDisplay.display.displayId
        val task = createTask(
            displayId = secondaryDisplayId,
            windowingMode = WINDOWING_MODE_FREEFORM
        )
        val windowDecor = setUpMockDecorationForTask(task)

        onTaskOpening(task)
        val onClickListenerCaptor = argumentCaptor<View.OnClickListener>()
        verify(windowDecor).setCaptionListeners(
            onClickListenerCaptor.capture(), any(), any(), any())

        val onClickListener = onClickListenerCaptor.firstValue
        val view = mock(View::class.java)
        whenever(view.id).thenReturn(R.id.back_button)

        val inputManager = mock(InputManager::class.java)
        spyContext.addMockSystemService(InputManager::class.java, inputManager)

        val freeformTaskTransitionStarter = mock(FreeformTaskTransitionStarter::class.java)
        desktopModeWindowDecorViewModel
                .setFreeformTaskTransitionStarter(freeformTaskTransitionStarter)

        onClickListener.onClick(view)

        val eventCaptor = argumentCaptor<KeyEvent>()
        verify(inputManager, times(2)).injectInputEvent(eventCaptor.capture(), anyInt())

        assertEquals(secondaryDisplayId, eventCaptor.firstValue.displayId)
        assertEquals(secondaryDisplayId, eventCaptor.secondValue.displayId)
    }

    @Test
    fun testCaptionIsNotCreatedWhenKeyguardIsVisible() {
        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true)
        val keyguardListenerCaptor = argumentCaptor<KeyguardChangeListener>()
        verify(mockShellController).addKeyguardChangeListener(keyguardListenerCaptor.capture())

        keyguardListenerCaptor.firstValue.onKeyguardVisibilityChanged(
                true /* visible */,
                true /* occluded */,
                false /* animatingDismiss */
        )
        onTaskOpening(task)

        task.setWindowingMode(WINDOWING_MODE_UNDEFINED)
        task.setWindowingMode(ACTIVITY_TYPE_UNDEFINED)
        onTaskChanging(task)

        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
    fun testDecorationIsCreatedForTopTranslucentActivitiesWithStyleFloating() {
        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true).apply {
            isTopActivityTransparent = true
            isTopActivityStyleFloating = true
            numActivities = 1
        }
        doReturn(true).`when` { DesktopModeStatus.canEnterDesktopMode(any()) }
        setUpMockDecorationsForTasks(task)

        onTaskOpening(task)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
    fun testDecorationIsNotCreatedForTopTranslucentActivitiesWithoutStyleFloating() {
        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true).apply {
            isTopActivityTransparent = true
            isTopActivityStyleFloating = false
            numActivities = 1
        }
        onTaskOpening(task)

        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODALS_POLICY)
    fun testDecorationIsNotCreatedForSystemUIActivities() {
        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true)

        // Set task as systemUI package
        val systemUIPackageName = context.resources.getString(
            com.android.internal.R.string.config_systemUi)
        val baseComponent = ComponentName(systemUIPackageName, /* class */ "")
        task.baseActivity = baseComponent

        onTaskOpening(task)

        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_IMMERSIVE_HANDLE_HIDING)
    fun testRelayoutRunsWhenStatusBarsInsetsSourceVisibilityChanges() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM, focused = true)
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task)

        // Add status bar insets source
        val insetsState = InsetsState()
        val statusBarInsetsSourceId = 0
        val statusBarInsetsSource = InsetsSource(statusBarInsetsSourceId, statusBars())
        statusBarInsetsSource.isVisible = false
        insetsState.addSource(statusBarInsetsSource)

        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)

        // Verify relayout occurs when status bar inset visibility changes
        verify(decoration, times(1)).relayout(task)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_IMMERSIVE_HANDLE_HIDING)
    fun testRelayoutDoesNotRunWhenNonStatusBarsInsetsSourceVisibilityChanges() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM, focused = true)
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task)

        // Add navigation bar insets source
        val insetsState = InsetsState()
        val navigationBarInsetsSourceId = 1
        val navigationBarInsetsSource = InsetsSource(navigationBarInsetsSourceId, navigationBars())
        navigationBarInsetsSource.isVisible = false
        insetsState.addSource(navigationBarInsetsSource)

        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)

        // Verify relayout does not occur when non-status bar inset changes visibility
        verify(decoration, never()).relayout(task)
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_IMMERSIVE_HANDLE_HIDING)
    fun testRelayoutDoesNotRunWhenNonStatusBarsInsetSourceVisibilityDoesNotChange() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM, focused = true)
        val decoration = setUpMockDecorationForTask(task)

        onTaskOpening(task)

        // Add status bar insets source
        val insetsState = InsetsState()
        val statusBarInsetsSourceId = 0
        val statusBarInsetsSource = InsetsSource(statusBarInsetsSourceId, statusBars())
        statusBarInsetsSource.isVisible = false
        insetsState.addSource(statusBarInsetsSource)

        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)
        desktopModeOnInsetsChangedListener.insetsChanged(insetsState)

        // Verify relayout runs only once when status bar inset visibility changes.
        verify(decoration, times(1)).relayout(task)
    }

    @Test
    fun testDestroyWindowDecoration_closesBeforeCleanup() {
        val task = createTask(windowingMode = WINDOWING_MODE_FREEFORM)
        val decoration = setUpMockDecorationForTask(task)
        val inOrder = Mockito.inOrder(decoration, windowDecorByTaskIdSpy)

        onTaskOpening(task)
        desktopModeWindowDecorViewModel.destroyWindowDecoration(task)

        inOrder.verify(decoration).close()
        inOrder.verify(windowDecorByTaskIdSpy).remove(task.taskId)
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    fun testWindowDecor_desktopModeUnsupportedOnDevice_decorNotCreated() {
        // Simulate default enforce device restrictions system property
        whenever(DesktopModeStatus.enforceDeviceRestrictions()).thenReturn(true)

        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true)
        // Simulate device that doesn't support desktop mode
        doReturn(false).`when` { DesktopModeStatus.isDesktopModeSupported(any()) }

        onTaskOpening(task)
        assertFalse(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    fun testWindowDecor_desktopModeUnsupportedOnDevice_deviceRestrictionsOverridden_decorCreated() {
        // Simulate enforce device restrictions system property overridden to false
        whenever(DesktopModeStatus.enforceDeviceRestrictions()).thenReturn(false)
        // Simulate device that doesn't support desktop mode
        doReturn(false).`when` { DesktopModeStatus.isDesktopModeSupported(any()) }

        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true)
        setUpMockDecorationsForTasks(task)

        onTaskOpening(task)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    @EnableFlags(Flags.FLAG_ENABLE_DESKTOP_WINDOWING_MODE)
    fun testWindowDecor_deviceSupportsDesktopMode_decorCreated() {
        // Simulate default enforce device restrictions system property
        whenever(DesktopModeStatus.enforceDeviceRestrictions()).thenReturn(true)

        val task = createTask(windowingMode = WINDOWING_MODE_FULLSCREEN, focused = true)
        doReturn(true).`when` { DesktopModeStatus.isDesktopModeSupported(any()) }
        setUpMockDecorationsForTasks(task)

        onTaskOpening(task)
        assertTrue(windowDecorByTaskIdSpy.contains(task.taskId))
    }

    @Test
    fun testOnDecorMaximizedOrRestored_togglesTaskSize() {
        val maxOrRestoreListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onMaxOrRestoreListenerCaptor = maxOrRestoreListenerCaptor
        )

        maxOrRestoreListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).toggleDesktopTaskSize(decor.mTaskInfo)
    }

    @Test
    fun testOnDecorMaximizedOrRestored_closesMenus() {
        val maxOrRestoreListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onMaxOrRestoreListenerCaptor = maxOrRestoreListenerCaptor
        )

        maxOrRestoreListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    fun testOnDecorSnappedLeft_snapResizes() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        )

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onLeftSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController)
            .snapToHalfScreen(decor.mTaskInfo, currentBounds, SnapPosition.LEFT)
    }

    @Test
    fun testOnDecorSnappedLeft_closeMenus() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        )

        onLeftSnapClickListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    @DisableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeLeft_nonResizable_decorSnappedLeft() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onLeftSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController)
            .snapToHalfScreen(decor.mTaskInfo, currentBounds, SnapPosition.LEFT)
    }

    @Test
    @EnableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeLeft_nonResizable_decorNotSnapped() {
        val onLeftSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onLeftSnapClickListenerCaptor = onLeftSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onLeftSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController, never())
            .snapToHalfScreen(decor.mTaskInfo, currentBounds, SnapPosition.LEFT)
    }

    @Test
    fun testOnDecorSnappedRight_snapResizes() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        )

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onRightSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController)
            .snapToHalfScreen(decor.mTaskInfo, currentBounds, SnapPosition.RIGHT)
    }

    @Test
    fun testOnDecorSnappedRight_closeMenus() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        )

        onRightSnapClickListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    @DisableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeRight_nonResizable_decorSnappedRight() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onRightSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController)
            .snapToHalfScreen(decor.mTaskInfo, currentBounds, SnapPosition.RIGHT)
    }

    @Test
    @EnableFlags(Flags.FLAG_DISABLE_NON_RESIZABLE_APP_SNAP_RESIZING)
    fun testOnSnapResizeRight_nonResizable_decorNotSnapped() {
        val onRightSnapClickListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onRightSnapClickListenerCaptor = onRightSnapClickListenerCaptor
        ).also { it.mTaskInfo.isResizeable = false }

        val currentBounds = decor.mTaskInfo.configuration.windowConfiguration.bounds
        onRightSnapClickListenerCaptor.value.invoke()

        verify(mockDesktopTasksController, never())
            .snapToHalfScreen(decor.mTaskInfo, currentBounds, SnapPosition.RIGHT)
    }

    @Test
    fun testDecor_onClickToDesktop_movesToDesktopWithSource() {
        val toDesktopListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onToDesktopClickListenerCaptor = toDesktopListenerCaptor
        )

        toDesktopListenerCaptor.value.accept(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON)

        verify(mockDesktopTasksController).moveTaskToDesktop(
            eq(decor.mTaskInfo.taskId),
            any(),
            eq(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON)
        )
    }

    @Test
    fun testDecor_onClickToDesktop_addsCaptionInsets() {
        val toDesktopListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onToDesktopClickListenerCaptor = toDesktopListenerCaptor
        )

        toDesktopListenerCaptor.value.accept(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON)

        verify(decor).addCaptionInset(any())
    }

    @Test
    fun testDecor_onClickToDesktop_closesHandleMenu() {
        val toDesktopListenerCaptor = forClass(Consumer::class.java)
                    as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onToDesktopClickListenerCaptor = toDesktopListenerCaptor
        )

        toDesktopListenerCaptor.value.accept(DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON)

        verify(decor).closeHandleMenu()
    }

    @Test
    fun testDecor_onClickToFullscreen_closesHandleMenu() {
        val toFullscreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onToFullscreenClickListenerCaptor = toFullscreenListenerCaptor
        )

        toFullscreenListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
    }

    @Test
    fun testDecor_onClickToFullscreen_isFreeform_movesToFullscreen() {
        val toFullscreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FREEFORM,
            onToFullscreenClickListenerCaptor = toFullscreenListenerCaptor
        )

        toFullscreenListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).moveToFullscreen(
            decor.mTaskInfo.taskId,
            DesktopModeTransitionSource.APP_HANDLE_MENU_BUTTON
        )
    }

    @Test
    fun testDecor_onClickToFullscreen_isSplit_movesToFullscreen() {
        val toFullscreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToFullscreenClickListenerCaptor = toFullscreenListenerCaptor
        )

        toFullscreenListenerCaptor.value.invoke()

        verify(mockSplitScreenController).moveTaskToFullscreen(
            decor.mTaskInfo.taskId,
            SplitScreenController.EXIT_REASON_DESKTOP_MODE
        )
    }

    @Test
    fun testDecor_onClickToSplitScreen_closesHandleMenu() {
        val toSplitScreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToSplitScreenClickListenerCaptor = toSplitScreenListenerCaptor
        )

        toSplitScreenListenerCaptor.value.invoke()

        verify(decor).closeHandleMenu()
    }

    @Test
    fun testDecor_onClickToSplitScreen_requestsSplit() {
        val toSplitScreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToSplitScreenClickListenerCaptor = toSplitScreenListenerCaptor
        )

        toSplitScreenListenerCaptor.value.invoke()

        verify(mockDesktopTasksController).requestSplit(decor.mTaskInfo, leftOrTop = false)
    }

    @Test
    fun testDecor_onClickToSplitScreen_disposesStatusBarInputLayer() {
        val toSplitScreenListenerCaptor = forClass(Function0::class.java)
                as ArgumentCaptor<Function0<Unit>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_MULTI_WINDOW,
            onToSplitScreenClickListenerCaptor = toSplitScreenListenerCaptor
        )

        toSplitScreenListenerCaptor.value.invoke()

        verify(decor).disposeStatusBarInputLayer()
    }

    @Test
    fun testDecor_onClickToOpenBrowser_closeMenus() {
        val openInBrowserListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<Uri>>
        val decor = createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onOpenInBrowserClickListener = openInBrowserListenerCaptor
        )

        openInBrowserListenerCaptor.value.accept(Uri.EMPTY)

        verify(decor).closeHandleMenu()
        verify(decor).closeMaximizeMenu()
    }

    @Test
    fun testDecor_onClickToOpenBrowser_opensBrowser() {
        doNothing().whenever(spyContext).startActivity(any())
        val uri = Uri.parse("https://www.google.com")
        val openInBrowserListenerCaptor = forClass(Consumer::class.java)
                as ArgumentCaptor<Consumer<Uri>>
        createOpenTaskDecoration(
            windowingMode = WINDOWING_MODE_FULLSCREEN,
            onOpenInBrowserClickListener = openInBrowserListenerCaptor
        )

        openInBrowserListenerCaptor.value.accept(uri)

        verify(spyContext).startActivity(argThat { intent ->
            intent.data == uri
                    && ((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
                    && intent.categories.contains(Intent.CATEGORY_LAUNCHER)
                    && intent.action == Intent.ACTION_MAIN
        })
    }

    @Test
    fun testOnDisplayRotation_tasksOutOfValidArea_taskBoundsUpdated() {
        val task = createTask(focused = true, windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)

        doReturn(true).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()

        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct).setBounds(eq(task.token), any())
        verify(wct).setBounds(eq(secondTask.token), any())
        verify(wct).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_taskInValidArea_taskBoundsNotUpdated() {
        val task = createTask(focused = true, windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)

        doReturn(false).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct, never()).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_sameOrientationRotation_taskBoundsNotUpdated() {
        val task = createTask(focused = true, windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask =
            createTask(displayId = task.displayId, windowingMode = WINDOWING_MODE_FREEFORM)

        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_180, null, wct
        )

        verify(wct, never()).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_differentDisplayId_taskBoundsNotUpdated() {
        val task = createTask(focused = true, windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask = createTask(displayId = -2, windowingMode = WINDOWING_MODE_FREEFORM)
        val thirdTask = createTask(displayId = -3, windowingMode = WINDOWING_MODE_FREEFORM)

        doReturn(true).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    @Test
    fun testOnDisplayRotation_nonFreeformTask_taskBoundsNotUpdated() {
        val task = createTask(focused = true, windowingMode = WINDOWING_MODE_FREEFORM)
        val secondTask = createTask(displayId = -2, windowingMode = WINDOWING_MODE_FULLSCREEN)
        val thirdTask = createTask(displayId = -3, windowingMode = WINDOWING_MODE_PINNED)

        doReturn(true).`when` {
            DragPositioningCallbackUtility.snapTaskBoundsIfNecessary(any(), any())
        }
        setUpMockDecorationsForTasks(task, secondTask, thirdTask)

        onTaskOpening(task)
        onTaskOpening(secondTask)
        onTaskOpening(thirdTask)

        val wct = mock<WindowContainerTransaction>()
        displayChangingListener.onDisplayChange(
            task.displayId, Surface.ROTATION_0, Surface.ROTATION_90, null, wct
        )

        verify(wct).setBounds(eq(task.token), any())
        verify(wct, never()).setBounds(eq(secondTask.token), any())
        verify(wct, never()).setBounds(eq(thirdTask.token), any())
    }

    private fun createOpenTaskDecoration(
        @WindowingMode windowingMode: Int,
        onMaxOrRestoreListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onLeftSnapClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onRightSnapClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onToDesktopClickListenerCaptor: ArgumentCaptor<Consumer<DesktopModeTransitionSource>> =
            forClass(Consumer::class.java) as ArgumentCaptor<Consumer<DesktopModeTransitionSource>>,
        onToFullscreenClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onToSplitScreenClickListenerCaptor: ArgumentCaptor<Function0<Unit>> =
            forClass(Function0::class.java) as ArgumentCaptor<Function0<Unit>>,
        onOpenInBrowserClickListener: ArgumentCaptor<Consumer<Uri>> =
            forClass(Consumer::class.java) as ArgumentCaptor<Consumer<Uri>>,
    ): DesktopModeWindowDecoration {
        val decor = setUpMockDecorationForTask(createTask(windowingMode = windowingMode))
        onTaskOpening(decor.mTaskInfo)
        verify(decor).setOnMaximizeOrRestoreClickListener(onMaxOrRestoreListenerCaptor.capture())
        verify(decor).setOnLeftSnapClickListener(onLeftSnapClickListenerCaptor.capture())
        verify(decor).setOnRightSnapClickListener(onRightSnapClickListenerCaptor.capture())
        verify(decor).setOnToDesktopClickListener(onToDesktopClickListenerCaptor.capture())
        verify(decor).setOnToFullscreenClickListener(onToFullscreenClickListenerCaptor.capture())
        verify(decor).setOnToSplitScreenClickListener(onToSplitScreenClickListenerCaptor.capture())
        verify(decor).setOpenInBrowserClickListener(onOpenInBrowserClickListener.capture())
        return decor
    }

    private fun onTaskOpening(task: RunningTaskInfo, leash: SurfaceControl = SurfaceControl()) {
        desktopModeWindowDecorViewModel.onTaskOpening(
                task,
                leash,
                SurfaceControl.Transaction(),
                SurfaceControl.Transaction()
        )
    }

    private fun onTaskChanging(task: RunningTaskInfo, leash: SurfaceControl = SurfaceControl()) {
        desktopModeWindowDecorViewModel.onTaskChanging(
                task,
                leash,
                SurfaceControl.Transaction(),
                SurfaceControl.Transaction()
        )
    }

    private fun createTask(
            displayId: Int = DEFAULT_DISPLAY,
            @WindowingMode windowingMode: Int,
            activityType: Int = ACTIVITY_TYPE_STANDARD,
            focused: Boolean = true,
            activityInfo: ActivityInfo = ActivityInfo(),
    ): RunningTaskInfo {
        return TestRunningTaskInfoBuilder()
                .setDisplayId(displayId)
                .setWindowingMode(windowingMode)
                .setVisible(true)
                .setActivityType(activityType)
                .build().apply {
                    topActivityInfo = activityInfo
                    isFocused = focused
                    isResizeable = true
                }
    }

    private fun setUpMockDecorationForTask(task: RunningTaskInfo): DesktopModeWindowDecoration {
        val decoration = mock(DesktopModeWindowDecoration::class.java)
        whenever(
            mockDesktopModeWindowDecorFactory.create(
                any(), any(), any(), any(), any(), eq(task), any(), any(), any(), any(), any(),
                any(), any(), any())
        ).thenReturn(decoration)
        decoration.mTaskInfo = task
        whenever(decoration.isFocused).thenReturn(task.isFocused)
        if (task.windowingMode == WINDOWING_MODE_MULTI_WINDOW) {
            whenever(mockSplitScreenController.isTaskInSplitScreen(task.taskId))
                .thenReturn(true)
        }
        whenever(decoration.calculateValidDragArea()).thenReturn(Rect(0, 60, 2560, 1600))
        return decoration
    }

    private fun setUpMockDecorationsForTasks(vararg tasks: RunningTaskInfo) {
        tasks.forEach { setUpMockDecorationForTask(it) }
    }

    private fun createVirtualDisplay(): VirtualDisplay? {
        val surfaceView = SurfaceView(mContext)
        val dm = mContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return dm.createVirtualDisplay(
                "testEventReceiversOnMultipleDisplays",
                /*width=*/ 400,
                /*height=*/ 400,
                /*densityDpi=*/ 320,
                surfaceView.holder.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )
    }

    private fun RunningTaskInfo.setWindowingMode(@WindowingMode mode: Int) {
        configuration.windowConfiguration.windowingMode = mode
    }

    private fun RunningTaskInfo.setActivityType(type: Int) {
        configuration.windowConfiguration.activityType = type
    }

    companion object {
        private const val TAG = "DesktopModeWindowDecorViewModelTests"
        private val STABLE_INSETS = Rect(0, 100, 0, 0)
    }
}
