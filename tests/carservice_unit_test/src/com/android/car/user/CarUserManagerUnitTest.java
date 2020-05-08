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
package com.android.car.user;

import static android.car.test.mocks.AndroidMockitoHelper.mockUmGetUsers;
import static android.car.test.util.UserTestingHelper.newUsers;
import static android.car.testapi.CarMockitoHelper.mockHandleRemoteExceptionFromCarServiceWithDefaultValue;
import static android.os.UserHandle.USER_SYSTEM;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.car.Car;
import android.car.ICarUserService;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserSwitchUiCallback;
import android.car.user.GetUserIdentificationAssociationResponse;
import android.car.user.UserSwitchResult;
import android.content.pm.UserInfo;
import android.os.RemoteException;
import android.os.UserManager;

import com.android.internal.infra.AndroidFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class CarUserManagerUnitTest extends AbstractExtendedMockitoTestCase {

    private static final long ASYNC_TIMEOUT_MS = 500;

    @Mock
    private Car mCar;
    @Mock
    private UserManager mUserManager;
    @Mock
    private ICarUserService mService;

    private CarUserManager mMgr;

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(UserManager.class);
    }

    @Before
    public void setFixtures() {
        mMgr = new CarUserManager(mCar, mService, mUserManager);
    }

    @Test
    public void testIsValidUser_headlessSystemUser() {
        mockIsHeadlessSystemUserMode(true);
        setExistingUsers(USER_SYSTEM);

        assertThat(mMgr.isValidUser(USER_SYSTEM)).isFalse();
    }

    @Test
    public void testIsValidUser_nonHeadlessSystemUser() {
        mockIsHeadlessSystemUserMode(false);
        setExistingUsers(USER_SYSTEM);

        assertThat(mMgr.isValidUser(USER_SYSTEM)).isTrue();
    }

    @Test
    public void testIsValidUser_found() {
        setExistingUsers(1, 2, 3);

        assertThat(mMgr.isValidUser(1)).isTrue();
        assertThat(mMgr.isValidUser(2)).isTrue();
        assertThat(mMgr.isValidUser(3)).isTrue();
    }

    @Test
    public void testIsValidUser_notFound() {
        setExistingUsers(1, 2, 3);

        assertThat(mMgr.isValidUser(4)).isFalse();
    }

    @Test
    public void testIsValidUser_emptyUsers() {
        assertThat(mMgr.isValidUser(666)).isFalse();
    }

    @Test
    public void testSwitchUser_success() throws Exception {
        expectServiceSwitchUserSucceeds(11, UserSwitchResult.STATUS_SUCCESSFUL,
                "D'OH!");

        AndroidFuture<UserSwitchResult> future = mMgr.switchUser(11);

        assertThat(future).isNotNull();
        UserSwitchResult result = getResult(future);
        assertThat(result.getStatus()).isEqualTo(UserSwitchResult.STATUS_SUCCESSFUL);
        assertThat(result.getErrorMessage()).isEqualTo("D'OH!");
    }

    @Test
    public void testSwitchUser_remoteException() throws Exception {
        expectServiceSwitchUserSucceeds(11);
        mockHandleRemoteExceptionFromCarServiceWithDefaultValue(mCar);

        AndroidFuture<UserSwitchResult> future = mMgr.switchUser(11);

        assertThat(future).isNotNull();
        UserSwitchResult result = getResult(future);
        assertThat(result.getStatus()).isEqualTo(UserSwitchResult.STATUS_HAL_INTERNAL_FAILURE);
        assertThat(result.getErrorMessage()).isNull();
    }

    @Test
    public void testSetSwitchUserUICallback_success() throws Exception {
        UserSwitchUiCallback callback = (u)-> { };

        mMgr.setUserSwitchUiCallback(callback);

        verify(mService).setUserSwitchUiCallback(any());
    }

    @Test
    public void testSetSwitchUserUICallback_nullCallback() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> mMgr.setUserSwitchUiCallback(null));
    }

    @Test
    public void testGetUserIdentificationAssociation_nullTypes() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mMgr.getUserIdentificationAssociation(null));
    }

    @Test
    public void testGetUserIdentificationAssociation_emptyTypes() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> mMgr.getUserIdentificationAssociation(new int[] {}));
    }

    @Test
    public void testGetUserIdentificationAssociation_remoteException() throws Exception {
        mockHandleRemoteExceptionFromCarServiceWithDefaultValue(mCar);
        assertThrows(IllegalArgumentException.class,
                () -> mMgr.getUserIdentificationAssociation(new int[] {}));
    }

    @Test
    public void testGetUserIdentificationAssociation_ok() throws Exception {
        int[] types = new int[] { 4, 8, 15, 16, 23, 42 };
        GetUserIdentificationAssociationResponse expectedResponse =
                new GetUserIdentificationAssociationResponse(null, new int[] {});
        when(mService.getUserIdentificationAssociation(types)).thenReturn(expectedResponse);

        GetUserIdentificationAssociationResponse actualResponse =
                mMgr.getUserIdentificationAssociation(types);

        assertThat(actualResponse).isSameAs(expectedResponse);
    }

    private void expectServiceSwitchUserSucceeds(@UserIdInt int userId,
            @UserSwitchResult.Status int status, @Nullable String errorMessage)
            throws RemoteException {
        doAnswer((invocation) -> {
            @SuppressWarnings("unchecked")
            AndroidFuture<UserSwitchResult> future = (AndroidFuture<UserSwitchResult>) invocation
                    .getArguments()[2];
            future.complete(new UserSwitchResult(status, errorMessage));
            return null;
        }).when(mService).switchUser(eq(userId), anyInt(), notNull());
    }

    private void expectServiceSwitchUserSucceeds(@UserIdInt int userId) throws RemoteException {
        doThrow(new RemoteException("D'OH!")).when(mService)
            .switchUser(eq(userId), anyInt(), notNull());
    }

    @NonNull
    private static <T> T getResult(@NonNull AndroidFuture<T> future) throws Exception {
        try {
            return future.get(ASYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("not called in " + ASYNC_TIMEOUT_MS + "ms", e);
        }
    }

    private void setExistingUsers(int... userIds) {
        List<UserInfo> users = newUsers(userIds);
        mockUmGetUsers(mUserManager, users);
    }
}
