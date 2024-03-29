/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.linhx.quickstep;

import static android.content.pm.ActivityInfo.CONFIG_ORIENTATION;
import static android.content.pm.ActivityInfo.CONFIG_SCREEN_SIZE;

import static com.linhx.launcher3.LauncherAppTransitionManagerImpl.RECENTS_LAUNCH_DURATION;
import static com.linhx.launcher3.LauncherAppTransitionManagerImpl.STATUS_BAR_TRANSITION_DURATION;
import static com.linhx.quickstep.TaskUtils.getRecentsWindowAnimator;
import static com.linhx.quickstep.TaskUtils.taskIsATargetWithMode;
import static com.linhx.systemui.shared.system.RemoteAnimationTargetCompat.MODE_CLOSING;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.linhx.launcher3.AbstractFloatingView;
import com.linhx.launcher3.BaseDraggingActivity;
import com.linhx.launcher3.DeviceProfile;
import com.linhx.launcher3.InvariantDeviceProfile;
import com.linhx.launcher3.ItemInfo;
import com.linhx.launcher3.LauncherAnimationRunner;
import com.linhx.launcher3.LauncherAppState;
import com.linhx.launcher3.R;
import com.linhx.launcher3.anim.Interpolators;
import com.linhx.launcher3.badge.BadgeInfo;
import com.linhx.launcher3.uioverrides.UiFactory;
import com.linhx.launcher3.util.SystemUiController;
import com.linhx.launcher3.util.Themes;
import com.linhx.launcher3.views.BaseDragLayer;
import com.linhx.quickstep.fallback.FallbackRecentsView;
import com.linhx.quickstep.fallback.RecentsRootView;
import com.linhx.quickstep.util.ClipAnimationHelper;
import com.linhx.quickstep.views.RecentsViewContainer;
import com.linhx.quickstep.views.TaskView;
import com.linhx.systemui.shared.system.ActivityOptionsCompat;
import com.linhx.systemui.shared.system.RemoteAnimationAdapterCompat;
import com.linhx.systemui.shared.system.RemoteAnimationRunnerCompat;
import com.linhx.systemui.shared.system.RemoteAnimationTargetCompat;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * A simple activity to show the recently launched tasks
 */
public class RecentsActivity extends BaseDraggingActivity {

    private Handler mUiHandler = new Handler(Looper.getMainLooper());
    private RecentsRootView mRecentsRootView;
    private FallbackRecentsView mFallbackRecentsView;
    private RecentsViewContainer mOverviewPanelContainer;

    private Configuration mOldConfig;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mOldConfig = new Configuration(getResources().getConfiguration());
        initDeviceProfile();

        setContentView(R.layout.fallback_recents_activity);
        mRecentsRootView = findViewById(R.id.drag_layer);
        mFallbackRecentsView = findViewById(R.id.overview_panel);
        mOverviewPanelContainer = findViewById(R.id.overview_panel_container);

        mRecentsRootView.setup();

        getSystemUiController().updateUiState(SystemUiController.UI_STATE_BASE_WINDOW,
                Themes.getAttrBoolean(this, R.attr.isWorkspaceDarkText));
        RecentsActivityTracker.onRecentsActivityCreate(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        int diff = newConfig.diff(mOldConfig);
        if ((diff & (CONFIG_ORIENTATION | CONFIG_SCREEN_SIZE)) != 0) {
            onHandleConfigChanged();
        }
        mOldConfig.setTo(newConfig);
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        onHandleConfigChanged();
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
    }

    public void onRootViewSizeChanged() {
        if (isInMultiWindowModeCompat()) {
            onHandleConfigChanged();
        }
    }

    private void onHandleConfigChanged() {
        mUserEventDispatcher = null;
        initDeviceProfile();

        AbstractFloatingView.closeOpenViews(this, true,
                AbstractFloatingView.TYPE_ALL & ~AbstractFloatingView.TYPE_REBIND_SAFE);
        dispatchDeviceProfileChanged();

        mRecentsRootView.setup();
        reapplyUi();
    }

    @Override
    protected void reapplyUi() {
        mRecentsRootView.dispatchInsets();
    }

    private void initDeviceProfile() {
        // In case we are reusing IDP, create a copy so that we dont conflict with Launcher
        // activity.
        LauncherAppState appState = LauncherAppState.getInstanceNoCreate();
        if (isInMultiWindowModeCompat()) {
            InvariantDeviceProfile idp = appState == null
                    ? new InvariantDeviceProfile(this) : appState.getInvariantDeviceProfile();
            DeviceProfile dp = idp.getDeviceProfile(this);
            mDeviceProfile = mRecentsRootView == null ? dp.copy(this)
                    : dp.getMultiWindowProfile(this, mRecentsRootView.getLastKnownSize());
        } else {
            // If we are reusing the Invariant device profile, make a copy.
            mDeviceProfile = appState == null
                    ? new InvariantDeviceProfile(this).getDeviceProfile(this)
                    : appState.getInvariantDeviceProfile().getDeviceProfile(this).copy(this);
        }
        onDeviceProfileInitiated();
    }

    @Override
    public BaseDragLayer getDragLayer() {
        return mRecentsRootView;
    }

    @Override
    public View getRootView() {
        return mRecentsRootView;
    }

    @Override
    public <T extends View> T getOverviewPanel() {
        return (T) mFallbackRecentsView;
    }

    public RecentsViewContainer getOverviewPanelContainer() {
        return mOverviewPanelContainer;
    }

    @Override
    public BadgeInfo getBadgeInfoForItem(ItemInfo info) {
        return null;
    }

    @Override
    public ActivityOptions getActivityLaunchOptions(final View v) {
        if (!(v instanceof TaskView)) {
            return null;
        }

        final TaskView taskView = (TaskView) v;
        RemoteAnimationRunnerCompat runner = new LauncherAnimationRunner(mUiHandler,
                true /* startAtFrontOfQueue */) {

            @Override
            public void onCreateAnimation(RemoteAnimationTargetCompat[] targetCompats,
                    AnimationResult result) {
                result.setAnimation(composeRecentsLaunchAnimator(taskView, targetCompats));
            }
        };
        return ActivityOptionsCompat.makeRemoteAnimation(new RemoteAnimationAdapterCompat(
                runner, RECENTS_LAUNCH_DURATION,
                RECENTS_LAUNCH_DURATION - STATUS_BAR_TRANSITION_DURATION));
    }

    /**
     * Composes the animations for a launch from the recents list if possible.
     */
    private AnimatorSet composeRecentsLaunchAnimator(TaskView taskView,
            RemoteAnimationTargetCompat[] targets) {
        AnimatorSet target = new AnimatorSet();
        boolean activityClosing = taskIsATargetWithMode(targets, getTaskId(), MODE_CLOSING);
        ClipAnimationHelper helper = new ClipAnimationHelper();
        target.play(getRecentsWindowAnimator(taskView, !activityClosing, targets, helper)
                .setDuration(RECENTS_LAUNCH_DURATION));

        // Found a visible recents task that matches the opening app, lets launch the app from there
        if (activityClosing) {
            Animator adjacentAnimation = mFallbackRecentsView
                    .createAdjacentPageAnimForTaskLaunch(taskView, helper);
            adjacentAnimation.setInterpolator(Interpolators.TOUCH_RESPONSE_INTERPOLATOR);
            adjacentAnimation.setDuration(RECENTS_LAUNCH_DURATION);
            adjacentAnimation.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mFallbackRecentsView.resetTaskVisuals();
                }
            });
            target.play(adjacentAnimation);
        }
        return target;
    }

    @Override
    public void invalidateParent(ItemInfo info) { }

    @Override
    protected void onStart() {
        // Set the alpha to 1 before calling super, as it may get set back to 0 due to
        // onActivityStart callback.
        mFallbackRecentsView.setContentAlpha(1);
        super.onStart();
        UiFactory.onStart(this);
        mFallbackRecentsView.resetTaskVisuals();
    }

    @Override
    protected void onStop() {
        super.onStop();

        // Workaround for b/78520668, explicitly trim memory once UI is hidden
        onTrimMemory(TRIM_MEMORY_UI_HIDDEN);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        UiFactory.onTrimMemory(this, level);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        RecentsActivityTracker.onRecentsActivityNewIntent(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RecentsActivityTracker.onRecentsActivityDestroy(this);
    }

    @Override
    public void onBackPressed() {
        // TODO: Launch the task we came from
        startHome();
    }

    public void startHome() {
        startActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter writer, String[] args) {
        super.dump(prefix, fd, writer, args);
        writer.println(prefix + "Misc:");
        dumpMisc(writer);
    }
}
