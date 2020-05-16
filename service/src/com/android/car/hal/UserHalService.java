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

import static android.car.VehiclePropertyIds.INITIAL_USER_INFO;
import static android.car.VehiclePropertyIds.SWITCH_USER;
import static android.car.VehiclePropertyIds.USER_IDENTIFICATION_ASSOCIATION;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.car.hardware.property.CarPropertyManager;
import android.car.user.CarUserManager;
import android.car.userlib.HalCallback;
import android.car.userlib.UserHalHelper;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.SwitchUserMessageType;
import android.hardware.automotive.vehicle.V2_0.SwitchUserResponse;
import android.hardware.automotive.vehicle.V2_0.SwitchUserStatus;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationAssociationType;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationGetRequest;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationResponse;
import android.hardware.automotive.vehicle.V2_0.UserIdentificationSetRequest;
import android.hardware.automotive.vehicle.V2_0.UserInfo;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceSpecificException;
import android.sysprop.CarProperties;
import android.text.TextUtils;
import android.util.EventLog;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;

import com.android.car.CarLocalServices;
import com.android.car.user.CarUserService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.car.EventLogTags;
import com.android.internal.util.FunctionalUtils;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Service used to integrate the OEM's custom user management with Android's.
 */
public final class UserHalService extends HalServiceBase {

    private static final String TAG = UserHalService.class.getSimpleName();

    private static final String UNSUPPORTED_MSG = "Vehicle HAL does not support user management";

    private static final int[] SUPPORTED_PROPERTIES = new int[]{
            INITIAL_USER_INFO,
            SWITCH_USER,
            USER_IDENTIFICATION_ASSOCIATION
    };

    // TODO(b/150413515): STOPSHIP - change to false before R is launched
    private static final boolean DBG = true;

    private final Object mLock = new Object();

    private final VehicleHal mHal;

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<VehiclePropConfig> mProperties;

    // This handler handles 2 types of messages:
    // - "Anonymous" messages (what=0) containing runnables.
    // - "Identifiable" messages used to check for timeouts (whose 'what' is the request id).
    private final Handler mHandler;

    /**
     * Value used on the next request.
     */
    @GuardedBy("mLock")
    private int mNextRequestId = 1;

    /**
     * Map of callbacks by request id.
     */
    @GuardedBy("mLock")
    private final SparseArray<PendingRequest<?, ?>> mPendingRequests = new SparseArray<>();

    public UserHalService(VehicleHal hal) {
        this(hal, new Handler(Looper.getMainLooper()));
    }

    @VisibleForTesting
    UserHalService(VehicleHal hal, Handler handler) {
        mHal = hal;
        mHandler = handler;
    }

    @Override
    public void init() {
        if (DBG) Log.d(TAG, "init()");

        if (mProperties == null) {
            return;
        }

        int size = mProperties.size();
        for (int i = 0; i < size; i++) {
            VehiclePropConfig config = mProperties.valueAt(i);
            if (VehicleHal.isPropertySubscribable(config)) {
                if (DBG) Log.d(TAG, "subscribing to property " + config.prop);
                mHal.subscribeProperty(this, config.prop);
            }
        }
    }

    @Override
    public void release() {
        if (DBG) Log.d(TAG, "release()");
    }

    @Override
    public void onHalEvents(List<VehiclePropValue> values) {
        if (DBG) Log.d(TAG, "handleHalEvents(): " + values);

        for (int i = 0; i < values.size(); i++) {
            VehiclePropValue value = values.get(i);
            switch (value.prop) {
                case INITIAL_USER_INFO:
                    mHandler.sendMessage(obtainMessage(
                            UserHalService::handleOnInitialUserInfoResponse, this, value));
                    break;
                case SWITCH_USER:
                    mHandler.sendMessage(obtainMessage(
                            UserHalService::handleOnSwitchUserResponse, this, value));
                    break;
                case USER_IDENTIFICATION_ASSOCIATION:
                    mHandler.sendMessage(obtainMessage(
                            UserHalService::handleOnUserIdentificationAssociation, this, value));
                    break;
                default:
                    Log.w(TAG, "received unsupported event from HAL: " + value);
            }
        }
    }

    @Override
    public void onPropertySetError(int property, int area,
            @CarPropertyManager.CarSetPropertyErrorCode int errorCode) {
        if (DBG) Log.d(TAG, "handlePropertySetError(" + property + "/" + area + ")");
    }

    @Override
    public int[] getAllSupportedProperties() {
        return SUPPORTED_PROPERTIES;
    }

    @Override
    public void takeProperties(Collection<VehiclePropConfig> properties) {
        if (properties.isEmpty()) {
            Log.w(TAG, UNSUPPORTED_MSG);
            return;
        }
        // TODO(b/150413515): increase capacity as more properties are added
        SparseArray<VehiclePropConfig> supportedProperties = new SparseArray<>(2);
        for (VehiclePropConfig config : properties) {
            supportedProperties.put(config.prop, config);
        }
        synchronized (mLock) {
            mProperties = supportedProperties;
        }
    }

    /**
     * Checks if the Vehicle HAL supports user management.
     */
    public boolean isSupported() {
        synchronized (mLock) {
            return mProperties != null;
        }
    }

    @GuardedBy("mLock")
    private void checkSupportedLocked() {
        Preconditions.checkState(isSupported(), UNSUPPORTED_MSG);
    }

    /**
     * Calls HAL to asynchronously get info about the initial user.
     *
     * @param requestType type of request (as defined by
     * {@link android.hardware.automotive.vehicle.V2_0.InitialUserInfoRequestType}).
     * @param timeoutMs how long to wait (in ms) for the property change event.
     * @param usersInfo current state of Android users.
     * @param callback to handle the response.
     *
     * @throws IllegalStateException if the HAL does not support user management (callers should
     * call {@link #isSupported()} first to avoid this exception).
     */
    public void getInitialUserInfo(int requestType, int timeoutMs, @NonNull UsersInfo usersInfo,
            @NonNull HalCallback<InitialUserInfoResponse> callback) {
        if (DBG) Log.d(TAG, "getInitialInfo(" + requestType + ")");
        Preconditions.checkArgumentPositive(timeoutMs, "timeout must be positive");
        Objects.requireNonNull(usersInfo);
        // TODO(b/150413515): use helper method to check usersInfo is valid
        Objects.requireNonNull(callback);

        VehiclePropValue propRequest;
        int requestId;
        synchronized (mLock) {
            checkSupportedLocked();
            if (hasPendingRequestLocked(InitialUserInfoResponse.class, callback)) return;
            requestId = getNextRequestId();
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_INITIAL_USER_INFO_REQ, requestId,
                    requestType, timeoutMs);
            propRequest = UserHalHelper.createPropRequest(INITIAL_USER_INFO, requestId,
                    requestType);
            UserHalHelper.addUsersInfo(propRequest, usersInfo);
            addPendingRequestLocked(requestId, InitialUserInfoResponse.class, callback);
        }

        sendHalRequest(requestId, timeoutMs, propRequest, callback);
    }

    private void sendHalRequest(int requestId, int timeoutMs, @NonNull VehiclePropValue request,
            @NonNull HalCallback<?> callback) {
        mHandler.sendMessageDelayed(obtainMessage(
                UserHalService::handleCheckIfRequestTimedOut, this, requestId).setWhat(requestId),
                timeoutMs);
        try {
            if (DBG) Log.d(TAG, "Calling hal.set(): " + request);
            mHal.set(request);
        } catch (ServiceSpecificException e) {
            handleRemovePendingRequest(requestId);
            Log.w(TAG, "Failed to set " + request, e);
            callback.onResponse(HalCallback.STATUS_HAL_SET_TIMEOUT, null);
        }
    }

    /**
     * Calls HAL to asynchronously switch user.
     *
     * @param targetInfo target user for user switching
     * @param timeoutMs how long to wait (in ms) for the property change event.
     * @param usersInfo current state of Android users.
     * @param callback to handle the response.
     *
     * @throws IllegalStateException if the HAL does not support user management (callers should
     * call {@link #isSupported()} first to avoid this exception).
     */
    public void switchUser(@NonNull UserInfo targetInfo, int timeoutMs,
            @NonNull UsersInfo usersInfo, @NonNull HalCallback<SwitchUserResponse> callback) {
        if (DBG) Log.d(TAG, "switchUser(" + targetInfo + ")");
        // TODO(b/150413515): check that targetInfo is not null / add unit test
        Preconditions.checkArgumentPositive(timeoutMs, "timeout must be positive");
        Objects.requireNonNull(usersInfo);
        // TODO(b/150413515): use helper method to check usersInfo is valid
        Objects.requireNonNull(callback);

        VehiclePropValue propRequest;
        int requestId;
        synchronized (mLock) {
            checkSupportedLocked();
            if (hasPendingRequestLocked(SwitchUserResponse.class, callback)) return;
            requestId = getNextRequestId();
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_SWITCH_USER_REQ, requestId,
                    targetInfo.userId, timeoutMs);
            propRequest = getPropRequestForSwitchUserLocked(requestId,
                    SwitchUserMessageType.ANDROID_SWITCH, targetInfo, usersInfo);
            addPendingRequestLocked(requestId, SwitchUserResponse.class, callback);
        }

        sendHalRequest(requestId, timeoutMs, propRequest, callback);
    }

    /**
     * Calls HAL after android user switch.
     *
     * @param requestId for which switch response is sent.
     * @param targetInfo target user info.
     * @param usersInfo current state of Android users.
     */
    public void postSwitchResponse(int requestId, @NonNull UserInfo targetInfo,
            @NonNull UsersInfo usersInfo) {
        EventLog.writeEvent(EventLogTags.CAR_USER_HAL_POST_SWITCH_USER_REQ, requestId,
                targetInfo.userId, usersInfo.currentUser.userId);
        if (DBG) Log.d(TAG, "postSwitchResponse(" + targetInfo + ")");
        Objects.requireNonNull(usersInfo);
        // TODO(b/150413515): use helper method to check usersInfo is valid

        VehiclePropValue propRequest;
        synchronized (mLock) {
            checkSupportedLocked();
            propRequest = getPropRequestForSwitchUserLocked(requestId,
                    SwitchUserMessageType.ANDROID_POST_SWITCH, targetInfo, usersInfo);
        }

        try {
            if (DBG) Log.d(TAG, "Calling hal.set(): " + propRequest);
            mHal.set(propRequest);
        } catch (ServiceSpecificException e) {
            Log.w(TAG, "Failed to set ANDROID POST SWITCH", e);
        }
    }

    /**
     * Calls HAL to switch user after legacy Android user switch. Legacy Android user switch means
     * user switch is not requested by {@link CarUserManager} or OEM, and user switch is directly
     * requested by {@link ActivityManager}
     *
     * @param targetInfo target user info.
     * @param usersInfo current state of Android users.
     */
    public void legacyUserSwitch(@NonNull UserInfo targetInfo, @NonNull UsersInfo usersInfo) {
        if (DBG) Log.d(TAG, "userSwitchLegacy(" + targetInfo + ")");
        Objects.requireNonNull(usersInfo);
        // TODO(b/150413515): use helper method to check usersInfo is valid

        VehiclePropValue propRequest;
        synchronized (mLock) {
            checkSupportedLocked();
            int requestId = getNextRequestId();
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_LEGACY_SWITCH_USER_REQ, requestId,
                    targetInfo.userId, usersInfo.currentUser.userId);
            propRequest = getPropRequestForSwitchUserLocked(requestId,
                    SwitchUserMessageType.LEGACY_ANDROID_SWITCH, targetInfo, usersInfo);
        }

        try {
            if (DBG) Log.d(TAG, "Calling hal.set(): " + propRequest);
            mHal.set(propRequest);
        } catch (ServiceSpecificException e) {
            Log.w(TAG, "Failed to set LEGACY ANDROID SWITCH", e);
        }
    }

    private static VehiclePropValue getPropRequestForSwitchUserLocked(int requestId,
            int requestType, @NonNull UserInfo targetInfo, @NonNull UsersInfo usersInfo) {
        VehiclePropValue propRequest =
                UserHalHelper.createPropRequest(SWITCH_USER, requestId, requestType);
        UserHalHelper.addUserInfo(propRequest, targetInfo);
        UserHalHelper.addUsersInfo(propRequest, usersInfo);
        return propRequest;
    }

    /**
     * Calls HAL to get the value of the user identifications associated with the given user.
     *
     * @return HAL response or {@code null} if it was invalid (for example, mismatch on the
     * requested number of associations).
     *
     * @throws IllegalArgumentException if request is invalid (mismatch on number of associations,
     *   duplicated association, invalid association type values, etc).
     */
    @Nullable
    public UserIdentificationResponse getUserAssociation(
            @NonNull UserIdentificationGetRequest request) {
        Objects.requireNonNull(request, "request cannot be null");

        // Check that it doesn't have dupes
        SparseBooleanArray types = new SparseBooleanArray(request.numberAssociationTypes);
        for (int i = 0; i < request.numberAssociationTypes; i++) {
            int type = request.associationTypes.get(i);
            Preconditions.checkArgument(!types.get(type), "type %s found more than once on %s",
                    UserIdentificationAssociationType.toString(type), request);
            types.put(type, true);
        }

        request.requestId = getNextRequestId();

        if (DBG) Log.d(TAG, "getUserAssociation(): req=" + request);

        VehiclePropValue requestAsPropValue = UserHalHelper.toVehiclePropValue(request);
        EventLog.writeEvent(EventLogTags.CAR_USER_HAL_GET_USER_AUTH_REQ,
                requestAsPropValue.value.int32Values.toArray());

        VehiclePropValue responseAsPropValue = mHal.get(requestAsPropValue);
        if (responseAsPropValue == null) {
            Log.w(TAG, "HAL returned null for request " + requestAsPropValue);
            return null;
        }

        logEventWithErrorMessage(EventLogTags.CAR_USER_HAL_GET_USER_AUTH_RESP, responseAsPropValue);
        if (DBG) Log.d(TAG, "getUserAssociation(): responseAsPropValue=" + responseAsPropValue);

        UserIdentificationResponse response;
        try {
            response = UserHalHelper.toUserIdentificationResponse(responseAsPropValue);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "invalid response from HAL for " + requestAsPropValue, e);
            return null;
        }
        if (DBG) Log.d(TAG, "getUserAssociation(): response=" + response);

        // Validate the response according to the request
        if (response.requestId != request.requestId) {
            Log.w(TAG, "invalid request id (should be " + request.requestId + ") on HAL response: "
                    + response);
            return null;
        }
        if (response.numberAssociation != request.numberAssociationTypes) {
            Log.w(TAG, "Wrong number of association types on HAL response (expected "
                    + request.numberAssociationTypes + ") for request " + requestAsPropValue
                    + ": " + response);
            return null;
        }
        for (int i = 0; i < request.numberAssociationTypes; i++) {
            int expectedType = request.associationTypes.get(i);
            int actualType = response.associations.get(i).type;
            if (actualType != expectedType) {
                Log.w(TAG, "Wrong type on index " + i + " of HAL response (" + response + ") for "
                        + "request " + requestAsPropValue + " : expected "
                        + UserIdentificationAssociationType.toString(expectedType)
                        + ", got " + UserIdentificationAssociationType.toString(actualType));
                return null;
            }
        }

        return response;
    }

    /**
     * Calls HAL to set the value of the user identifications associated with the given user.
     *
     * @throws IllegalArgumentException if request is invalid (mismatch on number of associations,
     *   duplicated association, invalid association type values, etc).
     */
    public void setUserAssociation(int timeoutMs, @NonNull UserIdentificationSetRequest request,
            @NonNull HalCallback<UserIdentificationResponse> callback) {
        if (DBG) Log.d(TAG, "setUserAssociation(" + request + ")");
        Preconditions.checkArgumentPositive(timeoutMs, "timeout must be positive");
        Objects.requireNonNull(request, "request cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");

        // Check that it doesn't have dupes
        SparseBooleanArray types = new SparseBooleanArray(request.numberAssociations);
        for (int i = 0; i < request.numberAssociations; i++) {
            int type = request.associations.get(i).type;
            Preconditions.checkArgument(!types.get(type), "type %s found more than once on %s",
                    UserIdentificationAssociationType.toString(type), request);
            types.put(type, true);
        }

        VehiclePropValue propRequest;
        int requestId;
        synchronized (mLock) {
            checkSupportedLocked();
            if (hasPendingRequestLocked(UserIdentificationResponse.class, callback)) return;
            requestId = request.requestId = getNextRequestId();
            propRequest = UserHalHelper.toVehiclePropValue(request);
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_SET_USER_AUTH_REQ,
                    propRequest.value.int32Values.toArray());

            addPendingRequestLocked(requestId, UserIdentificationSetRequest.class,
                    UserIdentificationResponse.class, request, callback);
        }
        sendHalRequest(requestId, timeoutMs, propRequest, callback);
    }

    private void handleOnUserIdentificationAssociation(@NonNull VehiclePropValue value) {
        logEventWithErrorMessage(EventLogTags.CAR_USER_HAL_SET_USER_AUTH_RESP, value);
        if (DBG) Log.d(TAG, "handleOnUserIdentificationAssociation(): " + value);

        int requestId = value.value.int32Values.get(0);
        HalCallback<UserIdentificationResponse> callback = handleGetPendingCallback(requestId,
                UserIdentificationResponse.class);
        if (callback == null) {
            Log.w(TAG, "no callback for requestId " + requestId + ": " + value);
            return;
        }
        PendingRequest<?, ?> pendingRequest = handleRemovePendingRequest(requestId);
        UserIdentificationResponse response;
        try {
            response = UserHalHelper.toUserIdentificationResponse(value);
        } catch (RuntimeException e) {
            Log.w(TAG, "error parsing UserIdentificationResponse (" + value + ")", e);
            callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
            return;
        }

        // Validate the response according to the request
        UserIdentificationSetRequest request = PendingRequest.getRequest(pendingRequest,
                UserIdentificationSetRequest.class, requestId);

        if (request == null) {
            // already logged on PendingRequest.getRequest
            callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
        }

        if (response.numberAssociation != request.numberAssociations) {
            Log.w(TAG, "Wrong number of association types on HAL response (expected "
                    + request.numberAssociations + ") for request " + request
                    + ": " + response);
            callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
            return;
        }

        for (int i = 0; i < request.numberAssociations; i++) {
            int expectedType = request.associations.get(i).type;
            int actualType = response.associations.get(i).type;
            if (actualType != expectedType) {
                Log.w(TAG, "Wrong type on index " + i + " of HAL response (" + response + ") for "
                        + "request " + value + " : expected "
                        + UserIdentificationAssociationType.toString(expectedType)
                        + ", got " + UserIdentificationAssociationType.toString(actualType));
                callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
                return;
            }
        }


        if (DBG) Log.d(TAG, "replying to request " + requestId + " with " + response);
        callback.onResponse(HalCallback.STATUS_OK, response);
    }

    private static void logEventWithErrorMessage(int eventTag, @NonNull VehiclePropValue value) {
        if (TextUtils.isEmpty(value.value.stringValue)) {
            EventLog.writeEvent(eventTag, value.value.int32Values.toArray());
        } else {
            // Must manually append the error message to the array of values
            int size = value.value.int32Values.size();
            Object[] list = new Object[size + 1];
            value.value.int32Values.toArray(list);
            list[list.length - 1] = value.value.stringValue;
            EventLog.writeEvent(eventTag, list);
        }
    }

    @VisibleForTesting
    int getNextRequestId() {
        synchronized (mLock) {
            return ++mNextRequestId;
        }
    }

    @GuardedBy("mLock")
    private <REQ, RESP> void addPendingRequestLocked(int requestId,
            @NonNull Class<REQ> requestClass, @NonNull Class<RESP> responseClass,
            @NonNull REQ request, @NonNull HalCallback<RESP> callback) {
        PendingRequest<?, RESP> pendingRequest = new PendingRequest<>(responseClass, request,
                callback);
        if (DBG) {
            Log.d(TAG, "adding pending request (" + pendingRequest + ") for requestId "
                    + requestId);
        }
        mPendingRequests.put(requestId, pendingRequest);
    }

    @GuardedBy("mLock")
    private <RESP> void addPendingRequestLocked(int requestId, @NonNull Class<RESP> responseClass,
            @NonNull HalCallback<RESP> callback) {
        addPendingRequestLocked(requestId, Void.class, responseClass, /* request= */ null,
                callback);
    }

    /**
     * Checks if there is a pending request of type {@code requestClass}, calling {@code callback}
     * with {@link HalCallback#STATUS_CONCURRENT_OPERATION} when there is.
     */
    @GuardedBy("mLock")
    private boolean hasPendingRequestLocked(@NonNull Class<?> responseClass,
            @NonNull HalCallback<?> callback) {
        for (int i = 0; i < mPendingRequests.size(); i++) {
            PendingRequest<?, ?> pendingRequest = mPendingRequests.valueAt(i);
            if (pendingRequest.responseClass == responseClass) {
                Log.w(TAG, "Already have pending request of type " + responseClass);
                callback.onResponse(HalCallback.STATUS_CONCURRENT_OPERATION, null);
                return true;
            }
        }
        return false;
    }

    /**
     * Removes the pending request and its timeout callback.
     */
    @Nullable
    private PendingRequest<?, ?> handleRemovePendingRequest(int requestId) {
        if (DBG) Log.d(TAG, "Removing pending request #" + requestId);
        mHandler.removeMessages(requestId);
        PendingRequest<?, ?> pendingRequest;
        synchronized (mLock) {
            pendingRequest = mPendingRequests.get(requestId);
            mPendingRequests.remove(requestId);
        }
        return pendingRequest;
    }

    private void handleCheckIfRequestTimedOut(int requestId) {
        PendingRequest<?, ?> pendingRequest = getPendingRequest(requestId);
        if (pendingRequest == null) return;

        Log.w(TAG, "Request #" + requestId + " timed out");
        handleRemovePendingRequest(requestId);
        pendingRequest.callback.onResponse(HalCallback.STATUS_HAL_RESPONSE_TIMEOUT, null);
    }

    @Nullable
    private PendingRequest<?, ?> getPendingRequest(int requestId) {
        synchronized (mLock) {
            return mPendingRequests.get(requestId);
        }
    }

    private void handleOnInitialUserInfoResponse(VehiclePropValue value) {
        // TODO(b/150413515): record (for dumping()) the last N responses.
        int requestId = value.value.int32Values.get(0);
        HalCallback<InitialUserInfoResponse> callback = handleGetPendingCallback(requestId,
                InitialUserInfoResponse.class);
        if (callback == null) {
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_INITIAL_USER_INFO_RESP, requestId,
                    HalCallback.STATUS_INVALID);
            Log.w(TAG, "no callback for requestId " + requestId + ": " + value);
            return;
        }
        handleRemovePendingRequest(requestId);

        InitialUserInfoResponse response;
        try {
            response = UserHalHelper.toInitialUserInfoResponse(value);
        } catch (RuntimeException e) {
            Log.e(TAG, "invalid response (" + value + ") from HAL", e);
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_INITIAL_USER_INFO_RESP, requestId,
                    HalCallback.STATUS_WRONG_HAL_RESPONSE);
            callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
            return;
        }
        EventLog.writeEvent(EventLogTags.CAR_USER_HAL_INITIAL_USER_INFO_RESP, requestId,
                HalCallback.STATUS_OK, response.action,
                response.userToSwitchOrCreate.userId, response.userToSwitchOrCreate.flags,
                response.userNameToCreate);
        if (DBG) Log.d(TAG, "replying to request " + requestId + " with " + response);
        callback.onResponse(HalCallback.STATUS_OK, response);
    }

    private void handleOnSwitchUserResponse(VehiclePropValue value) {
        int requestId = value.value.int32Values.get(0);
        int messageType = value.value.int32Values.get(1);

        if (messageType == SwitchUserMessageType.VEHICLE_RESPONSE) {
            handleVehicleResponse(value);
            return;
        }

        if (messageType == SwitchUserMessageType.VEHICLE_REQUEST) {
            handleVehicleRequest(value);
            return;
        }

        Log.e(TAG, "handleOnSwitchUserResponse invalid message type (" + messageType
                + ") from HAL: " + value);

        // check if a callback exists for the request ID
        HalCallback<SwitchUserResponse> callback =
                handleGetPendingCallback(requestId, SwitchUserResponse.class);
        if (callback != null) {
            handleRemovePendingRequest(requestId);
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_SWITCH_USER_RESP, requestId,
                    HalCallback.STATUS_WRONG_HAL_RESPONSE, SwitchUserStatus.FAILURE);
            callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
            return;
        }
    }

    private void handleVehicleRequest(VehiclePropValue value) {
        int requestId = value.value.int32Values.get(0);
        // Index 1 is message type, which is not required in this call.
        int targetUserId = value.value.int32Values.get(2);
        EventLog.writeEvent(EventLogTags.CAR_USER_HAL_OEM_SWITCH_USER_REQ, requestId, targetUserId);

        // HAL vehicle request should have negative request ID
        if (requestId >= 0) {
            Log.e(TAG, "handleVehicleRequest invalid requestId (" + requestId + ") from HAL: "
                    + value);
            return;
        }

        CarUserService userService = CarLocalServices.getService(CarUserService.class);
        userService.switchAndroidUserFromHal(requestId, targetUserId);
    }

    private void handleVehicleResponse(VehiclePropValue value) {
        int requestId = value.value.int32Values.get(0);
        HalCallback<SwitchUserResponse> callback =
                handleGetPendingCallback(requestId, SwitchUserResponse.class);
        if (callback == null) {
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_SWITCH_USER_RESP, requestId,
                    HalCallback.STATUS_INVALID);
            Log.w(TAG, "no callback for requestId " + requestId + ": " + value);
            return;
        }
        handleRemovePendingRequest(requestId);
        SwitchUserResponse response = new SwitchUserResponse();
        response.requestId = requestId;
        response.messageType = value.value.int32Values.get(1);
        response.status = value.value.int32Values.get(2);
        if (response.status == SwitchUserStatus.SUCCESS
                || response.status == SwitchUserStatus.FAILURE) {
            if (DBG) {
                Log.d(TAG, "replying to request " + requestId + " with " + response);
            }
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_SWITCH_USER_RESP, requestId,
                    HalCallback.STATUS_OK, response.status);
            callback.onResponse(HalCallback.STATUS_OK, response);
        } else {
            EventLog.writeEvent(EventLogTags.CAR_USER_HAL_SWITCH_USER_RESP, requestId,
                    HalCallback.STATUS_WRONG_HAL_RESPONSE, response.status);
            Log.e(TAG, "invalid status (" + response.status + ") from HAL: " + value);
            callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
        }
    }

    private <T> HalCallback<T> handleGetPendingCallback(int requestId, Class<T> clazz) {
        PendingRequest<?, ?> pendingRequest = getPendingRequest(requestId);
        if (pendingRequest == null) return null;

        if (pendingRequest.responseClass != clazz) {
            Log.e(TAG, "Invalid callback class for request " + requestId + ": expected" + clazz
                    + ", but got is " + pendingRequest.responseClass);
            // TODO(b/150413515): add unit test for this scenario once it supports other properties
            return null;
        }
        @SuppressWarnings("unchecked")
        HalCallback<T> callback = (HalCallback<T>) pendingRequest.callback;
        return callback;
    }

    @Override
    public void dump(PrintWriter writer) {
        String indent = "  ";
        writer.printf("*User HAL*\n");

        writer.printf("Relevant CarProperties\n");
        dumpSystemProperty(writer, indent, "user_hal_enabled", CarProperties.user_hal_enabled());
        dumpSystemProperty(writer, indent, "user_hal_timeout", CarProperties.user_hal_timeout());

        synchronized (mLock) {
            if (!isSupported()) {
                writer.println(UNSUPPORTED_MSG);
                return;
            }
            int numberProperties = mProperties.size();
            writer.printf("%d supported properties\n", numberProperties);
            for (int i = 0; i < numberProperties; i++) {
                writer.printf("%s%s\n", indent, mProperties.valueAt(i));
            }
            writer.printf("next request id: %d\n", mNextRequestId);

            int numberPendingCallbacks = mPendingRequests.size();
            if (numberPendingCallbacks == 0) {
                writer.println("no pending callbacks");
            } else {
                writer.printf("%d pending callbacks: %s\n", numberPendingCallbacks);
                for (int i = 0; i < numberPendingCallbacks; i++) {
                    writer.print(indent);
                    mPendingRequests.valueAt(i).dump(writer);
                    writer.println();
                }
            }
        }
    }

    private static void dumpSystemProperty(@NonNull PrintWriter writer, @NonNull String indent,
            @NonNull String name, Optional<?> prop) {
        String value = prop.isPresent() ? prop.get().toString() : "<NOT SET>";
        writer.printf("%s%s=%s\n", indent, name, value);
    }

    private static final class PendingRequest<REQ, RESP> {
        @NonNull
        public final Class<RESP> responseClass;

        @Nullable
        public final REQ request;

        @NonNull
        public final HalCallback<RESP> callback;

        PendingRequest(@NonNull Class<RESP> responseClass, @Nullable REQ request,
                @NonNull HalCallback<RESP> callback) {
            this.responseClass = responseClass;
            this.request = request;
            this.callback = callback;
        }

        /**
         * Gets the safely cast request for a given pending request.
         */
        @Nullable
        private static <T> T getRequest(@Nullable PendingRequest<?, ?> pendingRequest,
                @NonNull Class<T> clazz, int requestId) {
            if (pendingRequest == null) {
                Log.e(TAG, "No pending request for id " + requestId);
                return null;

            }
            Object request = pendingRequest.request;
            if (!clazz.isInstance(request)) {
                Log.e(TAG, "Wrong pending request for id " + requestId + ": " + pendingRequest);
                return null;
            }
            return clazz.cast(request);
        }

        public void dump(@NonNull PrintWriter pw) {
            pw.printf("Class: %s Callback: %s", responseClass.getSimpleName(),
                    FunctionalUtils.getLambdaName(callback));
            if (request != null) {
                pw.printf(" Request: %s", request);
            }
        }

        @Override
        public String toString() {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            pw.print("[PendingRequest: ");
            dump(pw);
            pw.print("]");
            pw.flush();
            return sw.toString();
        }

    }

}
