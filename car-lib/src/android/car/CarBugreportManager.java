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

package android.car;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import libcore.io.IoUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;

/**
 * Car specific bugreport manager. Only available for userdebug and eng builds.
 *
 * @hide
 */
public final class CarBugreportManager implements CarManagerBase {

    private final ICarBugreportService mService;
    private Handler mHandler;

    /**
     * Callback from carbugreport manager. Callback methods are always called on the main thread.
     */
    public abstract static class CarBugreportManagerCallback {

        @Retention(RetentionPolicy.SOURCE)
        @IntDef(prefix = {"CAR_BUGREPORT_ERROR_"}, value = {
                CAR_BUGREPORT_DUMPSTATE_FAILED,
                CAR_BUGREPORT_IN_PROGRESS,
                CAR_BUGREPORT_DUMPSTATE_CONNECTION_FAILED,
                CAR_BUGREPORT_SERVICE_NOT_AVAILABLE
        })

        public @interface CarBugreportErrorCode {
        }

        /** Dumpstate failed to generate bugreport. */
        public static final int CAR_BUGREPORT_DUMPSTATE_FAILED = 1;

        /**
         * Another bugreport is in progress.
         */
        public static final int CAR_BUGREPORT_IN_PROGRESS = 2;

        /** Cannot connect to dumpstate */
        public static final int CAR_BUGREPORT_DUMPSTATE_CONNECTION_FAILED = 3;

        /** Car bugreport service is not available (true for user builds) */
        public static final int CAR_BUGREPORT_SERVICE_NOT_AVAILABLE = 4;

        /**
         * Called on an error condition with one of the error codes listed above.
         *
         * @param errorCode the error code that defines failure reason.
         */
        public void onError(@CarBugreportErrorCode int errorCode) {
        }

        /**
         * Called when taking bugreport finishes successfully.
         */
        public void onFinished() {
        }
    }

    /**
     * Internal wrapper class to service.
     */
    private static final class CarBugreportManagerCallbackWrapper extends
            ICarBugreportCallback.Stub {

        private final WeakReference<CarBugreportManagerCallback> mWeakCallback;
        private final WeakReference<Handler> mWeakHandler;

        /**
         * Create a new callback wrapper.
         *
         * @param callback the callback passed from app
         * @param handler  the handler to execute callbacks on
         */
        CarBugreportManagerCallbackWrapper(CarBugreportManagerCallback callback,
                Handler handler) {
            mWeakCallback = new WeakReference<>(callback);
            mWeakHandler = new WeakReference<>(handler);
        }

        @Override
        public void onError(@CarBugreportManagerCallback.CarBugreportErrorCode int errorCode) {
            CarBugreportManagerCallback callback = mWeakCallback.get();
            Handler handler = mWeakHandler.get();
            if (handler != null && callback != null) {
                handler.post(() -> callback.onError(errorCode));
            }
        }

        @Override
        public void onFinished() {
            CarBugreportManagerCallback callback = mWeakCallback.get();
            Handler handler = mWeakHandler.get();
            if (handler != null && callback != null) {
                handler.post(() -> callback.onFinished());
            }
        }
    }

    /**
     * Get an instance of the CarBugreportManager
     *
     * Should not be obtained directly by clients, use {@link Car#getCarManager(String)} instead.
     */
    public CarBugreportManager(IBinder service, Context context) {
        mService = ICarBugreportService.Stub.asInterface(service);
        mHandler = new Handler(context.getMainLooper());
    }

    /**
     * Request a bug report. An old style flat bugreport is generated in the background.
     * The fd is closed when bugreport is written or if an exception happens.
     *
     * @param fd  the dump file
     * @param callback  the callback for reporting dump status
     */
    @RequiresPermission(android.Manifest.permission.DUMP)
    public void requestBugreport(@NonNull ParcelFileDescriptor fd,
            @NonNull CarBugreportManagerCallback callback) {
        try {
            Preconditions.checkNotNull(fd);
            Preconditions.checkNotNull(callback);
            CarBugreportManagerCallbackWrapper wrapper =
                    new CarBugreportManagerCallbackWrapper(callback, mHandler);
            mService.requestBugreport(fd, wrapper);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } finally {
            IoUtils.closeQuietly(fd);
        }
    }

    @Override
    public void onCarDisconnected() {
    }

}
