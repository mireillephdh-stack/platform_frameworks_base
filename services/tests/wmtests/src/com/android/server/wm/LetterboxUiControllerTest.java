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

package com.android.server.wm;

import static android.content.pm.ActivityInfo.FORCE_NON_RESIZE_APP;
import static android.content.pm.ActivityInfo.FORCE_RESIZE_APP;
import static android.content.pm.ActivityInfo.OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO;
import static android.content.pm.ActivityInfo.OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA;
import static android.content.pm.ActivityInfo.OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_3_2;
import static android.content.pm.PackageManager.USER_MIN_ASPECT_RATIO_FULLSCREEN;
import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.view.InsetsSource.FLAG_INSETS_ROUNDED_CORNER;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE;
import static android.view.WindowManager.PROPERTY_COMPAT_ENABLE_FAKE_FOCUS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.compat.testing.PlatformCompatChangeRule;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.Property;
import android.content.res.Resources;
import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.RoundedCorner;
import android.view.RoundedCorners;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.window.flags.Flags;

import libcore.junit.util.compat.CoreCompatChangeRule.DisableCompatChanges;
import libcore.junit.util.compat.CoreCompatChangeRule.EnableCompatChanges;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;

/**
 * Test class for {@link LetterboxUiController}.
 *
 * Build/Install/Run:
 * atest WmTests:LetterboxUiControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class LetterboxUiControllerTest extends WindowTestsBase {
    private static final int TASKBAR_COLLAPSED_HEIGHT = 10;
    private static final int TASKBAR_EXPANDED_HEIGHT = 20;
    private static final int SCREEN_WIDTH = 200;
    private static final int SCREEN_HEIGHT = 100;
    private static final Rect TASKBAR_COLLAPSED_BOUNDS = new Rect(0,
            SCREEN_HEIGHT - TASKBAR_COLLAPSED_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);
    private static final Rect TASKBAR_EXPANDED_BOUNDS = new Rect(0,
            SCREEN_HEIGHT - TASKBAR_EXPANDED_HEIGHT, SCREEN_WIDTH, SCREEN_HEIGHT);

    @Rule
    public TestRule compatChangeRule = new PlatformCompatChangeRule();

    private ActivityRecord mActivity;
    private Task mTask;
    private DisplayContent mDisplayContent;
    private LetterboxUiController mController;
    private LetterboxConfiguration mLetterboxConfiguration;
    private final Rect mLetterboxedPortraitTaskBounds = new Rect();

    @Before
    public void setUp() throws Exception {
        mActivity = setUpActivityWithComponent();

        mLetterboxConfiguration = mWm.mLetterboxConfiguration;
        spyOn(mLetterboxConfiguration);

        mController = new LetterboxUiController(mWm, mActivity);
    }



    @Test
    public void testGetCropBoundsIfNeeded_handleCropForTransparentActivityBasedOnOpaqueBounds() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);
        final Rect opaqueBounds = new Rect(0, 0, 500, 300);
        doReturn(opaqueBounds).when(mActivity).getBounds();
        // Activity is translucent
        spyOn(mActivity.mAppCompatController.getTransparentPolicy());
        when(mActivity.mAppCompatController.getTransparentPolicy()
                .isRunning()).thenReturn(true);

        // Makes requested sizes different
        mainWindow.mRequestedWidth = opaqueBounds.width() - 1;
        mainWindow.mRequestedHeight = opaqueBounds.height() - 1;
        assertNull(mActivity.mLetterboxUiController.getCropBoundsIfNeeded(mainWindow));

        // Makes requested sizes equals
        mainWindow.mRequestedWidth = opaqueBounds.width();
        mainWindow.mRequestedHeight = opaqueBounds.height();
        assertNotNull(mActivity.mLetterboxUiController.getCropBoundsIfNeeded(mainWindow));
    }

    @Test
    public void testGetCropBoundsIfNeeded_noCrop() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);

        // Do not apply crop if taskbar is collapsed
        taskbar.setFrame(TASKBAR_COLLAPSED_BOUNDS);
        assertNull(mController.getExpandedTaskbarOrNull(mainWindow));

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, SCREEN_HEIGHT / 4,
                SCREEN_WIDTH - SCREEN_WIDTH / 4, SCREEN_HEIGHT - SCREEN_HEIGHT / 4);

        final Rect noCrop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, noCrop);
        assertEquals(0, noCrop.left);
        assertEquals(0, noCrop.top);
        assertEquals(mLetterboxedPortraitTaskBounds.width(), noCrop.right);
        assertEquals(mLetterboxedPortraitTaskBounds.height(), noCrop.bottom);
    }

    @Test
    public void testGetCropBoundsIfNeeded_appliesCrop() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);

        // Apply crop if taskbar is expanded
        taskbar.setFrame(TASKBAR_EXPANDED_BOUNDS);
        assertNotNull(mController.getExpandedTaskbarOrNull(mainWindow));

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, 0, SCREEN_WIDTH - SCREEN_WIDTH / 4,
                SCREEN_HEIGHT);

        final Rect crop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, crop);
        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals(mLetterboxedPortraitTaskBounds.width(), crop.right);
        assertEquals(mLetterboxedPortraitTaskBounds.height() - TASKBAR_EXPANDED_HEIGHT,
                crop.bottom);
    }

    @Test
    public void testGetCropBoundsIfNeeded_appliesCropWithSizeCompatScaling() {
        final InsetsSource taskbar = new InsetsSource(/*id=*/ 0,
                WindowInsets.Type.navigationBars());
        taskbar.setFlags(FLAG_INSETS_ROUNDED_CORNER, FLAG_INSETS_ROUNDED_CORNER);
        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(taskbar);
        final float scaling = 2.0f;

        // Apply crop if taskbar is expanded
        taskbar.setFrame(TASKBAR_EXPANDED_BOUNDS);
        assertNotNull(mController.getExpandedTaskbarOrNull(mainWindow));
        // With SizeCompat scaling
        doReturn(true).when(mActivity).inSizeCompatMode();
        mainWindow.mInvGlobalScale = scaling;

        mLetterboxedPortraitTaskBounds.set(SCREEN_WIDTH / 4, 0, SCREEN_WIDTH - SCREEN_WIDTH / 4,
                SCREEN_HEIGHT);

        final int appWidth = mLetterboxedPortraitTaskBounds.width();
        final int appHeight = mLetterboxedPortraitTaskBounds.height();

        final Rect crop = mController.getCropBoundsIfNeeded(mainWindow);
        assertNotEquals(null, crop);
        assertEquals(0, crop.left);
        assertEquals(0, crop.top);
        assertEquals((int) (appWidth * scaling), crop.right);
        assertEquals((int) ((appHeight - TASKBAR_EXPANDED_HEIGHT) * scaling), crop.bottom);
    }

    @Test
    public void testGetRoundedCornersRadius_withRoundedCornersFromInsets() {
        final float invGlobalScale = 0.5f;
        final int expectedRadius = 7;
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mainWindow.mInvGlobalScale = invGlobalScale;
        final InsetsState insets = mainWindow.getInsetsState();

        RoundedCorners roundedCorners = new RoundedCorners(
                /*topLeft=*/ null,
                /*topRight=*/ null,
                /*bottomRight=*/ new RoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT,
                    configurationRadius, /*centerX=*/ 1, /*centerY=*/ 1),
                /*bottomLeft=*/ new RoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT,
                    configurationRadius * 2 /*2 is to test selection of the min radius*/,
                    /*centerX=*/ 1, /*centerY=*/ 1)
        );
        insets.setRoundedCorners(roundedCorners);
        mLetterboxConfiguration.setLetterboxActivityCornersRadius(-1);

        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    @Test
    public void testGetRoundedCornersRadius_withLetterboxActivityCornersRadius() {
        final float invGlobalScale = 0.5f;
        final int expectedRadius = 7;
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mainWindow.mInvGlobalScale = invGlobalScale;
        mLetterboxConfiguration.setLetterboxActivityCornersRadius(configurationRadius);

        doReturn(true).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));

        doReturn(false).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));

        doReturn(false).when(mActivity).isVisibleRequested();
        doReturn(false).when(mActivity).isVisible();
        assertEquals(0, mController.getRoundedCornersRadius(mainWindow));

        doReturn(true).when(mActivity).isInLetterboxAnimation();
        assertEquals(expectedRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    @Test
    public void testGetRoundedCornersRadius_noScalingApplied() {
        final int configurationRadius = 15;

        final WindowState mainWindow = mockForGetCropBoundsAndRoundedCorners(/*taskbar=*/ null);
        mLetterboxConfiguration.setLetterboxActivityCornersRadius(configurationRadius);

        mainWindow.mInvGlobalScale = -1f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));

        mainWindow.mInvGlobalScale = 0f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));

        mainWindow.mInvGlobalScale = 1f;
        assertEquals(configurationRadius, mController.getRoundedCornersRadius(mainWindow));
    }

    private WindowState mockForGetCropBoundsAndRoundedCorners(@Nullable InsetsSource taskbar) {
        final WindowState mainWindow = mock(WindowState.class);
        final InsetsState insets = new InsetsState();
        final Resources resources = mWm.mContext.getResources();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();

        mainWindow.mInvGlobalScale = 1f;
        spyOn(resources);
        spyOn(mActivity);
        spyOn(mActivity.mAppCompatController.getAppCompatAspectRatioPolicy());

        if (taskbar != null) {
            taskbar.setVisible(true);
            insets.addSource(taskbar);
        }
        doReturn(mLetterboxedPortraitTaskBounds).when(mActivity).getBounds();
        doReturn(false).when(mActivity).isInLetterboxAnimation();
        doReturn(true).when(mActivity).isVisible();
        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatAspectRatioPolicy()).isLetterboxedForFixedOrientationAndAspectRatio();
        doReturn(insets).when(mainWindow).getInsetsState();
        doReturn(attrs).when(mainWindow).getAttrs();
        doReturn(true).when(mainWindow).isDrawn();
        doReturn(true).when(mainWindow).isOnScreen();
        doReturn(false).when(mainWindow).isLetterboxedForDisplayCutout();
        doReturn(true).when(mainWindow).areAppWindowBoundsLetterboxed();
        doReturn(true).when(mLetterboxConfiguration).isLetterboxActivityCornersRounded();
        doReturn(TASKBAR_EXPANDED_HEIGHT).when(resources).getDimensionPixelSize(
                R.dimen.taskbar_frame_height);

        // Need to reinitialise due to the change in resources getDimensionPixelSize output.
        mController = new LetterboxUiController(mWm, mActivity);

        return mainWindow;
    }

    // shouldApplyUser...Override
    @Test
    public void testShouldApplyUserFullscreenOverride_trueProperty_returnsFalse() throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE,
                /* value */ true);

        doReturn(false).when(mLetterboxConfiguration).isUserAppAspectRatioFullscreenEnabled();
        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserFullscreenOverride());
    }

    @Test
    public void testShouldApplyUserFullscreenOverride_falseFullscreenProperty_returnsFalse()
            throws Exception {
        prepareActivityThatShouldApplyUserFullscreenOverride();
        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_FULLSCREEN_OVERRIDE,
                /* value */ false);

        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserFullscreenOverride());
    }

    @Test
    public void testShouldApplyUserFullscreenOverride_falseSettingsProperty_returnsFalse()
            throws Exception {
        prepareActivityThatShouldApplyUserFullscreenOverride();
        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, /* value */ false);

        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserFullscreenOverride());
    }

    @Test
    public void testShouldApplyUserFullscreenOverride_returnsTrue() {
        prepareActivityThatShouldApplyUserFullscreenOverride();

        assertTrue(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserFullscreenOverride());
    }

    @Test
    public void testShouldEnableUserAspectRatioSettings_falseProperty_returnsFalse()
            throws Exception {
        prepareActivityThatShouldApplyUserMinAspectRatioOverride();
        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, /* value */ false);

        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldEnableUserAspectRatioSettings());
    }

    @Test
    public void testShouldEnableUserAspectRatioSettings_trueProperty_returnsTrue()
            throws Exception {

        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mActivity = setUpActivityWithComponent();
        prepareActivityThatShouldApplyUserMinAspectRatioOverride();

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldEnableUserAspectRatioSettings());
    }

    @Test
    public void testShouldEnableUserAspectRatioSettings_noIgnoreOrientation_returnsFalse()
            throws Exception {
        prepareActivityForShouldApplyUserMinAspectRatioOverride(/* orientationRequest */ false);
        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldEnableUserAspectRatioSettings());
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_falseProperty_returnsFalse()
            throws Exception {
        prepareActivityThatShouldApplyUserMinAspectRatioOverride();
        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, /* value */ false);

        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldEnableUserAspectRatioSettings());
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_trueProperty_returnsFalse()
            throws Exception {
        doReturn(false).when(mLetterboxConfiguration).isUserAppAspectRatioSettingsEnabled();
        mockThatProperty(PROPERTY_COMPAT_ALLOW_USER_ASPECT_RATIO_OVERRIDE, /* value */ true);

        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserMinAspectRatioOverride());
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_disabledIgnoreOrientationRequest() {
        prepareActivityThatShouldApplyUserMinAspectRatioOverride();
        mDisplayContent.setIgnoreOrientationRequest(false);

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserMinAspectRatioOverride());
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_returnsTrue() {
        prepareActivityThatShouldApplyUserMinAspectRatioOverride();

        assertTrue(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserMinAspectRatioOverride());
    }

    @Test
    public void testShouldApplyUserMinAspectRatioOverride_noIgnoreOrientation_returnsFalse() {
        prepareActivityForShouldApplyUserMinAspectRatioOverride(/* orientationRequest */ false);

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldApplyUserMinAspectRatioOverride());
    }

    private void prepareActivityForShouldApplyUserMinAspectRatioOverride(
            boolean orientationRequest) {
        spyOn(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides());
        doReturn(orientationRequest).when(
                mLetterboxConfiguration).isUserAppAspectRatioSettingsEnabled();
        mDisplayContent.setIgnoreOrientationRequest(true);
        doReturn(USER_MIN_ASPECT_RATIO_3_2)
                .when(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides())
                .getUserMinAspectRatioOverrideCode();
    }

    private void prepareActivityThatShouldApplyUserMinAspectRatioOverride() {
        prepareActivityForShouldApplyUserMinAspectRatioOverride(/* orientationRequest */ true);
    }

    private void prepareActivityThatShouldApplyUserFullscreenOverride() {
        spyOn(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides());
        doReturn(true).when(mLetterboxConfiguration).isUserAppAspectRatioFullscreenEnabled();
        mDisplayContent.setIgnoreOrientationRequest(true);
        doReturn(USER_MIN_ASPECT_RATIO_FULLSCREEN)
                .when(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides())
                .getUserMinAspectRatioOverrideCode();
    }

    // shouldUseDisplayLandscapeNaturalOrientation

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_override_returnsTrue() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        assertTrue(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_overrideAndFalseProperty_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_DISPLAY_ORIENTATION_OVERRIDE, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_portraitNaturalOrientation_returnsFalse() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        doReturn(ORIENTATION_PORTRAIT).when(mDisplayContent).getNaturalOrientation();

        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_disabledIgnoreOrientationRequest_returnsFalse() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();
        mDisplayContent.setIgnoreOrientationRequest(false);

        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_USE_DISPLAY_LANDSCAPE_NATURAL_ORIENTATION})
    public void testShouldUseDisplayLandscapeNaturalOrientation_inMultiWindowMode_returnsFalse() {
        prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation();

        spyOn(mTask);
        doReturn(true).when(mTask).inMultiWindowMode();

        assertFalse(mController.shouldUseDisplayLandscapeNaturalOrientation());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideEnabled_returnsTrue() {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldSendFakeFocus());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testShouldSendFakeFocus_overrideDisabled_returnsFalse() {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldSendFakeFocus());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testIsCompatFakeFocusEnabled_propertyDisabledAndOverrideEnabled_fakeFocusDisabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldSendFakeFocus());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_ENABLE_COMPAT_FAKE_FOCUS})
    public void testIsCompatFakeFocusEnabled_propertyEnabled_noOverride_fakeFocusEnabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldSendFakeFocus());
    }

    @Test
    public void testIsCompatFakeFocusEnabled_propertyDisabled_fakeFocusDisabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ false);

        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldSendFakeFocus());
    }

    @Test
    public void testIsCompatFakeFocusEnabled_propertyEnabled_fakeFocusEnabled()
            throws Exception {
        doReturn(true).when(mLetterboxConfiguration).isCompatFakeFocusEnabled();
        mockThatProperty(PROPERTY_COMPAT_ENABLE_FAKE_FOCUS, /* value */ true);

        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldSendFakeFocus());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_overrideEnabled_returnsTrue() {
        mActivity = setUpActivityWithComponent();

        assertTrue(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldOverrideMinAspectRatio());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyTrue_overrideEnabled_returnsTrue()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mActivity = setUpActivityWithComponent();

        assertTrue(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldOverrideMinAspectRatio());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyTrue_overrideDisabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldOverrideMinAspectRatio());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_overrideDisabled_returnsFalse() {
        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldOverrideMinAspectRatio());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyFalse_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ false);

        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldOverrideMinAspectRatio());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO})
    public void testshouldOverrideMinAspectRatio_propertyFalse_noOverride_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ false);
        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatAspectRatioOverrides()
                .shouldOverrideMinAspectRatio());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_overrideEnabled_returnsTrue() {
        mActivity = setUpActivityWithComponent();
        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatCameraOverrides()).isCameraActive();

        assertTrue(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyTrue_overrideEnabled_returnsTrue()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mActivity = setUpActivityWithComponent();
        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatCameraOverrides()).isCameraActive();

        assertTrue(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyTrue_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mActivity = setUpActivityWithComponent();
        doReturn(false).when(mActivity.mAppCompatController
                .getAppCompatCameraOverrides()).isCameraActive();

        assertFalse(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyTrue_overrideDisabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ true);
        mActivity = setUpActivityWithComponent();
        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatCameraOverrides()).isCameraActive();

        assertFalse(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_overrideDisabled_returnsFalse() {
        mActivity = setUpActivityWithComponent();
        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatCameraOverrides()).isCameraActive();

        assertFalse(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera());
    }

    @Test
    @EnableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyFalse_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ false);
        mActivity = setUpActivityWithComponent();

        assertFalse(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera());
    }

    @Test
    @DisableCompatChanges({OVERRIDE_MIN_ASPECT_RATIO_ONLY_FOR_CAMERA})
    public void shouldOverrideMinAspectRatioForCamera_propertyFalse_noOverride_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_MIN_ASPECT_RATIO_OVERRIDE, /* value */ false);

        mActivity = setUpActivityWithComponent();

        doReturn(true).when(mActivity.mAppCompatController
                .getAppCompatCameraOverrides()).isCameraActive();

        assertFalse(mActivity.mAppCompatController.getAppCompatCameraOverrides()
                .shouldOverrideMinAspectRatioForCamera());
    }

    @Test
    @EnableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_overrideEnabled_returnsTrue() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyTrue_overrideEnabled_returnsTrue()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyTrue_overrideDisabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_overrideDisabled_returnsFalse() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyFalse_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);

        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_RESIZE_APP})
    public void testshouldOverrideForceResizeApp_propertyFalse_noOverride_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_overrideEnabled_returnsTrue() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyTrue_overrideEnabled_returnsTrue()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertTrue(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyTrue_overrideDisabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ true);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_overrideDisabled_returnsFalse() {
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @EnableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyFalse_overrideEnabled_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);

        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    @DisableCompatChanges({FORCE_NON_RESIZE_APP})
    public void testshouldOverrideForceNonResizeApp_propertyFalse_noOverride_returnsFalse()
            throws Exception {
        mockThatProperty(PROPERTY_COMPAT_ALLOW_RESIZEABLE_ACTIVITY_OVERRIDES, /* value */ false);
        mController = new LetterboxUiController(mWm, mActivity);

        assertFalse(mController.shouldOverrideForceNonResizeApp());
    }

    @Test
    public void testgetFixedOrientationLetterboxAspectRatio_splitScreenAspectEnabled() {
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabled();
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isCameraCompatTreatmentEnabledAtBuildTime();
        doReturn(true).when(mActivity.mWmService.mLetterboxConfiguration)
                .isCameraCompatSplitScreenAspectRatioEnabled();
        doReturn(false).when(mActivity.mWmService.mLetterboxConfiguration)
                .getIsDisplayAspectRatioEnabledForFixedOrientationLetterbox();
        doReturn(1.5f).when(mActivity.mWmService.mLetterboxConfiguration)
                .getFixedOrientationLetterboxAspectRatio();

        // Recreate DisplayContent with DisplayRotationCompatPolicy
        mActivity = setUpActivityWithComponent();
        mController = new LetterboxUiController(mWm, mActivity);

        assertEquals(1.5f, mController.getFixedOrientationLetterboxAspectRatio(
                mActivity.getParent().getConfiguration()), /* delta */ 0.01);

        spyOn(mDisplayContent.mAppCompatCameraPolicy);
        doReturn(true).when(mDisplayContent.mAppCompatCameraPolicy)
                .isTreatmentEnabledForActivity(eq(mActivity));

        final AppCompatAspectRatioOverrides aspectRatioOverrides =
                mActivity.mAppCompatController.getAppCompatAspectRatioOverrides();
        assertEquals(aspectRatioOverrides.getSplitScreenAspectRatio(),
                aspectRatioOverrides.getFixedOrientationLetterboxAspectRatio(
                        mActivity.getParent().getConfiguration()), /* delta */  0.01);
    }

    @Test
    public void testIsVerticalThinLetterboxed() {
        // Vertical thin letterbox disabled
        doReturn(-1).when(mActivity.mWmService.mLetterboxConfiguration)
                .getThinLetterboxHeightPx();
        assertFalse(mController.isVerticalThinLetterboxed());
        // Define a Task 100x100
        final Task task = mock(Task.class);
        doReturn(new Rect(0, 0, 100, 100)).when(task).getBounds();
        doReturn(10).when(mActivity.mWmService.mLetterboxConfiguration)
                .getThinLetterboxHeightPx();

        // Vertical thin letterbox disabled without Task
        doReturn(null).when(mActivity).getTask();
        assertFalse(mController.isVerticalThinLetterboxed());
        // Assign a Task for the Activity
        doReturn(task).when(mActivity).getTask();

        // (task.width() - act.width()) / 2  = 5 < 10
        doReturn(new Rect(5, 5, 95, 95)).when(mActivity).getBounds();
        assertTrue(mController.isVerticalThinLetterboxed());

        // (task.width() - act.width()) / 2  = 10 = 10
        doReturn(new Rect(10, 10, 90, 90)).when(mActivity).getBounds();
        assertTrue(mController.isVerticalThinLetterboxed());

        // (task.width() - act.width()) / 2  = 11 > 10
        doReturn(new Rect(11, 11, 89, 89)).when(mActivity).getBounds();
        assertFalse(mController.isVerticalThinLetterboxed());
    }

    @Test
    public void testIsHorizontalThinLetterboxed() {
        // Horizontal thin letterbox disabled
        doReturn(-1).when(mActivity.mWmService.mLetterboxConfiguration)
                .getThinLetterboxWidthPx();
        assertFalse(mController.isHorizontalThinLetterboxed());
        // Define a Task 100x100
        final Task task = mock(Task.class);
        doReturn(new Rect(0, 0, 100, 100)).when(task).getBounds();
        doReturn(10).when(mActivity.mWmService.mLetterboxConfiguration)
                .getThinLetterboxWidthPx();

        // Vertical thin letterbox disabled without Task
        doReturn(null).when(mActivity).getTask();
        assertFalse(mController.isHorizontalThinLetterboxed());
        // Assign a Task for the Activity
        doReturn(task).when(mActivity).getTask();

        // (task.height() - act.height()) / 2  = 5 < 10
        doReturn(new Rect(5, 5, 95, 95)).when(mActivity).getBounds();
        assertTrue(mController.isHorizontalThinLetterboxed());

        // (task.height() - act.height()) / 2  = 10 = 10
        doReturn(new Rect(10, 10, 90, 90)).when(mActivity).getBounds();
        assertTrue(mController.isHorizontalThinLetterboxed());

        // (task.height() - act.height()) / 2  = 11 > 10
        doReturn(new Rect(11, 11, 89, 89)).when(mActivity).getBounds();
        assertFalse(mController.isHorizontalThinLetterboxed());
    }

    @Test
    @EnableFlags(Flags.FLAG_DISABLE_THIN_LETTERBOXING_POLICY)
    public void testAllowReachabilityForThinLetterboxWithFlagEnabled() {
        spyOn(mController);
        doReturn(true).when(mController).isVerticalThinLetterboxed();
        assertFalse(mController.allowVerticalReachabilityForThinLetterbox());
        doReturn(true).when(mController).isHorizontalThinLetterboxed();
        assertFalse(mController.allowHorizontalReachabilityForThinLetterbox());

        doReturn(false).when(mController).isVerticalThinLetterboxed();
        assertTrue(mController.allowVerticalReachabilityForThinLetterbox());
        doReturn(false).when(mController).isHorizontalThinLetterboxed();
        assertTrue(mController.allowHorizontalReachabilityForThinLetterbox());
    }

    @Test
    @DisableFlags(Flags.FLAG_DISABLE_THIN_LETTERBOXING_POLICY)
    public void testAllowReachabilityForThinLetterboxWithFlagDisabled() {
        spyOn(mController);
        doReturn(true).when(mController).isVerticalThinLetterboxed();
        assertTrue(mController.allowVerticalReachabilityForThinLetterbox());
        doReturn(true).when(mController).isHorizontalThinLetterboxed();
        assertTrue(mController.allowHorizontalReachabilityForThinLetterbox());

        doReturn(false).when(mController).isVerticalThinLetterboxed();
        assertTrue(mController.allowVerticalReachabilityForThinLetterbox());
        doReturn(false).when(mController).isHorizontalThinLetterboxed();
        assertTrue(mController.allowHorizontalReachabilityForThinLetterbox());
    }

    @Test
    public void testIsLetterboxEducationEnabled() {
        mController.isLetterboxEducationEnabled();
        verify(mLetterboxConfiguration).getIsEducationEnabled();
    }

    private void mockThatProperty(String propertyName, boolean value) throws Exception {
        Property property = new Property(propertyName, /* value */ value, /* packageName */ "",
                /* className */ "");
        PackageManager pm = mWm.mContext.getPackageManager();
        spyOn(pm);
        doReturn(property).when(pm).getProperty(eq(propertyName), anyString());
    }

    private void prepareActivityThatShouldUseDisplayLandscapeNaturalOrientation() {
        spyOn(mDisplayContent);
        doReturn(ORIENTATION_LANDSCAPE).when(mDisplayContent).getNaturalOrientation();
        mDisplayContent.setIgnoreOrientationRequest(true);
    }

    private ActivityRecord setUpActivityWithComponent() {
        mDisplayContent = new TestDisplayContent
                .Builder(mAtm, /* dw */ 1000, /* dh */ 2000).build();
        mTask = new TaskBuilder(mSupervisor).setDisplay(mDisplayContent).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setOnTop(true)
                .setTask(mTask)
                // Set the component to be that of the test class in order to enable compat changes
                .setComponent(ComponentName.createRelative(mContext,
                        com.android.server.wm.LetterboxUiControllerTest.class.getName()))
                .build();
        spyOn(activity.mAppCompatController.getAppCompatCameraOverrides());
        return activity;
    }
}
