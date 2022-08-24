/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.developeroptions.notification;

import static com.android.car.developeroptions.notification.SettingPref.TYPE_SYSTEM;

import android.content.Context;
import android.provider.Settings.System;

import com.android.car.developeroptions.R;
import com.android.car.developeroptions.SettingsPreferenceFragment;
import com.android.settingslib.core.lifecycle.Lifecycle;

public class ScreenLockSoundPreferenceController extends SettingPrefController {

    private static final String KEY_SCREEN_LOCKING_SOUNDS = "screen_locking_sounds";

    public ScreenLockSoundPreferenceController(Context context, SettingsPreferenceFragment parent,
            Lifecycle lifecycle) {
        super(context, parent, lifecycle);
        mPreference = new SettingPref(
            TYPE_SYSTEM, KEY_SCREEN_LOCKING_SOUNDS, System.LOCKSCREEN_SOUNDS_ENABLED, DEFAULT_ON);
    }

    @Override
    public boolean isAvailable() {
        return mContext.getResources().getBoolean(R.bool.config_show_screen_locking_sounds);
    }
}