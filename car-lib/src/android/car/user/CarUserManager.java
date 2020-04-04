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

package android.car.user;

import static android.Manifest.permission.INTERACT_ACROSS_USERS;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.os.Process.myUid;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.annotation.UserIdInt;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.ICarUserService;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * API to manage users related to car.
 *
 * @hide
 */
@SystemApi
@TestApi
public final class CarUserManager extends CarManagerBase {

    private static final String TAG = CarUserManager.class.getSimpleName();

    // TODO(b/144120654): STOPSHIP - set to false
    private static final boolean DBG = true;

    /**
     * {@link UserLifecycleEvent} called when the user is starting.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_STARTING = 1;

    /**
     * {@link UserLifecycleEvent} called when the user is switching.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_SWITCHING = 2;

    /**
     * {@link UserLifecycleEvent} called whe the user is unlocking.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKING = 3;

    /**
     * {@link UserLifecycleEvent} called after the user was unlocked.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_UNLOCKED = 4;

    /**
     * {@link UserLifecycleEvent} called when the user is stopping.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_STOPPING = 5;

    /**
     * {@link UserLifecycleEvent} called after the user stoppped.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int USER_LIFECYCLE_EVENT_TYPE_STOPPED = 6;

    /** @hide */
    @IntDef(prefix = { "USER_LIFECYCLE_EVENT_TYPE_" }, value = {
            USER_LIFECYCLE_EVENT_TYPE_STARTING,
            USER_LIFECYCLE_EVENT_TYPE_SWITCHING,
            USER_LIFECYCLE_EVENT_TYPE_UNLOCKING,
            USER_LIFECYCLE_EVENT_TYPE_UNLOCKED,
            USER_LIFECYCLE_EVENT_TYPE_STOPPING,
            USER_LIFECYCLE_EVENT_TYPE_STOPPED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserLifecycleEventType{}

    /** @hide */
    public static final String BUNDLE_PARAM_ACTION = "action";
    /** @hide */
    public static final String BUNDLE_PARAM_PREVIOUS_USER_ID = "previous_user";

    /**
     * {@code int} extra used to represent the user switch status {@link IResultReceiver}
     * response.
     *
     * @hide
     */
    public static final String BUNDLE_USER_SWITCH_STATUS = "user_switch.status";
    /**
     * {@code int} extra used to represent the user switch message type {@link IResultReceiver}
     * response.
     *
     * @hide
     */
    public static final String BUNDLE_USER_SWITCH_MSG_TYPE = "user_switch.messageType";
    /**
     * {@code string} extra used to represent the user switch error {@link IResultReceiver}
     * response.
     *
     * @hide
     */
    public static final String BUNDLE_USER_SWITCH_ERROR_MSG = "user_switch.errorMessage";

    /**
     * {@link UserSwitchStatus} called user switch status is unknown.
     *
     * @hide
     */
    public static final int USER_SWICTH_STATUS_UNKNOWN = 0;
    /**
     * {@link UserSwitchStatus} called when user switch is successful for both HAL and Android.
     *
     * @hide
     */
    public static final int USER_SWICTH_STATUS_SUCCESSFUL = 1;
    /**
     * {@link UserSwitchStatus} called when user switch is only successful for Hal but not for
     * Android. Hal user switch rollover message have been sent.
     *
     * @hide
     */
    public static final int USER_SWICTH_STATUS_ANDROID_FAILURE = 2;
    /**
     * {@link UserSwitchStatus} called when user switch is failed for HAL.
     * Andrid user switch is not called.
     *
     * @hide
     */
    public static final int USER_SWICTH_STATUS_HAL_FAILURE = 3;

    /** @hide */
    @IntDef(prefix = { "USER_SWICTH_STATUS_" }, value = {
            USER_SWICTH_STATUS_UNKNOWN,
            USER_SWICTH_STATUS_SUCCESSFUL,
            USER_SWICTH_STATUS_ANDROID_FAILURE,
            USER_SWICTH_STATUS_HAL_FAILURE,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface UserSwitchStatus{}

    private final Object mLock = new Object();
    private final ICarUserService mService;
    private final UserManager mUserManager;

    @Nullable
    @GuardedBy("mLock")
    private ArrayMap<UserLifecycleListener, Executor> mListeners;

    @Nullable
    @GuardedBy("mLock")
    private LifecycleResultReceiver mReceiver;

    /**
     * @hide
     */
    public CarUserManager(@NonNull Car car, @NonNull IBinder service) {
        this(car, ICarUserService.Stub.asInterface(service), UserManager.get(car.getContext()));
    }

    /**
     * @hide
     */
    @VisibleForTesting
    public CarUserManager(@NonNull Car car, @NonNull ICarUserService service,
            @NonNull UserManager userManager) {
        super(car);
        mService = service;
        mUserManager = userManager;
    }
    /**
     * Adds a listener for {@link UserLifecycleEvent user lifecycle events}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {INTERACT_ACROSS_USERS, INTERACT_ACROSS_USERS_FULL})
    public void addListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull UserLifecycleListener listener) {
        checkInteractAcrossUsersPermission();

        // TODO(b/144120654): add unit tests to validate input
        // - executor cannot be null
        // - listener cannot be null
        // - listener must not be added before

        synchronized (mLock) {
            if (mReceiver == null) {
                mReceiver = new LifecycleResultReceiver();
                try {
                    Log.i(TAG, "Setting lifecycle receiver for uid " + myUid());
                    mService.setLifecycleListenerForUid(mReceiver);
                } catch (RemoteException e) {
                    handleRemoteExceptionFromCarService(e);
                }
            } else {
                if (DBG) Log.d(TAG, "Already set receiver for uid " + myUid());
            }

            if (mListeners == null) {
                mListeners = new ArrayMap<>(1); // Most likely app will have just one listener
            }
            if (DBG) Log.d(TAG, "Adding listener: " + listener);
            mListeners.put(listener, executor);
        }
    }

    /**
     * Removes a listener for {@link UserLifecycleEvent user lifecycle events}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @RequiresPermission(anyOf = {INTERACT_ACROSS_USERS, INTERACT_ACROSS_USERS_FULL})
    public void removeListener(@NonNull UserLifecycleListener listener) {
        checkInteractAcrossUsersPermission();

        // TODO(b/144120654): add unit tests to validate input
        // - listener cannot be null
        // - listener must not be added before
        synchronized (mLock) {
            if (mListeners == null) {
                Log.w(TAG, "removeListener(): no listeners for uid " + myUid());
                return;
            }

            mListeners.remove(listener);

            if (!mListeners.isEmpty()) {
                if (DBG) Log.d(TAG, "removeListeners(): still " + mListeners.size() + " left");
                return;
            }
            mListeners = null;

            if (mReceiver == null) {
                Log.wtf(TAG, "removeListener(): receiver already null");
                return;
            }

            Log.i(TAG, "Removing lifecycle receiver for uid=" + myUid());
            try {
                mService.resetLifecycleListenerForUid();
                mReceiver = null;
            } catch (RemoteException e) {
                handleRemoteExceptionFromCarService(e);
            }
        }
    }

    /** @hide */
    @TestApi
    // TODO(b/144120654): temp method used by CTS; will eventually be refactored to take a listener
    @UserIdInt
    public int createUser(@Nullable String name, boolean isGuestUser) {
        Log.i(TAG, "createUser()"); // name is PII

        if (isGuestUser) {
            return mUserManager.createUser(name, UserManager.USER_TYPE_FULL_GUEST, /* flags= */ 0)
                    .id;
        }

        return mUserManager.createUser(name, /* flags= */ 0).id;
    }

    /** @hide */
    @TestApi
    // TODO(b/144120654): temp method used by CTS; will eventually be refactored to take a listener
    public void removeUser(@UserIdInt int userId) {
        Log.i(TAG, "removeUser(" + userId + ")");
        mUserManager.removeUser(userId);
    }

    /**
     * {@code IResultReceiver} used to receive lifecycle events and dispatch to the proper listener.
     */
    private class LifecycleResultReceiver extends IResultReceiver.Stub {
        @Override
        public void send(int resultCode, Bundle resultData) {
            if (resultData == null) {
                Log.w(TAG, "Received result (" + resultCode + ") without data");
                return;
            }
            int from = resultData.getInt(BUNDLE_PARAM_PREVIOUS_USER_ID, UserHandle.USER_NULL);
            int to = resultCode;
            int eventType = resultData.getInt(BUNDLE_PARAM_ACTION);
            UserLifecycleEvent event = new UserLifecycleEvent(eventType, from, to);
            ArrayMap<UserLifecycleListener, Executor> listeners;
            synchronized (mLock) {
                listeners = mListeners;
            }
            if (listeners == null) {
                Log.w(TAG, "No listeners for event " + event);
                return;
            }
            for (int i = 0; i < listeners.size(); i++) {
                UserLifecycleListener listener = listeners.keyAt(i);
                Executor executor = listeners.valueAt(i);
                if (DBG) Log.d(TAG, "Calling listener " + listener + " for event " + event);
                executor.execute(() -> listener.onEvent(event));
            }
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    /**
     * @hide
     */
    @TestApi
    public static String lifecycleEventTypeToString(@UserLifecycleEventType int type) {
        switch (type) {
            case USER_LIFECYCLE_EVENT_TYPE_STARTING:
                return "STARTING";
            case USER_LIFECYCLE_EVENT_TYPE_SWITCHING:
                return "SWITCHING";
            case USER_LIFECYCLE_EVENT_TYPE_UNLOCKING:
                return "UNLOCKING";
            case USER_LIFECYCLE_EVENT_TYPE_UNLOCKED:
                return "UNLOCKED";
            case USER_LIFECYCLE_EVENT_TYPE_STOPPING:
                return "STOPPING";
            case USER_LIFECYCLE_EVENT_TYPE_STOPPED:
                return "STOPPED";
            default:
                return "UNKNOWN-" + type;
        }
    }

    private void checkInteractAcrossUsersPermission() {
        checkInteractAcrossUsersPermission(getContext());
    }

    private static void checkInteractAcrossUsersPermission(Context context) {
        if (context.checkSelfPermission(INTERACT_ACROSS_USERS) != PERMISSION_GRANTED
                && context.checkSelfPermission(INTERACT_ACROSS_USERS_FULL) != PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Must have either " + android.Manifest.permission.INTERACT_ACROSS_USERS + " or "
                            + android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
                            + " permission");
        }
    }

    // NOTE: this method is called by ExperimentalCarUserManager, so it can get the mService.
    // "Real" ExperimentalCarUserManager instances should be obtained through
    //    ExperimentalCarUserManager.from(mCarUserManager)
    // instead.
    ExperimentalCarUserManager newExperimentalCarUserManager() {
        return new ExperimentalCarUserManager(mCar, mService);
    }

    /**
     * Checks if the given {@code userId} represents a valid user.
     *
     * <p>A "valid" user:
     *
     * <ul>
     *   <li>Must exist in the device.
     *   <li>Is not in the process of being deleted.
     *   <li>Cannot be the {@link UserHandle#isSystem() system} user on devices that use
     *   {@link UserManager#isHeadlessSystemUserMode() headless system mode}.
     * </ul>
     *
     * @hide
     */
    public boolean isValidUser(@UserIdInt int userId) {
        List<UserInfo> allUsers = mUserManager.getUsers();
        for (int i = 0; i < allUsers.size(); i++) {
            UserInfo user = allUsers.get(i);
            if (user.id == userId && (userId != UserHandle.USER_SYSTEM
                    || !UserManager.isHeadlessSystemUserMode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Defines a lifecycle event for an Android user.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final class UserLifecycleEvent {
        private final @UserLifecycleEventType int mEventType;
        private final @UserIdInt int mUserId;
        private final @UserIdInt int mPreviousUserId;

        /** @hide */
        public UserLifecycleEvent(@UserLifecycleEventType int eventType,
                @UserIdInt int from, @UserIdInt int to) {
            mEventType = eventType;
            mPreviousUserId = from;
            mUserId = to;
        }

        /** @hide */
        public UserLifecycleEvent(@UserLifecycleEventType int eventType, @UserIdInt int to) {
            this(eventType, UserHandle.USER_NULL, to);
        }

        /**
         * Gets the event type.
         *
         * @return either {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_STARTING},
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_SWITCHING},
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_UNLOCKING},
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_UNLOCKED},
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_STOPPING}, or
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_STOPPED}.
         */
        @UserLifecycleEventType
        public int getEventType() {
            return mEventType;
        }

        /**
         * Gets the id of the user whose event is being reported.
         *
         * @hide
         */
        @UserIdInt
        public int getUserId() {
            return mUserId;
        }

        /**
         * Gets the handle of the user whose event is being reported.
         */
        @NonNull
        public UserHandle getUserHandle() {
            return UserHandle.of(mUserId);
        }

        /**
         * Gets the id of the user being switched from.
         *
         * <p>This method returns {@link UserHandle#USER_NULL} for all event types but
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_SWITCHING}.
         *
         * @hide
         */
        @UserIdInt
        public int getPreviousUserId() {
            return mPreviousUserId;
        }

        /**
         * Gets the handle of the user being switched from.
         *
         * <p>This method returns {@code null} for all event types but
         * {@link CarUserManager#USER_LIFECYCLE_EVENT_TYPE_SWITCHING}.
         */
        @Nullable
        public UserHandle getPreviousUserHandle() {
            return mPreviousUserId == UserHandle.USER_NULL ? null : UserHandle.of(mPreviousUserId);
        }

        @Override
        public String toString() {
            StringBuilder builder = new StringBuilder("Event[type=")
                    .append(lifecycleEventTypeToString(mEventType));
            if (mPreviousUserId != UserHandle.USER_NULL) {
                builder
                    .append(",from=").append(mPreviousUserId)
                    .append(",to=").append(mUserId);
            } else {
                builder.append(",user=").append(mUserId);
            }

            return builder.append(']').toString();
        }
    }

    /**
     * Listener for Android User lifecycle events.
     *
     * <p>Must be registered using {@link CarUserManager#addListener(UserLifecycleListener)} and
     * unregistered through {@link CarUserManager#removeListener(UserLifecycleListener)}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public interface UserLifecycleListener {

        /**
         * Called to notify the given {@code event}.
         */
        void onEvent(@NonNull UserLifecycleEvent event);
    }
}
