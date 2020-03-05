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

package com.android.car.watchdog;

import static com.google.common.truth.Truth.assertThat;

import android.automotive.watchdog.ICarWatchdog;
import android.automotive.watchdog.ICarWatchdogClient;
import android.automotive.watchdog.TimeoutLength;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * <p>This class contains unit tests for the {@link CarWatchdogService}.
 */
@RunWith(MockitoJUnitRunner.class)
public class CarWatchdogServiceTest {

    private static final String TAG = CarWatchdogServiceTest.class.getSimpleName();

    @Mock private Context mMockContext;

    private CarWatchdogService mCarWatchdogService;
    private FakeCarWatchdog mFakeCarWatchdog;

    /**
     * Initialize all of the objects with the @Mock annotation.
     */
    @Before
    public void setUpMocks() {
        mFakeCarWatchdog = new FakeCarWatchdog();
        mCarWatchdogService = new CarWatchdogService(mMockContext, mFakeCarWatchdog);
    }

    /**
     * Test that the {@link CarWatchdogService} binds to car watchdog daemon.
     */
    @Test
    public void testInitializeCarWatchdService() throws Exception {
        mCarWatchdogService.init();
        mFakeCarWatchdog.waitForMediatorResponse();
        assertThat(mFakeCarWatchdog.getClientCount()).isEqualTo(1);
        assertThat(mFakeCarWatchdog.gotResponse()).isTrue();
    }

    // FakeCarWatchdog mimics ICarWatchdog daemon in local process.
    final class FakeCarWatchdog extends ICarWatchdog.Default {

        private static final int TEST_SESSION_ID = 11223344;
        private static final int TEN_SECONDS_IN_MS = 10000;

        private final Handler mMainHandler = new Handler(Looper.getMainLooper());
        private final List<ICarWatchdogClient> mClients = new ArrayList<>();
        private long mLastPingTimeMs;
        private boolean mGotResponse;
        private CountDownLatch mClientResponse = new CountDownLatch(1);

        int getClientCount() {
            return mClients.size();
        }

        boolean gotResponse() {
            return mGotResponse;
        }

        void waitForMediatorResponse() throws InterruptedException {
            if (!mClientResponse.await(TEN_SECONDS_IN_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Mediator doesn't respond within timeout(" + TEN_SECONDS_IN_MS + "ms)");
            }
        }

        @Override
        public void registerMediator(ICarWatchdogClient mediator) throws RemoteException {
            mClients.add(mediator);
            mMainHandler.post(() -> {
                try {
                    mediator.checkIfAlive(TEST_SESSION_ID, TimeoutLength.TIMEOUT_NORMAL);
                } catch (RemoteException e) {
                    // Do nothing.
                }
                mLastPingTimeMs = System.currentTimeMillis();
            });
        }

        @Override
        public void unregisterMediator(ICarWatchdogClient mediator) throws RemoteException {
            mClients.remove(mediator);
        }

        @Override
        public void tellMediatorAlive(ICarWatchdogClient mediator, int[] clientsNotResponding,
                int sessionId) throws RemoteException {
            long currentTimeMs = System.currentTimeMillis();
            if (sessionId == TEST_SESSION_ID && mClients.contains(mediator)
                    && currentTimeMs < mLastPingTimeMs + TEN_SECONDS_IN_MS) {
                mGotResponse = true;
            }
            mClientResponse.countDown();
        }
    }
}
