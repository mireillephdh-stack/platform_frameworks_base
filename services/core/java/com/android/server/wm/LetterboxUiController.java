/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;

import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__BOTTOM;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__LEFT;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__RIGHT;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__TOP;
import static com.android.internal.util.FrameworkStatsLog.APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__UNKNOWN_POSITION;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__BOTTOM_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_BOTTOM;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_LEFT;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_RIGHT;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_TOP;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__LEFT_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__RIGHT_TO_CENTER;
import static com.android.internal.util.FrameworkStatsLog.LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__TOP_TO_CENTER;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_SOLID_COLOR;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_BACKGROUND_WALLPAPER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER;
import static com.android.server.wm.AppCompatConfiguration.LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP;
import static com.android.server.wm.AppCompatConfiguration.letterboxBackgroundTypeToString;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.TaskDescription;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Slog;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.LetterboxDetails;
import com.android.server.wm.AppCompatConfiguration.LetterboxBackgroundType;
import com.android.window.flags.Flags;

import java.io.PrintWriter;

/** Controls behaviour of the letterbox UI for {@link mActivityRecord}. */
// TODO(b/185262487): Improve test coverage of this class. Parts of it are tested in
// SizeCompatTests and LetterboxTests but not all.
final class LetterboxUiController {

    private static final String TAG = TAG_WITH_CLASS_NAME ? "LetterboxUiController" : TAG_ATM;

    private final Point mTmpPoint = new Point();

    private final AppCompatConfiguration mAppCompatConfiguration;

    private final ActivityRecord mActivityRecord;

    private boolean mShowWallpaperForLetterboxBackground;

    @Nullable
    private Letterbox mLetterbox;

    private boolean mLastShouldShowLetterboxUi;

    private boolean mDoubleTapEvent;

    LetterboxUiController(WindowManagerService wmService, ActivityRecord activityRecord) {
        mAppCompatConfiguration = wmService.mAppCompatConfiguration;
        // Given activityRecord may not be fully constructed since LetterboxUiController
        // is created in its constructor. It shouldn't be used in this constructor but it's safe
        // to use it after since controller is only used in ActivityRecord.
        mActivityRecord = activityRecord;
    }

    /** Cleans up {@link Letterbox} if it exists.*/
    void destroy() {
        if (mLetterbox != null) {
            mLetterbox.destroy();
            mLetterbox = null;
        }
    }

    void onMovedToDisplay(int displayId) {
        if (mLetterbox != null) {
            mLetterbox.onMovedToDisplay(displayId);
        }
    }

    boolean hasWallpaperBackgroundForLetterbox() {
        return mShowWallpaperForLetterboxBackground;
    }

    /** Gets the letterbox insets. The insets will be empty if there is no letterbox. */
    Rect getLetterboxInsets() {
        if (mLetterbox != null) {
            return mLetterbox.getInsets();
        } else {
            return new Rect();
        }
    }

    /** Gets the inner bounds of letterbox. The bounds will be empty if there is no letterbox. */
    void getLetterboxInnerBounds(Rect outBounds) {
        if (mLetterbox != null) {
            outBounds.set(mLetterbox.getInnerFrame());
            final WindowState w = mActivityRecord.findMainWindow();
            if (w != null) {
                adjustBoundsForTaskbar(w, outBounds);
            }
        } else {
            outBounds.setEmpty();
        }
    }

    /** Gets the outer bounds of letterbox. The bounds will be empty if there is no letterbox. */
    private void getLetterboxOuterBounds(Rect outBounds) {
        if (mLetterbox != null) {
            outBounds.set(mLetterbox.getOuterFrame());
        } else {
            outBounds.setEmpty();
        }
    }

    /**
     * @return {@code true} if bar shown within a given rectangle is allowed to be fully transparent
     *     when the current activity is displayed.
     */
    boolean isFullyTransparentBarAllowed(Rect rect) {
        return mLetterbox == null || mLetterbox.notIntersectsOrFullyContains(rect);
    }

    void updateLetterboxSurfaceIfNeeded(WindowState winHint) {
        updateLetterboxSurfaceIfNeeded(winHint, mActivityRecord.getSyncTransaction(),
                mActivityRecord.getPendingTransaction());
    }

    void updateLetterboxSurfaceIfNeeded(WindowState winHint, @NonNull Transaction t,
            @NonNull Transaction inputT) {
        if (shouldNotLayoutLetterbox(winHint)) {
            return;
        }
        layoutLetterboxIfNeeded(winHint);
        if (mLetterbox != null && mLetterbox.needsApplySurfaceChanges()) {
            mLetterbox.applySurfaceChanges(t, inputT);
        }
    }

    void layoutLetterboxIfNeeded(WindowState w) {
        if (shouldNotLayoutLetterbox(w)) {
            return;
        }
        updateRoundedCornersIfNeeded(w);
        updateWallpaperForLetterbox(w);
        if (shouldShowLetterboxUi(w)) {
            if (mLetterbox == null) {
                mLetterbox = new Letterbox(() -> mActivityRecord.makeChildSurface(null),
                        mActivityRecord.mWmService.mTransactionFactory,
                        this::shouldLetterboxHaveRoundedCorners,
                        this::getLetterboxBackgroundColor,
                        this::hasWallpaperBackgroundForLetterbox,
                        this::getLetterboxWallpaperBlurRadiusPx,
                        this::getLetterboxWallpaperDarkScrimAlpha,
                        this::handleHorizontalDoubleTap,
                        this::handleVerticalDoubleTap,
                        this::getLetterboxParentSurface);
                mLetterbox.attachInput(w);
            }

            if (mActivityRecord.isInLetterboxAnimation()) {
                // In this case we attach the letterbox to the task instead of the activity.
                mActivityRecord.getTask().getPosition(mTmpPoint);
            } else {
                mActivityRecord.getPosition(mTmpPoint);
            }

            // Get the bounds of the "space-to-fill". The transformed bounds have the highest
            // priority because the activity is launched in a rotated environment. In multi-window
            // mode, the taskFragment-level represents this for both split-screen
            // and activity-embedding. In fullscreen-mode, the task container does
            // (since the orientation letterbox is also applied to the task).
            final Rect transformedBounds = mActivityRecord.getFixedRotationTransformDisplayBounds();
            final Rect spaceToFill = transformedBounds != null
                    ? transformedBounds
                    : mActivityRecord.inMultiWindowMode()
                            ? mActivityRecord.getTaskFragment().getBounds()
                            : mActivityRecord.getRootTask().getParent().getBounds();
            // In case of translucent activities an option is to use the WindowState#getFrame() of
            // the first opaque activity beneath. In some cases (e.g. an opaque activity is using
            // non MATCH_PARENT layouts or a Dialog theme) this might not provide the correct
            // information and in particular it might provide a value for a smaller area making
            // the letterbox overlap with the translucent activity's frame.
            // If we use WindowState#getFrame() for the translucent activity's letterbox inner
            // frame, the letterbox will then be overlapped with the translucent activity's frame.
            // Because the surface layer of letterbox is lower than an activity window, this
            // won't crop the content, but it may affect other features that rely on values stored
            // in mLetterbox, e.g. transitions, a status bar scrim and recents preview in Launcher
            // For this reason we use ActivityRecord#getBounds() that the translucent activity
            // inherits from the first opaque activity beneath and also takes care of the scaling
            // in case of activities in size compat mode.
            final Rect innerFrame = mActivityRecord.mAppCompatController
                    .getTransparentPolicy().isRunning()
                    ? mActivityRecord.getBounds() : w.getFrame();
            mLetterbox.layout(spaceToFill, innerFrame, mTmpPoint);
            if (mDoubleTapEvent) {
                // We need to notify Shell that letterbox position has changed.
                mActivityRecord.getTask().dispatchTaskInfoChangedIfNeeded(true /* force */);
            }
        } else if (mLetterbox != null) {
            mLetterbox.hide();
        }
    }

    boolean isFromDoubleTap() {
        final boolean isFromDoubleTap = mDoubleTapEvent;
        mDoubleTapEvent = false;
        return isFromDoubleTap;
    }

    SurfaceControl getLetterboxParentSurface() {
        if (mActivityRecord.isInLetterboxAnimation()) {
            return mActivityRecord.getTask().getSurfaceControl();
        }
        return mActivityRecord.getSurfaceControl();
    }

    private static boolean shouldNotLayoutLetterbox(WindowState w) {
        if (w == null) {
            return true;
        }
        final int type = w.mAttrs.type;
        // Allow letterbox to be displayed early for base application or application starting
        // windows even if it is not on the top z order to prevent flickering when the
        // letterboxed window is brought to the top
        return (type != TYPE_BASE_APPLICATION && type != TYPE_APPLICATION_STARTING)
                || w.mAnimatingExit;
    }

    private boolean shouldLetterboxHaveRoundedCorners() {
        // TODO(b/214030873): remove once background is drawn for transparent activities
        // Letterbox shouldn't have rounded corners if the activity is transparent
        return mAppCompatConfiguration.isLetterboxActivityCornersRounded()
                && mActivityRecord.fillsParent();
    }

    // Check if we are in the given pose and in fullscreen mode.
    // Note that we check the task rather than the parent as with ActivityEmbedding the parent might
    // be a TaskFragment, and its windowing mode is always MULTI_WINDOW, even if the task is
    // actually fullscreen. If display is still in transition e.g. unfolding, don't return true
    // for HALF_FOLDED state or app will flicker.
    boolean isDisplayFullScreenAndInPosture(boolean isTabletop) {
        Task task = mActivityRecord.getTask();
        return mActivityRecord.mDisplayContent != null && task != null
                && mActivityRecord.mDisplayContent.getDisplayRotation().isDeviceInPosture(
                        DeviceStateController.DeviceState.HALF_FOLDED, isTabletop)
                && !mActivityRecord.mDisplayContent.inTransition()
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }

    // Note that we check the task rather than the parent as with ActivityEmbedding the parent might
    // be a TaskFragment, and its windowing mode is always MULTI_WINDOW, even if the task is
    // actually fullscreen.
    private boolean isDisplayFullScreenAndSeparatingHinge() {
        Task task = mActivityRecord.getTask();
        return mActivityRecord.mDisplayContent != null
                && mActivityRecord.mDisplayContent.getDisplayRotation().isDisplaySeparatingHinge()
                && task != null
                && task.getWindowingMode() == WINDOWING_MODE_FULLSCREEN;
    }


    float getHorizontalPositionMultiplier(Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean bookModeEnabled = isFullScreenAndBookModeEnabled();
        return isHorizontalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mAppCompatConfiguration.getHorizontalMultiplierForReachability(bookModeEnabled)
                : mAppCompatConfiguration.getLetterboxHorizontalPositionMultiplier(bookModeEnabled);
    }

    private boolean isFullScreenAndBookModeEnabled() {
        return isDisplayFullScreenAndInPosture(/* isTabletop */ false)
                && mAppCompatConfiguration.getIsAutomaticReachabilityInBookModeEnabled();
    }

    float getVerticalPositionMultiplier(Configuration parentConfiguration) {
        // Don't check resolved configuration because it may not be updated yet during
        // configuration change.
        boolean tabletopMode = isDisplayFullScreenAndInPosture(/* isTabletop */ true);
        return isVerticalReachabilityEnabled(parentConfiguration)
                // Using the last global dynamic position to avoid "jumps" when moving
                // between apps or activities.
                ? mAppCompatConfiguration.getVerticalMultiplierForReachability(tabletopMode)
                : mAppCompatConfiguration.getLetterboxVerticalPositionMultiplier(tabletopMode);
    }

    float getFixedOrientationLetterboxAspectRatio(@NonNull Configuration parentConfiguration) {
        return mActivityRecord.mAppCompatController.getAppCompatAspectRatioOverrides()
                .getFixedOrientationLetterboxAspectRatio(parentConfiguration);
    }

    boolean isLetterboxEducationEnabled() {
        return mAppCompatConfiguration.getIsEducationEnabled();
    }

    /**
     * @return {@value true} if the resulting app is letterboxed in a way defined as thin.
     */
    boolean isVerticalThinLetterboxed() {
        final int thinHeight = mAppCompatConfiguration.getThinLetterboxHeightPx();
        if (thinHeight < 0) {
            return false;
        }
        final Task task = mActivityRecord.getTask();
        if (task == null) {
            return false;
        }
        final int padding = Math.abs(
                task.getBounds().height() - mActivityRecord.getBounds().height()) / 2;
        return padding <= thinHeight;
    }

    /**
     * @return {@value true} if the resulting app is pillarboxed in a way defined as thin.
     */
    boolean isHorizontalThinLetterboxed() {
        final int thinWidth = mAppCompatConfiguration.getThinLetterboxWidthPx();
        if (thinWidth < 0) {
            return false;
        }
        final Task task = mActivityRecord.getTask();
        if (task == null) {
            return false;
        }
        final int padding = Math.abs(
                task.getBounds().width() - mActivityRecord.getBounds().width()) / 2;
        return padding <= thinWidth;
    }


    /**
     * @return {@value true} if the vertical reachability should be allowed in case of
     * thin letteboxing
     */
    boolean allowVerticalReachabilityForThinLetterbox() {
        if (!Flags.disableThinLetterboxingPolicy()) {
            return true;
        }
        // When the flag is enabled we allow vertical reachability only if the
        // app is not thin letterboxed vertically.
        return !isVerticalThinLetterboxed();
    }

    /**
     * @return {@value true} if the vertical reachability should be enabled in case of
     * thin letteboxing
     */
    boolean allowHorizontalReachabilityForThinLetterbox() {
        if (!Flags.disableThinLetterboxingPolicy()) {
            return true;
        }
        // When the flag is enabled we allow horizontal reachability only if the
        // app is not thin pillarboxed.
        return !isHorizontalThinLetterboxed();
    }

    boolean shouldOverrideMinAspectRatio() {
        return mActivityRecord.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldOverrideMinAspectRatio();
    }

    @AppCompatConfiguration.LetterboxVerticalReachabilityPosition
    int getLetterboxPositionForVerticalReachability() {
        final boolean isInFullScreenTabletopMode = isDisplayFullScreenAndSeparatingHinge();
        return mAppCompatConfiguration.getLetterboxPositionForVerticalReachability(
                isInFullScreenTabletopMode);
    }

    @AppCompatConfiguration.LetterboxHorizontalReachabilityPosition
    int getLetterboxPositionForHorizontalReachability() {
        final boolean isInFullScreenBookMode = isFullScreenAndBookModeEnabled();
        return mAppCompatConfiguration.getLetterboxPositionForHorizontalReachability(
                isInFullScreenBookMode);
    }

    @VisibleForTesting
    void handleHorizontalDoubleTap(int x) {
        if (!isHorizontalReachabilityEnabled() || mActivityRecord.isInTransition()) {
            return;
        }

        if (mLetterbox.getInnerFrame().left <= x && mLetterbox.getInnerFrame().right >= x) {
            // Only react to clicks at the sides of the letterboxed app window.
            return;
        }

        boolean isInFullScreenBookMode = isDisplayFullScreenAndSeparatingHinge()
                && mAppCompatConfiguration.getIsAutomaticReachabilityInBookModeEnabled();
        int letterboxPositionForHorizontalReachability = mAppCompatConfiguration
                .getLetterboxPositionForHorizontalReachability(isInFullScreenBookMode);
        if (mLetterbox.getInnerFrame().left > x) {
            // Moving to the next stop on the left side of the app window: right > center > left.
            mAppCompatConfiguration.movePositionForHorizontalReachabilityToNextLeftStop(
                    isInFullScreenBookMode);
            int changeToLog =
                    letterboxPositionForHorizontalReachability
                            == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_LEFT
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__RIGHT_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
            mDoubleTapEvent = true;
        } else if (mLetterbox.getInnerFrame().right < x) {
            // Moving to the next stop on the right side of the app window: left > center > right.
            mAppCompatConfiguration.movePositionForHorizontalReachabilityToNextRightStop(
                    isInFullScreenBookMode);
            int changeToLog =
                    letterboxPositionForHorizontalReachability
                            == LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_RIGHT
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__LEFT_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
            mDoubleTapEvent = true;
        }
        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    @VisibleForTesting
    void handleVerticalDoubleTap(int y) {
        if (!isVerticalReachabilityEnabled() || mActivityRecord.isInTransition()) {
            return;
        }

        if (mLetterbox.getInnerFrame().top <= y && mLetterbox.getInnerFrame().bottom >= y) {
            // Only react to clicks at the top and bottom of the letterboxed app window.
            return;
        }
        boolean isInFullScreenTabletopMode = isDisplayFullScreenAndSeparatingHinge();
        int letterboxPositionForVerticalReachability = mAppCompatConfiguration
                .getLetterboxPositionForVerticalReachability(isInFullScreenTabletopMode);
        if (mLetterbox.getInnerFrame().top > y) {
            // Moving to the next stop on the top side of the app window: bottom > center > top.
            mAppCompatConfiguration.movePositionForVerticalReachabilityToNextTopStop(
                    isInFullScreenTabletopMode);
            int changeToLog =
                    letterboxPositionForVerticalReachability
                            == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_TOP
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__BOTTOM_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
            mDoubleTapEvent = true;
        } else if (mLetterbox.getInnerFrame().bottom < y) {
            // Moving to the next stop on the bottom side of the app window: top > center > bottom.
            mAppCompatConfiguration.movePositionForVerticalReachabilityToNextBottomStop(
                    isInFullScreenTabletopMode);
            int changeToLog =
                    letterboxPositionForVerticalReachability
                            == LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER
                                ? LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__CENTER_TO_BOTTOM
                                : LETTERBOX_POSITION_CHANGED__POSITION_CHANGE__TOP_TO_CENTER;
            logLetterboxPositionChange(changeToLog);
            mDoubleTapEvent = true;
        }
        // TODO(197549949): Add animation for transition.
        mActivityRecord.recomputeConfiguration();
    }

    /**
     * Whether horizontal reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Windowing mode is fullscreen.
     *   <li>Horizontal Reachability is enabled.
     *   <li>First top opaque activity fills parent vertically, but not horizontally.
     * </ul>
     */
    private boolean isHorizontalReachabilityEnabled(Configuration parentConfiguration) {
        if (!allowHorizontalReachabilityForThinLetterbox()) {
            return false;
        }
        final Rect parentAppBoundsOverride = mActivityRecord.getParentAppBoundsOverride();
        final Rect parentAppBounds = parentAppBoundsOverride != null
                ? parentAppBoundsOverride : parentConfiguration.windowConfiguration.getAppBounds();
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty
        final Rect opaqueActivityBounds = mActivityRecord.mAppCompatController
                .getTransparentPolicy().getFirstOpaqueActivity()
                .map(ActivityRecord::getScreenResolvedBounds)
                .orElse(mActivityRecord.getScreenResolvedBounds());
        return mAppCompatConfiguration.getIsHorizontalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                // Check whether the activity fills the parent vertically.
                && parentAppBounds.height() <= opaqueActivityBounds.height()
                && parentAppBounds.width() > opaqueActivityBounds.width();
    }

    @VisibleForTesting
    boolean isHorizontalReachabilityEnabled() {
        return isHorizontalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    boolean isLetterboxDoubleTapEducationEnabled() {
        return isHorizontalReachabilityEnabled() || isVerticalReachabilityEnabled();
    }

    // TODO(b/346264992): Remove after AppCompatController refactoring
    private AppCompatOverrides getAppCompatOverrides() {
        return mActivityRecord.mAppCompatController.getAppCompatOverrides();
    }

    /**
     * Whether vertical reachability is enabled for an activity in the current configuration.
     *
     * <p>Conditions that needs to be met:
     * <ul>
     *   <li>Windowing mode is fullscreen.
     *   <li>Vertical Reachability is enabled.
     *   <li>First top opaque activity fills parent horizontally but not vertically.
     * </ul>
     */
    private boolean isVerticalReachabilityEnabled(Configuration parentConfiguration) {
        if (!allowVerticalReachabilityForThinLetterbox()) {
            return false;
        }
        final Rect parentAppBoundsOverride = mActivityRecord.getParentAppBoundsOverride();
        final Rect parentAppBounds = parentAppBoundsOverride != null
                ? parentAppBoundsOverride : parentConfiguration.windowConfiguration.getAppBounds();
        // Use screen resolved bounds which uses resolved bounds or size compat bounds
        // as activity bounds can sometimes be empty.
        final Rect opaqueActivityBounds = mActivityRecord.mAppCompatController
                .getTransparentPolicy().getFirstOpaqueActivity()
                .map(ActivityRecord::getScreenResolvedBounds)
                .orElse(mActivityRecord.getScreenResolvedBounds());
        return mAppCompatConfiguration.getIsVerticalReachabilityEnabled()
                && parentConfiguration.windowConfiguration.getWindowingMode()
                        == WINDOWING_MODE_FULLSCREEN
                // Check whether the activity fills the parent horizontally.
                && parentAppBounds.width() <= opaqueActivityBounds.width()
                && parentAppBounds.height() > opaqueActivityBounds.height();
    }

    @VisibleForTesting
    boolean isVerticalReachabilityEnabled() {
        return isVerticalReachabilityEnabled(mActivityRecord.getParent().getConfiguration());
    }

    @VisibleForTesting
    boolean shouldShowLetterboxUi(WindowState mainWindow) {
        if (getAppCompatOverrides().getAppCompatOrientationOverrides()
                .getIsRelaunchingAfterRequestedOrientationChanged()) {
            return mLastShouldShowLetterboxUi;
        }

        final boolean shouldShowLetterboxUi =
                (mActivityRecord.isInLetterboxAnimation() || mActivityRecord.isVisible()
                        || mActivityRecord.isVisibleRequested())
                && mainWindow.areAppWindowBoundsLetterboxed()
                // Check for FLAG_SHOW_WALLPAPER explicitly instead of using
                // WindowContainer#showWallpaper because the later will return true when this
                // activity is using blurred wallpaper for letterbox background.
                && (mainWindow.getAttrs().flags & FLAG_SHOW_WALLPAPER) == 0;

        mLastShouldShowLetterboxUi = shouldShowLetterboxUi;

        return shouldShowLetterboxUi;
    }

    Color getLetterboxBackgroundColor() {
        final WindowState w = mActivityRecord.findMainWindow();
        if (w == null || w.isLetterboxedForDisplayCutout()) {
            return Color.valueOf(Color.BLACK);
        }
        @LetterboxBackgroundType int letterboxBackgroundType =
                mAppCompatConfiguration.getLetterboxBackgroundType();
        TaskDescription taskDescription = mActivityRecord.taskDescription;
        switch (letterboxBackgroundType) {
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND_FLOATING:
                if (taskDescription != null && taskDescription.getBackgroundColorFloating() != 0) {
                    return Color.valueOf(taskDescription.getBackgroundColorFloating());
                }
                break;
            case LETTERBOX_BACKGROUND_APP_COLOR_BACKGROUND:
                if (taskDescription != null && taskDescription.getBackgroundColor() != 0) {
                    return Color.valueOf(taskDescription.getBackgroundColor());
                }
                break;
            case LETTERBOX_BACKGROUND_WALLPAPER:
                if (hasWallpaperBackgroundForLetterbox()) {
                    // Color is used for translucent scrim that dims wallpaper.
                    return mAppCompatConfiguration.getLetterboxBackgroundColor();
                }
                Slog.w(TAG, "Wallpaper option is selected for letterbox background but "
                        + "blur is not supported by a device or not supported in the current "
                        + "window configuration or both alpha scrim and blur radius aren't "
                        + "provided so using solid color background");
                break;
            case LETTERBOX_BACKGROUND_SOLID_COLOR:
                return mAppCompatConfiguration.getLetterboxBackgroundColor();
            default:
                throw new AssertionError(
                    "Unexpected letterbox background type: " + letterboxBackgroundType);
        }
        // If picked option configured incorrectly or not supported then default to a solid color
        // background.
        return mAppCompatConfiguration.getLetterboxBackgroundColor();
    }

    private void updateRoundedCornersIfNeeded(final WindowState mainWindow) {
        final SurfaceControl windowSurface = mainWindow.getSurfaceControl();
        if (windowSurface == null || !windowSurface.isValid()) {
            return;
        }

        // cropBounds must be non-null for the cornerRadius to be ever applied.
        mActivityRecord.getSyncTransaction()
                .setCrop(windowSurface, getCropBoundsIfNeeded(mainWindow))
                .setCornerRadius(windowSurface, getRoundedCornersRadius(mainWindow));
    }

    @VisibleForTesting
    @Nullable
    Rect getCropBoundsIfNeeded(final WindowState mainWindow) {
        if (!requiresRoundedCorners(mainWindow) || mActivityRecord.isInLetterboxAnimation()) {
            // We don't want corner radius on the window.
            // In the case the ActivityRecord requires a letterboxed animation we never want
            // rounded corners on the window because rounded corners are applied at the
            // animation-bounds surface level and rounded corners on the window would interfere
            // with that leading to unexpected rounded corner positioning during the animation.
            return null;
        }

        final Rect cropBounds = new Rect(mActivityRecord.getBounds());

        // In case of translucent activities we check if the requested size is different from
        // the size provided using inherited bounds. In that case we decide to not apply rounded
        // corners because we assume the specific layout would. This is the case when the layout
        // of the translucent activity uses only a part of all the bounds because of the use of
        // LayoutParams.WRAP_CONTENT.
        if (mActivityRecord.mAppCompatController.getTransparentPolicy().isRunning()
                && (cropBounds.width() != mainWindow.mRequestedWidth
                || cropBounds.height() != mainWindow.mRequestedHeight)) {
            return null;
        }

        // It is important to call {@link #adjustBoundsIfNeeded} before {@link cropBounds.offsetTo}
        // because taskbar bounds used in {@link #adjustBoundsIfNeeded}
        // are in screen coordinates
        adjustBoundsForTaskbar(mainWindow, cropBounds);

        final float scale = mainWindow.mInvGlobalScale;
        if (scale != 1f && scale > 0f) {
            cropBounds.scale(scale);
        }

        // ActivityRecord bounds are in screen coordinates while (0,0) for activity's surface
        // control is in the top left corner of an app window so offsetting bounds
        // accordingly.
        cropBounds.offsetTo(0, 0);
        return cropBounds;
    }

    private boolean requiresRoundedCorners(final WindowState mainWindow) {
        return isLetterboxedNotForDisplayCutout(mainWindow)
                && mAppCompatConfiguration.isLetterboxActivityCornersRounded();
    }

    // Returns rounded corners radius the letterboxed activity should have based on override in
    // R.integer.config_letterboxActivityCornersRadius or min device bottom corner radii.
    // Device corners can be different on the right and left sides, but we use the same radius
    // for all corners for consistency and pick a minimal bottom one for consistency with a
    // taskbar rounded corners.
    int getRoundedCornersRadius(final WindowState mainWindow) {
        if (!requiresRoundedCorners(mainWindow)) {
            return 0;
        }

        final int radius;
        if (mAppCompatConfiguration.getLetterboxActivityCornersRadius() >= 0) {
            radius = mAppCompatConfiguration.getLetterboxActivityCornersRadius();
        } else {
            final InsetsState insetsState = mainWindow.getInsetsState();
            radius = Math.min(
                    getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_LEFT),
                    getInsetsStateCornerRadius(insetsState, RoundedCorner.POSITION_BOTTOM_RIGHT));
        }

        final float scale = mainWindow.mInvGlobalScale;
        return (scale != 1f && scale > 0f) ? (int) (scale * radius) : radius;
    }

    /**
     * Returns the taskbar in case it is visible and expanded in height, otherwise returns null.
     */
    @VisibleForTesting
    @Nullable
    InsetsSource getExpandedTaskbarOrNull(final WindowState mainWindow) {
        final InsetsState state = mainWindow.getInsetsState();
        for (int i = state.sourceSize() - 1; i >= 0; i--) {
            final InsetsSource source = state.sourceAt(i);
            if (source.getType() == WindowInsets.Type.navigationBars()
                    && source.hasFlags(InsetsSource.FLAG_INSETS_ROUNDED_CORNER)
                    && source.isVisible()) {
                return source;
            }
        }
        return null;
    }

    private void adjustBoundsForTaskbar(final WindowState mainWindow, final Rect bounds) {
        // Rounded corners should be displayed above the taskbar. When taskbar is hidden,
        // an insets frame is equal to a navigation bar which shouldn't affect position of
        // rounded corners since apps are expected to handle navigation bar inset.
        // This condition checks whether the taskbar is visible.
        // Do not crop the taskbar inset if the window is in immersive mode - the user can
        // swipe to show/hide the taskbar as an overlay.
        // Adjust the bounds only in case there is an expanded taskbar,
        // otherwise the rounded corners will be shown behind the navbar.
        final InsetsSource expandedTaskbarOrNull = getExpandedTaskbarOrNull(mainWindow);
        if (expandedTaskbarOrNull != null) {
            // Rounded corners should be displayed above the expanded taskbar.
            bounds.bottom = Math.min(bounds.bottom, expandedTaskbarOrNull.getFrame().top);
        }
    }

    private int getInsetsStateCornerRadius(
                InsetsState insetsState, @RoundedCorner.Position int position) {
        RoundedCorner corner = insetsState.getRoundedCorners().getRoundedCorner(position);
        return corner == null ? 0 : corner.getRadius();
    }

    private boolean isLetterboxedNotForDisplayCutout(WindowState mainWindow) {
        return shouldShowLetterboxUi(mainWindow)
                && !mainWindow.isLetterboxedForDisplayCutout();
    }

    private void updateWallpaperForLetterbox(WindowState mainWindow) {
        @LetterboxBackgroundType int letterboxBackgroundType =
                mAppCompatConfiguration.getLetterboxBackgroundType();
        boolean wallpaperShouldBeShown =
                letterboxBackgroundType == LETTERBOX_BACKGROUND_WALLPAPER
                        // Don't use wallpaper as a background if letterboxed for display cutout.
                        && isLetterboxedNotForDisplayCutout(mainWindow)
                        // Check that dark scrim alpha or blur radius are provided
                        && (getLetterboxWallpaperBlurRadiusPx() > 0
                                || getLetterboxWallpaperDarkScrimAlpha() > 0)
                        // Check that blur is supported by a device if blur radius is provided.
                        && (getLetterboxWallpaperBlurRadiusPx() <= 0
                                || isLetterboxWallpaperBlurSupported());
        if (mShowWallpaperForLetterboxBackground != wallpaperShouldBeShown) {
            mShowWallpaperForLetterboxBackground = wallpaperShouldBeShown;
            mActivityRecord.requestUpdateWallpaperIfNeeded();
        }
    }

    private int getLetterboxWallpaperBlurRadiusPx() {
        int blurRadius = mAppCompatConfiguration.getLetterboxBackgroundWallpaperBlurRadiusPx();
        return Math.max(blurRadius, 0);
    }

    private float getLetterboxWallpaperDarkScrimAlpha() {
        float alpha = mAppCompatConfiguration.getLetterboxBackgroundWallpaperDarkScrimAlpha();
        // No scrim by default.
        return (alpha < 0 || alpha >= 1) ? 0.0f : alpha;
    }

    private boolean isLetterboxWallpaperBlurSupported() {
        return mAppCompatConfiguration.mContext.getSystemService(WindowManager.class)
                .isCrossWindowBlurEnabled();
    }

    void dump(PrintWriter pw, String prefix) {
        final WindowState mainWin = mActivityRecord.findMainWindow();
        if (mainWin == null) {
            return;
        }

        boolean areBoundsLetterboxed = mainWin.areAppWindowBoundsLetterboxed();
        pw.println(prefix + "areBoundsLetterboxed=" + areBoundsLetterboxed);
        if (!areBoundsLetterboxed) {
            return;
        }

        pw.println(prefix + "  letterboxReason=" + getLetterboxReasonString(mainWin));
        pw.println(prefix + "  activityAspectRatio="
                + AppCompatUtils.computeAspectRatio(mActivityRecord.getBounds()));

        boolean shouldShowLetterboxUi = shouldShowLetterboxUi(mainWin);
        pw.println(prefix + "shouldShowLetterboxUi=" + shouldShowLetterboxUi);

        if (!shouldShowLetterboxUi) {
            return;
        }
        pw.println(prefix + "  isVerticalThinLetterboxed=" + isVerticalThinLetterboxed());
        pw.println(prefix + "  isHorizontalThinLetterboxed=" + isHorizontalThinLetterboxed());
        pw.println(prefix + "  letterboxBackgroundColor=" + Integer.toHexString(
                getLetterboxBackgroundColor().toArgb()));
        pw.println(prefix + "  letterboxBackgroundType="
                + letterboxBackgroundTypeToString(
                        mAppCompatConfiguration.getLetterboxBackgroundType()));
        pw.println(prefix + "  letterboxCornerRadius="
                + getRoundedCornersRadius(mainWin));
        if (mAppCompatConfiguration.getLetterboxBackgroundType()
                == LETTERBOX_BACKGROUND_WALLPAPER) {
            pw.println(prefix + "  isLetterboxWallpaperBlurSupported="
                    + isLetterboxWallpaperBlurSupported());
            pw.println(prefix + "  letterboxBackgroundWallpaperDarkScrimAlpha="
                    + getLetterboxWallpaperDarkScrimAlpha());
            pw.println(prefix + "  letterboxBackgroundWallpaperBlurRadius="
                    + getLetterboxWallpaperBlurRadiusPx());
        }

        pw.println(prefix + "  isHorizontalReachabilityEnabled="
                + isHorizontalReachabilityEnabled());
        pw.println(prefix + "  isVerticalReachabilityEnabled=" + isVerticalReachabilityEnabled());
        pw.println(prefix + "  letterboxHorizontalPositionMultiplier="
                + getHorizontalPositionMultiplier(mActivityRecord.getParent().getConfiguration()));
        pw.println(prefix + "  letterboxVerticalPositionMultiplier="
                + getVerticalPositionMultiplier(mActivityRecord.getParent().getConfiguration()));
        pw.println(prefix + "  letterboxPositionForHorizontalReachability="
                + AppCompatConfiguration.letterboxHorizontalReachabilityPositionToString(
                mAppCompatConfiguration.getLetterboxPositionForHorizontalReachability(false)));
        pw.println(prefix + "  letterboxPositionForVerticalReachability="
                + AppCompatConfiguration.letterboxVerticalReachabilityPositionToString(
                mAppCompatConfiguration.getLetterboxPositionForVerticalReachability(false)));
        pw.println(prefix + "  fixedOrientationLetterboxAspectRatio="
                + mAppCompatConfiguration.getFixedOrientationLetterboxAspectRatio());
        pw.println(prefix + "  defaultMinAspectRatioForUnresizableApps="
                + mAppCompatConfiguration.getDefaultMinAspectRatioForUnresizableApps());
        pw.println(prefix + "  isSplitScreenAspectRatioForUnresizableAppsEnabled="
                + mAppCompatConfiguration.getIsSplitScreenAspectRatioForUnresizableAppsEnabled());
        pw.println(prefix + "  isDisplayAspectRatioEnabledForFixedOrientationLetterbox="
                + mAppCompatConfiguration
                .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox());
    }

    /**
     * Returns a string representing the reason for letterboxing. This method assumes the activity
     * is letterboxed.
     */
    private String getLetterboxReasonString(WindowState mainWin) {
        if (mActivityRecord.inSizeCompatMode()) {
            return "SIZE_COMPAT_MODE";
        }
        if (mActivityRecord.mAppCompatController.getAppCompatAspectRatioPolicy()
                .isLetterboxedForFixedOrientationAndAspectRatio()) {
            return "FIXED_ORIENTATION";
        }
        if (mainWin.isLetterboxedForDisplayCutout()) {
            return "DISPLAY_CUTOUT";
        }
        if (mActivityRecord.mAppCompatController.getAppCompatAspectRatioPolicy()
                .isLetterboxedForAspectRatioOnly()) {
            return "ASPECT_RATIO";
        }
        return "UNKNOWN_REASON";
    }

    private int letterboxHorizontalReachabilityPositionToLetterboxPosition(
            @AppCompatConfiguration.LetterboxHorizontalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_LEFT:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__LEFT;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_CENTER:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
            case LETTERBOX_HORIZONTAL_REACHABILITY_POSITION_RIGHT:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__RIGHT;
            default:
                throw new AssertionError(
                        "Unexpected letterbox horizontal reachability position type: "
                                + position);
        }
    }

    private int letterboxVerticalReachabilityPositionToLetterboxPosition(
            @AppCompatConfiguration.LetterboxVerticalReachabilityPosition int position) {
        switch (position) {
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_TOP:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__TOP;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_CENTER:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__CENTER;
            case LETTERBOX_VERTICAL_REACHABILITY_POSITION_BOTTOM:
                return APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__BOTTOM;
            default:
                throw new AssertionError(
                        "Unexpected letterbox vertical reachability position type: "
                                + position);
        }
    }

    int getLetterboxPositionForLogging() {
        int positionToLog = APP_COMPAT_STATE_CHANGED__LETTERBOX_POSITION__UNKNOWN_POSITION;
        if (isHorizontalReachabilityEnabled()) {
            int letterboxPositionForHorizontalReachability = mAppCompatConfiguration
                    .getLetterboxPositionForHorizontalReachability(
                            isDisplayFullScreenAndInPosture(/* isTabletop */ false));
            positionToLog = letterboxHorizontalReachabilityPositionToLetterboxPosition(
                    letterboxPositionForHorizontalReachability);
        } else if (isVerticalReachabilityEnabled()) {
            int letterboxPositionForVerticalReachability = mAppCompatConfiguration
                    .getLetterboxPositionForVerticalReachability(
                            isDisplayFullScreenAndInPosture(/* isTabletop */ true));
            positionToLog = letterboxVerticalReachabilityPositionToLetterboxPosition(
                    letterboxPositionForVerticalReachability);
        }
        return positionToLog;
    }

    /**
     * Logs letterbox position changes via {@link ActivityMetricsLogger#logLetterboxPositionChange}.
     */
    private void logLetterboxPositionChange(int letterboxPositionChange) {
        mActivityRecord.mTaskSupervisor.getActivityMetricsLogger()
                .logLetterboxPositionChange(mActivityRecord, letterboxPositionChange);
    }

    @Nullable
    LetterboxDetails getLetterboxDetails() {
        final WindowState w = mActivityRecord.findMainWindow();
        if (mLetterbox == null || w == null || w.isLetterboxedForDisplayCutout()) {
            return null;
        }
        Rect letterboxInnerBounds = new Rect();
        Rect letterboxOuterBounds = new Rect();
        getLetterboxInnerBounds(letterboxInnerBounds);
        getLetterboxOuterBounds(letterboxOuterBounds);

        if (letterboxInnerBounds.isEmpty() || letterboxOuterBounds.isEmpty()) {
            return null;
        }

        return new LetterboxDetails(
                letterboxInnerBounds,
                letterboxOuterBounds,
                w.mAttrs.insetsFlags.appearance
        );
    }
}
