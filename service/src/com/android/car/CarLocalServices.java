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

package com.android.car;

import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Copy of frameworks/base/core/java/com/android/server/LocalServices.java
 * This is for accessing other car service components.
 */
public class CarLocalServices {
    private CarLocalServices() {}

    private static final ArrayMap<Class<?>, Object> sLocalServiceObjects =
            new ArrayMap<Class<?>, Object>();

    /**
     * Returns a local service instance that implements the specified interface.
     *
     * @param type The type of service.
     * @return The service object.
     */
    @SuppressWarnings("unchecked")
    public static <T> T getService(Class<T> type) {
        Log.d("CarLocalServices", " getService " + type.getSimpleName());
        synchronized (sLocalServiceObjects) {
            return (T) sLocalServiceObjects.get(type);
        }
    }

    /**
     * Adds a service instance of the specified interface to the global registry of local services.
     */
    public static <T> void addService(Class<T> type, T service) {
        synchronized (sLocalServiceObjects) {
            if (sLocalServiceObjects.containsKey(type)) {
                throw new IllegalStateException("Overriding service registration");
            }
            Log.d("CarLocalServices", " Adding " + type.getSimpleName());
            sLocalServiceObjects.put(type, service);
        }
    }

    /**
     * Remove a service instance, must be only used in tests.
     */
    @VisibleForTesting
    public static <T> void removeServiceForTest(Class<T> type) {
        Log.d("CarLocalServices", " Removing " + type.getSimpleName());
        synchronized (sLocalServiceObjects) {
            sLocalServiceObjects.remove(type);
        }
    }

    /**
     * Remove all registered services. Should be called when car service restarts.
     */
    public static void removeAllServices() {
        Log.d("CarLocalServices", " removeAllServices");
        synchronized (sLocalServiceObjects) {
            sLocalServiceObjects.clear();
        }
    }
}
