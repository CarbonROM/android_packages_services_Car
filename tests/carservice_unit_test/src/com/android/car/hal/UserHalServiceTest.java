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
package com.android.car.hal;

import static android.car.VehiclePropertyIds.CURRENT_GEAR;
import static android.car.VehiclePropertyIds.INITIAL_USER_INFO;
import static android.car.VehiclePropertyIds.SWITCH_USER;
import static android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType.COLD_BOOT;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.assertThrows;

import android.car.hardware.property.VehicleHalStatusCode;
import android.car.userlib.HalCallback;
import android.car.userlib.UserHalHelper;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.SwitchUserMessageType;
import android.hardware.automotive.vehicle.V2_0.SwitchUserResponse;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.UserInfo;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyAccess;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyChangeMode;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(MockitoJUnitRunner.class)
public final class UserHalServiceTest {

    private static final String TAG = UserHalServiceTest.class.getSimpleName();

    /**
     * Timeout passed to {@link UserHalService} methods
     */
    private static final int TIMEOUT_MS = 20;

    /**
     * Timeout for {@link GenericHalCallback#assertCalled()} for tests where the HAL is supposed to
     * return something - it's a short time so it doesn't impact the test duration.
     */
    private static final int CALLBACK_TIMEOUT_SUCCESS = TIMEOUT_MS + 50;

    /**
     * Timeout for {@link GenericHalCallback#assertCalled()} for tests where the HAL is not supposed
     * to return anything - it's a slightly longer to make sure the test doesn't fail prematurely.
     */
    private static final int CALLBACK_TIMEOUT_TIMEOUT = TIMEOUT_MS + 500;

    // Used when crafting a reqquest property - the real value will be set by the mock.
    private static final int REQUEST_ID_PLACE_HOLDER = 42;

    private static final int INITIAL_USER_INFO_RESPONSE_ACTION = 108;

    @Mock
    private VehicleHal mVehicleHal;

    private final UserInfo mUser0 = new UserInfo();
    private final UserInfo mUser10 = new UserInfo();

    private final UsersInfo mUsersInfo = new UsersInfo();

    private UserHalService mUserHalService;

    @Before
    public void setFixtures() {
        mUserHalService = new UserHalService(mVehicleHal);
        mUserHalService.takeProperties(Arrays.asList(
                newSubscribableConfig(INITIAL_USER_INFO),
                newSubscribableConfig(SWITCH_USER)));

        mUser0.userId = 0;
        mUser0.flags = 100;
        mUser10.userId = 10;
        mUser10.flags = 110;

        mUsersInfo.currentUser = mUser0;
        mUsersInfo.numberUsers = 2;
        mUsersInfo.existingUsers = new ArrayList<>(2);
        mUsersInfo.existingUsers.add(mUser0);
        mUsersInfo.existingUsers.add(mUser10);
    }

    @Test
    public void testTakeSupportedProperties_unsupportedOnly() {
        // Cannot use mUserHalService because it's already set with supported properties
        UserHalService myHalService = new UserHalService(mVehicleHal);

        myHalService.takeProperties(Collections.EMPTY_LIST);
        assertThat(myHalService.isSupported()).isFalse();
    }

    @Test
    public void testTakeSupportedPropertiesAndInit() {
        // Cannot use mUserHalService because it's already set with supported properties
        UserHalService myHalService = new UserHalService(mVehicleHal);

        VehiclePropConfig unsupportedConfig = newConfig(CURRENT_GEAR);
        VehiclePropConfig userInfoConfig = newSubscribableConfig(INITIAL_USER_INFO);
        List<VehiclePropConfig> input = Arrays.asList(unsupportedConfig, userInfoConfig);
        myHalService.takeProperties(input);
        assertThat(mUserHalService.isSupported()).isTrue();

        // Ideally there should be 2 test methods (one for takeSupportedProperties() and one for
        // init()), but on "real life" VehicleHal calls these 2 methods in sequence, and the latter
        // depends on the properties set by the former, so it's ok to test both here...
        myHalService.init();
        verify(mVehicleHal).subscribeProperty(myHalService, INITIAL_USER_INFO);
    }

    @Test
    public void testSupportedProperties() {
        assertThat(mUserHalService.getAllSupportedProperties()).asList().containsAllOf(
                INITIAL_USER_INFO,
                SWITCH_USER);
    }

    @Test
    public void testGetUserInfo_invalidTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> mUserHalService.getInitialUserInfo(COLD_BOOT, 0, mUsersInfo, (i, r) -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> mUserHalService.getInitialUserInfo(COLD_BOOT, -1, mUsersInfo, (i, r) -> {
                }));
    }

    @Test
    public void testGetUserInfo_noUsersInfo() {
        assertThrows(NullPointerException.class,
                () -> mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, null,
                        (i, r) -> {
                        }));
    }

    @Test
    public void testGetUserInfo_noCallback() {
        assertThrows(NullPointerException.class,
                () -> mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS,
                        mUsersInfo, null));
    }

    @Test
    public void testGetUserInfo_halSetTimedOut() throws Exception {
        replySetPropertyWithTimeoutException(INITIAL_USER_INFO);

        GenericHalCallback<InitialUserInfoResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback);

        callback.assertCalled();
        assertCallbackStatus(callback, HalCallback.STATUS_HAL_SET_TIMEOUT);
        assertThat(callback.response).isNull();

        // Make sure the pending request was removed
        SystemClock.sleep(CALLBACK_TIMEOUT_TIMEOUT);
        callback.assertNotCalledAgain();
    }

    @Test
    public void testGetUserInfo_halDidNotReply() throws Exception {
        GenericHalCallback<InitialUserInfoResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback);

        callback.assertCalled();
        assertCallbackStatus(callback, HalCallback.STATUS_HAL_RESPONSE_TIMEOUT);
        assertThat(callback.response).isNull();
    }

    @Test
    public void testGetUserInfo_secondCallFailWhilePending() throws Exception {
        GenericHalCallback<InitialUserInfoResponse> callback1 = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        GenericHalCallback<InitialUserInfoResponse> callback2 = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback1);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback2);

        callback1.assertCalled();
        assertCallbackStatus(callback1, HalCallback.STATUS_HAL_RESPONSE_TIMEOUT);
        assertThat(callback1.response).isNull();

        callback2.assertCalled();
        assertCallbackStatus(callback2, HalCallback.STATUS_CONCURRENT_OPERATION);
        assertThat(callback1.response).isNull();
    }

    @Test
    public void testGetUserInfo_halReplyWithWrongRequestId() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    INITIAL_USER_INFO_RESPONSE_ACTION, INITIAL_USER_INFO);

        replySetPropertyWithOnChangeEvent(INITIAL_USER_INFO, propResponse,
                /* rightRequestId= */ false);

        GenericHalCallback<InitialUserInfoResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback);

        callback.assertCalled();
        assertCallbackStatus(callback, HalCallback.STATUS_HAL_RESPONSE_TIMEOUT);
        assertThat(callback.response).isNull();
    }

    @Test
    public void testGetUserInfo_halReturnedInvalidAction() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    INITIAL_USER_INFO_RESPONSE_ACTION, INITIAL_USER_INFO);

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                INITIAL_USER_INFO, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<InitialUserInfoResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertInitialUserInfoSetRequest(reqCaptor.get(), COLD_BOOT);

        // Assert response
        assertCallbackStatus(callback, HalCallback.STATUS_WRONG_HAL_RESPONSE);
        assertThat(callback.response).isNull();
    }

    @Test
    public void testGetUserInfo_successDefault() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    InitialUserInfoResponseAction.DEFAULT, INITIAL_USER_INFO);

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                INITIAL_USER_INFO, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<InitialUserInfoResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertInitialUserInfoSetRequest(reqCaptor.get(), COLD_BOOT);

        // Assert response
        assertCallbackStatus(callback, HalCallback.STATUS_OK);
        InitialUserInfoResponse actualResponse = callback.response;
        assertThat(actualResponse.action).isEqualTo(InitialUserInfoResponseAction.DEFAULT);
        assertThat(actualResponse.userNameToCreate).isEmpty();
        assertThat(actualResponse.userToSwitchOrCreate).isNotNull();
        assertThat(actualResponse.userToSwitchOrCreate.userId).isEqualTo(UserHandle.USER_NULL);
        assertThat(actualResponse.userToSwitchOrCreate.flags).isEqualTo(UserFlags.NONE);
    }

    @Test
    public void testGetUserInfo_successSwitchUser() throws Exception {
        int userIdToSwitch = 42;
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    InitialUserInfoResponseAction.SWITCH, INITIAL_USER_INFO);
        propResponse.value.int32Values.add(userIdToSwitch);

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                INITIAL_USER_INFO, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<InitialUserInfoResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertInitialUserInfoSetRequest(reqCaptor.get(), COLD_BOOT);

        assertCallbackStatus(callback, HalCallback.STATUS_OK);
        InitialUserInfoResponse actualResponse = callback.response;
        assertThat(actualResponse.action).isEqualTo(InitialUserInfoResponseAction.SWITCH);
        assertThat(actualResponse.userNameToCreate).isEmpty();
        UserInfo userToSwitch = actualResponse.userToSwitchOrCreate;
        assertThat(userToSwitch).isNotNull();
        assertThat(userToSwitch.userId).isEqualTo(userIdToSwitch);
        assertThat(userToSwitch.flags).isEqualTo(UserFlags.NONE);
    }

    @Test
    public void testGetUserInfo_successCreateUser() throws Exception {
        int newUserFlags = 108;
        String newUserName = "Groot";
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    InitialUserInfoResponseAction.CREATE, INITIAL_USER_INFO);
        propResponse.value.int32Values.add(newUserFlags);
        propResponse.value.stringValue = newUserName;

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                INITIAL_USER_INFO, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<InitialUserInfoResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.getInitialUserInfo(COLD_BOOT, TIMEOUT_MS, mUsersInfo,
                callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertInitialUserInfoSetRequest(reqCaptor.get(), COLD_BOOT);

        assertCallbackStatus(callback, HalCallback.STATUS_OK);
        assertThat(callback.status).isEqualTo(HalCallback.STATUS_OK);
        InitialUserInfoResponse actualResponse = callback.response;
        assertThat(actualResponse.action).isEqualTo(InitialUserInfoResponseAction.CREATE);
        assertThat(actualResponse.userNameToCreate).isEqualTo(newUserName);
        UserInfo newUser = actualResponse.userToSwitchOrCreate;
        assertThat(newUser).isNotNull();
        assertThat(newUser.userId).isEqualTo(UserHandle.USER_NULL);
        assertThat(newUser.flags).isEqualTo(newUserFlags);
    }

    @Test
    public void testGetUserInfo_twoSuccessfulCalls() throws Exception {
        testGetUserInfo_successDefault();
        testGetUserInfo_successDefault();
    }

    @Test
    public void testSwitchUser_invalidTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> mUserHalService.switchUser(mUser10, 0, mUsersInfo, (i, r) -> {
                }));
        assertThrows(IllegalArgumentException.class,
                () -> mUserHalService.switchUser(mUser10, -1, mUsersInfo, (i, r) -> {
                }));
    }

    @Test
    public void testSwitchUser_noUsersInfo() {
        assertThrows(NullPointerException.class,
                () -> mUserHalService.switchUser(mUser10, TIMEOUT_MS, null,
                        (i, r) -> {
                        }));
    }

    @Test
    public void testSwitchUser_noCallback() {
        assertThrows(NullPointerException.class,
                () -> mUserHalService.switchUser(mUser10, TIMEOUT_MS,
                        mUsersInfo, null));
    }

    @Test
    public void testSwitchUser_halSetTimedOut() throws Exception {
        replySetPropertyWithTimeoutException(SWITCH_USER);

        GenericHalCallback<SwitchUserResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback);

        callback.assertCalled();
        assertCallbackStatus(callback, HalCallback.STATUS_HAL_SET_TIMEOUT);
        assertThat(callback.response).isNull();

        // Make sure the pending request was removed
        SystemClock.sleep(CALLBACK_TIMEOUT_TIMEOUT);
        callback.assertNotCalledAgain();
    }

    @Test
    public void testSwitchUser_halDidNotReply() throws Exception {
        GenericHalCallback<SwitchUserResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback);

        callback.assertCalled();
        assertCallbackStatus(callback, HalCallback.STATUS_HAL_RESPONSE_TIMEOUT);
        assertThat(callback.response).isNull();
    }

    @Test
    public void testSwitchUser_halReplyWithWrongRequestId() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    InitialUserInfoResponseAction.SWITCH, SWITCH_USER);

        replySetPropertyWithOnChangeEvent(SWITCH_USER, propResponse,
                /* rightRequestId= */ false);

        GenericHalCallback<SwitchUserResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback);

        callback.assertCalled();
        assertCallbackStatus(callback, HalCallback.STATUS_HAL_RESPONSE_TIMEOUT);
        assertThat(callback.response).isNull();
    }

    @Test
    public void testSwitchUser_halReturnedInvalidMessageType() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    SwitchUserMessageType.VEHICLE_REQUEST, SWITCH_USER);
        propResponse.value.int32Values.add(SwitchUserStatus.SUCCESS);

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                SWITCH_USER, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<SwitchUserResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertSwitchUserSetRequest(reqCaptor.get(), SwitchUserMessageType.ANDROID_SWITCH, mUser10);

        // Assert response
        assertCallbackStatus(callback, HalCallback.STATUS_WRONG_HAL_RESPONSE);
        assertThat(callback.response).isNull();
    }

    @Test
    public void testUserSwitch_success() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    SwitchUserMessageType.VEHICLE_RESPONSE, SWITCH_USER);
        propResponse.value.int32Values.add(SwitchUserStatus.SUCCESS);

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                SWITCH_USER, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<SwitchUserResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertSwitchUserSetRequest(reqCaptor.get(), SwitchUserMessageType.ANDROID_SWITCH, mUser10);

        // Assert response
        assertCallbackStatus(callback, HalCallback.STATUS_OK);
        SwitchUserResponse actualResponse = callback.response;
        assertThat(actualResponse.status).isEqualTo(SwitchUserStatus.SUCCESS);
        assertThat(actualResponse.messageType).isEqualTo(SwitchUserMessageType.VEHICLE_RESPONSE);
    }

    @Test
    public void testUserSwitch_failure() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    SwitchUserMessageType.VEHICLE_RESPONSE, SWITCH_USER);
        propResponse.value.int32Values.add(SwitchUserStatus.FAILURE);

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                SWITCH_USER, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<SwitchUserResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertSwitchUserSetRequest(reqCaptor.get(), SwitchUserMessageType.ANDROID_SWITCH, mUser10);

        // Assert response
        assertCallbackStatus(callback, HalCallback.STATUS_OK);
        SwitchUserResponse actualResponse = callback.response;
        assertThat(actualResponse.status).isEqualTo(SwitchUserStatus.FAILURE);
        assertThat(actualResponse.messageType).isEqualTo(SwitchUserMessageType.VEHICLE_RESPONSE);
    }

    @Test
    public void testSwitchUser_secondCallFailWhilePending() throws Exception {
        GenericHalCallback<SwitchUserResponse> callback1 = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        GenericHalCallback<SwitchUserResponse> callback2 = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_TIMEOUT);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback1);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback2);

        callback1.assertCalled();
        assertCallbackStatus(callback1, HalCallback.STATUS_HAL_RESPONSE_TIMEOUT);
        assertThat(callback1.response).isNull();

        callback2.assertCalled();
        assertCallbackStatus(callback2, HalCallback.STATUS_CONCURRENT_OPERATION);
        assertThat(callback1.response).isNull();
    }

    @Test
    public void testSwitchUser_halReturnedInvalidStatus() throws Exception {
        VehiclePropValue propResponse = UserHalHelper.createPropRequest(REQUEST_ID_PLACE_HOLDER,
                    SwitchUserMessageType.VEHICLE_RESPONSE, SWITCH_USER);
        propResponse.value.int32Values.add(/*status =*/ 110); // an invalid status

        AtomicReference<VehiclePropValue> reqCaptor = replySetPropertyWithOnChangeEvent(
                SWITCH_USER, propResponse, /* rightRequestId= */ true);

        GenericHalCallback<SwitchUserResponse> callback = new GenericHalCallback<>(
                CALLBACK_TIMEOUT_SUCCESS);
        mUserHalService.switchUser(mUser10, TIMEOUT_MS, mUsersInfo, callback);

        callback.assertCalled();

        // Make sure the arguments were properly converted
        assertSwitchUserSetRequest(reqCaptor.get(), SwitchUserMessageType.ANDROID_SWITCH, mUser10);

        // Assert response
        assertCallbackStatus(callback, HalCallback.STATUS_WRONG_HAL_RESPONSE);
        assertThat(callback.response).isNull();
    }

    @Test
    public void testPostSwitchResponse_noUsersInfo() {
        assertThrows(NullPointerException.class,
                () -> mUserHalService.postSwitchResponse(42, mUser10, null));
    }

    @Test
    public void testPostSwitchResponse_HalCalledWithCorrectProp() {
        mUserHalService.postSwitchResponse(42, mUser10, mUsersInfo);
        ArgumentCaptor<VehiclePropValue> propCaptor =
                ArgumentCaptor.forClass(VehiclePropValue.class);
        verify(mVehicleHal).set(propCaptor.capture());
        VehiclePropValue prop = propCaptor.getValue();
        assertPostSwitchResponseSetRequest(prop, SwitchUserMessageType.ANDROID_POST_SWITCH,
                mUser10);
    }

    /**
     * Asserts the given {@link UsersInfo} is properly represented in the {@link VehiclePropValue}.
     *
     * @param value property containing the info
     * @param info info to be checked
     * @param initialIndex first index of the info values in the property's {@code int32Values}
     */
    private void assertUsersInfo(VehiclePropValue value, UsersInfo info, int initialIndex) {
        // TODO(b/150419600): use helper method to convert prop value to proper req to check users
        ArrayList<Integer> values = value.value.int32Values;
        assertWithMessage("wrong values size").that(values)
                .hasSize(initialIndex + 3 + info.numberUsers * 2);

        int i = initialIndex;
        assertWithMessage("currentUser.id mismatch at index %s", i).that(values.get(i))
                .isEqualTo(info.currentUser.userId);
        i++;
        assertWithMessage("currentUser.flags mismatch at index %s", i).that(values.get(i))
            .isEqualTo(info.currentUser.flags);
        i++;
        assertWithMessage("numberUsers mismatch at index %s", i).that(values.get(i))
            .isEqualTo(info.numberUsers);
        i++;

        for (int j = 0; j < info.numberUsers; j++) {
            int actualUserId = values.get(i++);
            int actualUserFlags = values.get(i++);
            UserInfo expectedUser = info.existingUsers.get(j);
            assertWithMessage("wrong id for existing user#%s at index %s", j, i)
                .that(actualUserId).isEqualTo(expectedUser.userId);
            assertWithMessage("wrong flags for existing user#%s at index %s", j, i)
                .that(actualUserFlags).isEqualTo(expectedUser.flags);
        }
    }

    /**
     * Sets the VHAL mock to emulate a property change event upon a call to set a property.
     *
     * @param prop prop to be set
     * @param response response to be set on event
     * @param rightRequestId whether the response id should match the request
     * @return
     *
     * @return reference to the value passed to {@code set()}.
     */
    private AtomicReference<VehiclePropValue> replySetPropertyWithOnChangeEvent(int prop,
            VehiclePropValue response, boolean rightRequestId) throws Exception {
        AtomicReference<VehiclePropValue> ref = new AtomicReference<>();
        doAnswer((inv) -> {
            VehiclePropValue request = inv.getArgument(0);
            ref.set(request);
            int requestId = request.value.int32Values.get(0);
            int responseId = rightRequestId ? requestId : requestId + 1000;
            response.value.int32Values.set(0, responseId);
            Log.d(TAG, "mockSetPropertyWithOnChange(): resp=" + response + " for req=" + request);
            mUserHalService.onHalEvents(Arrays.asList(response));
            return null;
        }).when(mVehicleHal).set(isProperty(prop));
        return ref;
    }

    /**
     * Sets the VHAL mock to emulate a property timeout exception upon a call to set a property.
     */
    private void replySetPropertyWithTimeoutException(int prop) throws Exception {
        doThrow(new ServiceSpecificException(VehicleHalStatusCode.STATUS_TRY_AGAIN,
                "PropId: 0x" + Integer.toHexString(prop))).when(mVehicleHal).set(isProperty(prop));
    }

    private void assertInitialUserInfoSetRequest(VehiclePropValue req, int requestType) {
        assertThat(req.value.int32Values.get(1)).isEqualTo(requestType);
        assertUsersInfo(req, mUsersInfo, 2);
    }

    private void assertSwitchUserSetRequest(VehiclePropValue req, int messageType,
            UserInfo targetUserInfo) {
        assertThat(req.value.int32Values.get(1)).isEqualTo(messageType);
        assertWithMessage("targetuser.id mismatch").that(req.value.int32Values.get(2))
                .isEqualTo(targetUserInfo.userId);
        assertWithMessage("targetuser.flags mismatch").that(req.value.int32Values.get(3))
                .isEqualTo(targetUserInfo.flags);
        assertUsersInfo(req, mUsersInfo, 4);
    }

    private void assertPostSwitchResponseSetRequest(VehiclePropValue req, int messageType,
            UserInfo targetUserInfo) {
        assertThat(req.value.int32Values.get(1)).isEqualTo(messageType);
        assertWithMessage("targetuser.id mismatch").that(req.value.int32Values.get(2))
                .isEqualTo(targetUserInfo.userId);
        assertWithMessage("targetuser.flags mismatch").that(req.value.int32Values.get(3))
                .isEqualTo(targetUserInfo.flags);
        assertUsersInfo(req, mUsersInfo, 4);
    }

    private void assertCallbackStatus(GenericHalCallback callback,
            int expectedStatus) {
        int actualStatus = callback.status;
        if (actualStatus == expectedStatus) return;

        fail("Wrong callback status; expected "
                + UserHalHelper.halCallbackStatusToString(expectedStatus) + ", got "
                + UserHalHelper.halCallbackStatusToString(actualStatus));
    }

    private final class GenericHalCallback<R> implements HalCallback<R> {

        private final CountDownLatch mLatch = new CountDownLatch(1);
        private final int mTimeout;
        private final List<Pair<Integer, R>> mExtraCalls = new ArrayList<>();

        public int status;
        public R response;

        GenericHalCallback(int timeout) {
            this.mTimeout = timeout;
        }

        @Override
        public void onResponse(int status, R response) {
            Log.d(TAG, "onResponse(): status=" + status + ", response=" +  response);
            this.status = status;
            this.response = response;
            if (mLatch.getCount() == 0) {
                Log.e(TAG, "Already responded");
                mExtraCalls.add(new Pair<>(status, response));
                return;
            }
            mLatch.countDown();
        }

        /**
         * Asserts that the callback was called, or fail if it timed out.
         */
        public void assertCalled() throws InterruptedException {
            Log.d(TAG, "assertCalled(): waiting " + mTimeout + "ms");
            if (!mLatch.await(mTimeout, TimeUnit.MILLISECONDS)) {
                throw new AssertionError("callback not called in " + mTimeout + "ms");
            }
        }

        /**
         * Asserts that the callback was not called more than once.
         */
        public void assertNotCalledAgain() {
            if (mExtraCalls.isEmpty()) return;
            throw new AssertionError("Called " + mExtraCalls.size() + " times more than expected: "
                    + mExtraCalls);
        }
    }

    // TODO(b/149099817): move stuff below to common code

    /**
     * Custom Mockito matcher to check if a {@link VehiclePropValue} has the given {@code prop}.
     */
    public static VehiclePropValue isProperty(int prop) {
        return argThat(new PropertyIdMatcher(prop));
    }

    private static class PropertyIdMatcher implements ArgumentMatcher<VehiclePropValue> {

        public final int prop;

        private PropertyIdMatcher(int prop) {
            this.prop = prop;
        }

        @Override
        public boolean matches(VehiclePropValue argument) {
            return argument.prop == prop;
        }
    }

    /**
     * Creates an empty config for the given property.
     */
    private static VehiclePropConfig newConfig(int prop) {
        VehiclePropConfig config = new VehiclePropConfig();
        config.prop = prop;
        return config;
    }

    /**
     * Creates a config for the given property that passes the
     * {@link VehicleHal#isPropertySubscribable(VehiclePropConfig)} criteria.
     */
    private static VehiclePropConfig newSubscribableConfig(int prop) {
        VehiclePropConfig config = newConfig(prop);
        config.access = VehiclePropertyAccess.READ_WRITE;
        config.changeMode = VehiclePropertyChangeMode.ON_CHANGE;
        return config;
    }
}
