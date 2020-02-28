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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.hardware.property.CarPropertyManager;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponse;
import android.hardware.automotive.vehicle.V2_0.InitialUserInfoResponseAction;
import android.hardware.automotive.vehicle.V2_0.UserFlags;
import android.hardware.automotive.vehicle.V2_0.UserInfo;
import android.hardware.automotive.vehicle.V2_0.UsersInfo;
import android.hardware.automotive.vehicle.V2_0.VehiclePropConfig;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Service used to integrate the OEM's custom user management with Android's.
 */
public final class UserHalService extends HalServiceBase {

    private static final String UNSUPPORTED_MSG = "Vehicle HAL does not support user management";

    private static final String TAG = CarLog.TAG_USER;

    // TODO(b/146207078): STOPSHIP - change to false before R is launched
    private static final boolean DBG = true;

    private final Object mLock = new Object();

    private final VehicleHal mHal;

    @GuardedBy("mLock")
    @Nullable
    private SparseArray<VehiclePropConfig> mProperties;

    // This handler handles 2 types of messages:
    // - "Anonymous" messages (what=0) containing runnables.
    // - "Identifiable" messages used to check for timeouts (whose 'what' is the request id).
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    /**
     * Value used on the next request.
     */
    @GuardedBy("mLock")
    private int mNextRequestId = 1;

    /**
     * Map of callback by request id.
     */
    @GuardedBy("mHandler")
    private SparseArray<Pair<Class<?>, HalCallback<?>>> mPendingCallbacks = new SparseArray<>();

    public UserHalService(VehicleHal hal) {
        mHal = hal;
    }

    @Override
    public void init() {
        if (DBG) Log.d(TAG, "init()");

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
                default:
                    Slog.w(TAG, "received unsupported event from HAL: " + value);
            }
        }
    }

    @Override
    public void onPropertySetError(int property, int area,
            @CarPropertyManager.CarSetPropertyErrorCode int errorCode) {
        if (DBG)Log.d(TAG, "handlePropertySetError(" + property + "/" + area + ")");
    }

    @Override
    @Nullable
    public Collection<VehiclePropConfig> takeSupportedProperties(
            Collection<VehiclePropConfig> allProperties) {
        boolean supported = false;
        // TODO(b/146207078): increase capacity once it supports more
        SparseArray<VehiclePropConfig> properties = new SparseArray<>(1);
        ArrayList<VehiclePropConfig> taken = new ArrayList<>();
        for (VehiclePropConfig config : allProperties) {
            switch (config.prop) {
                case INITIAL_USER_INFO:
                    supported = true;
                    taken.add(config);
                    properties.put(config.prop, config);
                    break;
            }

        }
        if (!supported) {
            Log.w(TAG, UNSUPPORTED_MSG);
            return null;
        }
        synchronized (mLock) {
            mProperties = properties;
        }
        return taken;
    }

    /**
     * Callback used on async methods.
     *
     * @param <R> response type.
     */
    public interface HalCallback<R> {

        int STATUS_OK = 1;
        int STATUS_HAL_SET_TIMEOUT = 2;
        int STATUS_HAL_RESPONSE_TIMEOUT = 3;
        int STATUS_WRONG_HAL_RESPONSE = 4;

        /** @hide */
        @IntDef(prefix = { "STATUS_" }, value = {
                STATUS_OK,
                STATUS_HAL_SET_TIMEOUT,
                STATUS_HAL_RESPONSE_TIMEOUT,
                STATUS_WRONG_HAL_RESPONSE
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface HalCallbackStatus{}

        /**
         * Called when the HAL generated an event responding to that callback (or when an error
         * occurred).
         *
         * @param status status of the request.
         * @param response HAL response (or {@code null} in case of error).
         */
        void onResponse(@HalCallbackStatus int status, @Nullable R response);
    }

    /**
     * Checks if the Vehicle HAL supports user management.
     */
    public boolean isSupported() {
        synchronized (mLock) {
            return mProperties != null;
        }
    }

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
     * @param callback callback to handle the response.
     *
     * @throws IllegalStateException if the HAL does not support user management (callers should
     * call {@link #isSupported()} first to avoid this exception).
     */
    public void getInitialUserInfo(int requestType, int timeoutMs, @NonNull UsersInfo usersInfo,
            @NonNull HalCallback<InitialUserInfoResponse> callback) {
        Preconditions.checkArgumentPositive(timeoutMs, "timeout must be positive");
        Objects.requireNonNull(usersInfo);
        // TODO(b/146207078): use helper method to convert request to prop value and check usersInfo
        // is valid
        Objects.requireNonNull(callback);

        VehiclePropValue propRequest = new VehiclePropValue();
        propRequest.prop = INITIAL_USER_INFO;
        int requestId;
        synchronized (mLock) {
            checkSupportedLocked();
            requestId = mNextRequestId++;
            // TODO(b/146207078): use helper method to convert request to prop value
            propRequest.value.int32Values.add(requestId);
            propRequest.value.int32Values.add(requestType);
            propRequest.value.int32Values.add(usersInfo.currentUser.userId);
            propRequest.value.int32Values.add(usersInfo.currentUser.flags);
            propRequest.value.int32Values.add(usersInfo.numberUsers);
            for (int i = 0; i < usersInfo.numberUsers; i++) {
                UserInfo userInfo = usersInfo.existingUsers.get(i);
                propRequest.value.int32Values.add(userInfo.userId);
                propRequest.value.int32Values.add(userInfo.flags);
            }
            setTimestamp(propRequest);
            if (DBG) Log.d(TAG, "adding pending callback for request " + requestId);
            mPendingCallbacks.put(requestId, new Pair<>(InitialUserInfoResponse.class, callback));
        }

        mHandler.sendMessageDelayed(obtainMessage(
                UserHalService::handleCheckIfRequestTimedOut, this, requestId).setWhat(requestId),
                timeoutMs);
        try {
            if (DBG) Log.d(TAG, "Calling hal.set(): " + propRequest);
            mHal.set(propRequest);
        } catch (PropertyTimeoutException e) {
            Log.w(TAG, "Failed to set INITIAL_USER_INFO", e);
            callback.onResponse(HalCallback.STATUS_HAL_SET_TIMEOUT, null);
        }
    }

    /**
     * Removes the pending request and its timeout callback.
     */
    private void handleRemovePendingRequest(int requestId) {
        if (DBG) Log.d(TAG, "Removing pending request #" + requestId);
        mHandler.removeMessages(requestId);
        mPendingCallbacks.remove(requestId);
    }

    private void handleCheckIfRequestTimedOut(int requestId) {
        Pair<Class<?>, HalCallback<?>> pair = mPendingCallbacks.get(requestId);
        if (pair == null) return;

        Log.w(TAG, "Request #" + requestId + " timed out");
        handleRemovePendingRequest(requestId);
        pair.second.onResponse(HalCallback.STATUS_HAL_RESPONSE_TIMEOUT, null);
    }

    @GuardedBy("mHandle")
    private void handleOnInitialUserInfoResponse(VehiclePropValue value) {
        // TODO(b/146207078): record (for dumping()) the last N responses.
        int requestId = value.value.int32Values.get(0);
        HalCallback<InitialUserInfoResponse> callback = handleGetPendingCallback(requestId,
                InitialUserInfoResponse.class);
        if (callback == null) {
            Log.w(TAG, "no callback for requestId " + requestId + ": " + value);
            return;
        }
        handleRemovePendingRequest(requestId);
        InitialUserInfoResponse response = new InitialUserInfoResponse();
        // TODO(b/146207078): use helper method to convert prop value to proper response
        response.requestId = requestId;
        response.action = value.value.int32Values.get(1);
        switch (response.action) {
            case InitialUserInfoResponseAction.DEFAULT:
                response.userToSwitchOrCreate.userId = UserHandle.USER_NULL;
                response.userToSwitchOrCreate.flags = UserFlags.NONE;
                break;
            case InitialUserInfoResponseAction.SWITCH:
                response.userToSwitchOrCreate.userId = value.value.int32Values.get(2);
                response.userToSwitchOrCreate.flags = UserFlags.NONE;
                break;
            case InitialUserInfoResponseAction.CREATE:
                response.userToSwitchOrCreate.userId = UserHandle.USER_NULL;
                response.userToSwitchOrCreate.flags = value.value.int32Values.get(2);
                response.userNameToCreate = value.value.stringValue;
                break;
            default:
                Log.e(TAG, "invalid action (" + response.action + ") from HAL: " + value);
                callback.onResponse(HalCallback.STATUS_WRONG_HAL_RESPONSE, null);
                return;
        }

        if (DBG) Log.d(TAG, "replying to request " + requestId + " with " + response);
        callback.onResponse(HalCallback.STATUS_OK, response);
    }

    @GuardedBy("mHandle")
    private <T> HalCallback<T> handleGetPendingCallback(int requestId, Class<T> clazz) {
        Pair<Class<?>, HalCallback<?>> pair = mPendingCallbacks.get(requestId);
        if (pair == null) return null;

        if (pair.first != clazz) {
            Slog.e(TAG, "Invalid callback class for request " + requestId + ": expected" + clazz
                    + ", but got is " + pair.first);
            // TODO(b/146207078): add unit test for this scenario once it supports other properties
            return null;
        }
        @SuppressWarnings("unchecked")
        HalCallback<T> callback = (HalCallback<T>) pair.second;
        return callback;
    }

    private void setTimestamp(VehiclePropValue propRequest) {
        propRequest.timestamp = SystemClock.elapsedRealtime();
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.printf("*User HAL*\n");
        synchronized (mLock) {
            if (!isSupported()) {
                writer.println(UNSUPPORTED_MSG);
                return;
            }
            int numberProperties = mProperties.size();
            String indent = "  ";
            writer.printf("%d supported properties\n", numberProperties);
            for (int i = 0; i < numberProperties; i++) {
                writer.printf("%s%s\n", indent, mProperties.valueAt(i));
            }
            writer.printf("next request id: %d\n", mNextRequestId);
            if (mPendingCallbacks.size() == 0) {
                writer.println("no pending callbacks");
            } else {
                writer.printf("pending callbacks: %s\n", mPendingCallbacks);
            }
        }
    }
}
