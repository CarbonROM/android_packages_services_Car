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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.UserIdInt;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.ICarUserService;
import android.content.pm.UserInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * API to manage users related to car.
 *
 * @hide
 */
public final class CarUserManager extends CarManagerBase {

    /* User id representing invalid user */
    public static final int INVALID_USER_ID = UserHandle.USER_NULL;

    private static final String TAG = CarUserManager.class.getSimpleName();
    private final ICarUserService mService;

    @VisibleForTesting
    public CarUserManager(Car car, @NonNull IBinder service) {
        super(car);
        mService = ICarUserService.Stub.asInterface(service);
    }

    /**
     * Creates a driver who is a regular user and is allowed to login to the driving occupant zone.
     *
     * @param name The name of the driver to be created.
     * @param admin Whether the created driver will be an admin.
     * @return user id of the created driver, or {@code INVALID_USER_ID} if the driver could
     *         not be created.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @Nullable
    public int createDriver(@NonNull String name, boolean admin) {
        try {
            UserInfo ui = mService.createDriver(name, admin);
            return ui != null ? ui.id : INVALID_USER_ID;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Creates a passenger who is a profile of the given driver.
     *
     * @param name The name of the passenger to be created.
     * @param driverId User id of the driver under whom a passenger is created.
     * @return user id of the created passenger, or {@code INVALID_USER_ID} if the passenger
     *         could not be created.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @Nullable
    public int createPassenger(@NonNull String name, @UserIdInt int driverId) {
        try {
            UserInfo ui = mService.createPassenger(name, driverId);
            return ui != null ? ui.id : INVALID_USER_ID;
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, null);
        }
    }

    /**
     * Switches a driver to the given user.
     *
     * @param driverId User id of the driver to switch to.
     * @return {@code true} if user switching succeeds, or {@code false} if it fails.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean switchDriver(@UserIdInt int driverId) {
        try {
            return mService.switchDriver(driverId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Returns all drivers who can occupy the driving zone. Guest users are included in the list.
     *
     * @return the list of user ids who can be a driver on the device.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @NonNull
    public List<Integer> getAllDrivers() {
        try {
            return getUserIdsFromUserInfos(mService.getAllDrivers());
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.emptyList());
        }
    }

    /**
     * Returns all passengers under the given driver.
     *
     * @param driverId User id of a driver.
     * @return the list of user ids who are passengers under the given driver.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    @NonNull
    public List<Integer> getPassengers(@UserIdInt int driverId) {
        try {
            return getUserIdsFromUserInfos(mService.getPassengers(driverId));
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, Collections.emptyList());
        }
    }

    /**
     * Assigns the passenger to the zone and starts the user if it is not started yet.
     *
     * @param passengerId User id of the passenger to be started.
     * @param zoneId Zone id to which the passenger is assigned.
     * @return {@code true} if the user is successfully started or the user is already running.
     *         Otherwise, {@code false}.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean startPassenger(@UserIdInt int passengerId, int zoneId) {
        try {
            return mService.startPassenger(passengerId, zoneId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    /**
     * Stops the given passenger.
     *
     * @param passengerId User id of the passenger to be stopped.
     * @return {@code true} if successfully stopped, or {@code false} if failed.
     */
    @RequiresPermission(android.Manifest.permission.MANAGE_USERS)
    public boolean stopPassenger(@UserIdInt int passengerId) {
        try {
            return mService.stopPassenger(passengerId);
        } catch (RemoteException e) {
            return handleRemoteExceptionFromCarService(e, false);
        }
    }

    @Override
    public void onCarDisconnected() {
        // nothing to do
    }

    private List<Integer> getUserIdsFromUserInfos(List<UserInfo> infos) {
        List<Integer> ids = new ArrayList<>(infos.size());
        for (UserInfo ui : infos) {
            ids.add(ui.id);
        }
        return ids;
    }
}
