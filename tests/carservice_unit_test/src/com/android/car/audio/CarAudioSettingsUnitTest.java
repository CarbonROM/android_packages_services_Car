/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.audio;

import static com.google.common.truth.Truth.assertThat;

import android.car.settings.CarSettings;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.ContentResolver;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class CarAudioSettingsUnitTest extends AbstractExtendedMockitoTestCase {

    private static final int TEST_USER_ID_1 = 11;


    @Mock
    private ContentResolver mMockContentResolver;

    private CarAudioSettings mCarAudioSettings;

    @Before
    public void setUp() {
        mCarAudioSettings = new CarAudioSettings(mMockContentResolver);
    }

    @Test
    public void isRejectNavigationOnCallEnabledInSettings_whenSetToNotToReject_returnsFalse() {
        setRejectNavigationOnCallSettingsValues(0);
        assertThat(
                mCarAudioSettings.isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID_1))
                .isFalse();
    }

    @Test
    public void isRejectNavigationOnCallEnabledInSettings_whenSetToToReject_returnsTrue() {
        setRejectNavigationOnCallSettingsValues(1);
        assertThat(
                mCarAudioSettings.isRejectNavigationOnCallEnabledInSettings(TEST_USER_ID_1))
                .isTrue();
    }

    private void setRejectNavigationOnCallSettingsValues(int settingsValue) {
        putSettingsInt(CarSettings.Secure.KEY_AUDIO_FOCUS_NAVIGATION_REJECTED_DURING_CALL,
                settingsValue);
    }
}
