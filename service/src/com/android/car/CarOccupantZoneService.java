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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.car.Car;
import android.car.CarInfoManager;
import android.car.CarOccupantZoneManager;
import android.car.CarOccupantZoneManager.OccupantTypeEnum;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.ICarOccupantZone;
import android.car.ICarOccupantZoneCallback;
import android.car.VehicleAreaSeat;
import android.car.media.CarAudioManager;
import android.car.user.CarUserManager;
import android.car.user.CarUserManager.UserLifecycleListener;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Display;
import android.view.DisplayAddress;

import com.android.car.user.CarUserService;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Service to implement CarOccupantZoneManager API.
 */
public final class CarOccupantZoneService extends ICarOccupantZone.Stub
        implements CarServiceBase {

    private static final int INVALID_OCCUPANT_ZONE_ID = -1;

    private final Object mLock = new Object();
    private final Context mContext;
    private final DisplayManager mDisplayManager;

    /** key: zone id */
    @GuardedBy("mLock")
    private final HashMap<Integer, OccupantZoneInfo> mOccupantsConfig = new HashMap<>();

    @VisibleForTesting
    static class DisplayConfig {
        public final int displayType;
        public final int occupantZoneId;

        DisplayConfig(int displayType, int occupantZoneId) {
            this.displayType = displayType;
            this.occupantZoneId = occupantZoneId;
        }

        @Override
        public String toString() {
            // do not include type as this is only used for dump
            StringBuilder b = new StringBuilder(64);
            b.append("{displayType=");
            b.append(Integer.toHexString(displayType));
            b.append(" occupantZoneId=");
            b.append(occupantZoneId);
            b.append("}");
            return b.toString();
        }
    }

    /** key: display port address */
    @GuardedBy("mLock")
    private final HashMap<Integer, DisplayConfig> mDisplayConfigs = new HashMap<>();

    /** key: audio zone id */
    @GuardedBy("mLock")
    private final SparseIntArray mAudioZoneIdToOccupantZoneIdMapping = new SparseIntArray();

    @VisibleForTesting
    static class DisplayInfo {
        public final Display display;
        public final int displayType;

        DisplayInfo(Display display, int displayType) {
            this.display = display;
            this.displayType = displayType;
        }

        @Override
        public String toString() {
            // do not include type as this is only used for dump
            StringBuilder b = new StringBuilder(64);
            b.append("{displayId=");
            b.append(display.getDisplayId());
            b.append(" displayType=");
            b.append(displayType);
            b.append("}");
            return b.toString();
        }
    }

    @VisibleForTesting
    static class OccupantConfig {
        public int userId = UserHandle.USER_NULL;
        public final LinkedList<DisplayInfo> displayInfos = new LinkedList<>();
        public int audioZoneId = CarAudioManager.INVALID_AUDIO_ZONE;

        @Override
        public String toString() {
            // do not include type as this is only used for dump
            StringBuilder b = new StringBuilder(128);
            b.append("{userId=");
            b.append(userId);
            b.append(" displays=");
            for (DisplayInfo info : displayInfos) {
                b.append(info.toString());
            }
            b.append(" audioZoneId=");
            if (audioZoneId != CarAudioManager.INVALID_AUDIO_ZONE) {
                b.append(audioZoneId);
            } else {
                b.append("none");
            }
            b.append("}");
            return b.toString();
        }
    }

    /** key : zoneId */
    @GuardedBy("mLock")
    private final HashMap<Integer, OccupantConfig> mActiveOccupantConfigs = new HashMap<>();

    @VisibleForTesting
    final UserLifecycleListener mUserLifecycleListener = event -> {
        if (Log.isLoggable(CarLog.TAG_MEDIA, Log.DEBUG)) {
            Log.d(CarLog.TAG_MEDIA, "onEvent(" + event + ")");
        }
        if (CarUserManager.USER_LIFECYCLE_EVENT_TYPE_SWITCHING == event.getEventType()) {
            handleUserChange();
        }
    };

    final CarUserService.PassengerCallback mPassengerCallback =
            new CarUserService.PassengerCallback() {
                @Override
                public void onPassengerStarted(@UserIdInt int passengerId, int zoneId) {
                    handlePassengerStarted(passengerId, zoneId);
                }

                @Override
                public void onPassengerStopped(@UserIdInt int passengerId) {
                    handlePassengerStopped(passengerId);
                }
            };

    @VisibleForTesting
    final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                    handleDisplayChange();
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                    handleDisplayChange();
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    // nothing to do
                }
            };

    private final RemoteCallbackList<ICarOccupantZoneCallback> mClientCallbacks =
            new RemoteCallbackList<>();

    @GuardedBy("mLock")
    private int mDriverSeat = VehicleAreaSeat.SEAT_UNKNOWN;

    public CarOccupantZoneService(Context context) {
        mContext = context;
        mDisplayManager = context.getSystemService(DisplayManager.class);
    }

    @VisibleForTesting
    public CarOccupantZoneService(Context context, DisplayManager displayManager) {
        mContext = context;
        mDisplayManager = displayManager;
    }

    @Override
    public void init() {
        // This does not require connection as binder will be passed directly.
        Car car = new Car(mContext, /* service= */null, /* handler= */ null);
        CarInfoManager infoManager = new CarInfoManager(car, CarLocalServices.getService(
                CarPropertyService.class));
        int driverSeat = infoManager.getDriverSeat();
        synchronized (mLock) {
            mDriverSeat = driverSeat;
            parseOccupantZoneConfigsLocked();
            parseDisplayConfigsLocked();
            handleActiveDisplaysLocked();
            handleAudioZoneChangesLocked();
            handleUserChangesLocked();
        }
        CarUserService userService = CarLocalServices.getService(CarUserService.class);
        userService.addUserLifecycleListener(mUserLifecycleListener);
        userService.addPassengerCallback(mPassengerCallback);
        mDisplayManager.registerDisplayListener(mDisplayListener,
                new Handler(Looper.getMainLooper()));
        CarUserService.ZoneUserBindingHelper helper = new CarUserService.ZoneUserBindingHelper() {
            @Override
            @NonNull
            public List<OccupantZoneInfo> getOccupantZones(@OccupantTypeEnum int occupantType) {
                List<OccupantZoneInfo> zones = new ArrayList<OccupantZoneInfo>();
                for (OccupantZoneInfo ozi : getAllOccupantZones()) {
                    if (ozi.occupantType == occupantType) {
                        zones.add(ozi);
                    }
                }
                return zones;
            }

            @Override
            public boolean assignUserToOccupantZone(@UserIdInt int userId, int zoneId) {
                // Check if the user is already assigned to the other zone.
                synchronized (mLock) {
                    for (Map.Entry<Integer, OccupantConfig> entry :
                            mActiveOccupantConfigs.entrySet()) {
                        OccupantConfig config = entry.getValue();
                        if (config.userId == userId && zoneId != entry.getKey()) {
                            Log.w(CarLog.TAG_OCCUPANT,
                                    "cannot assign user to two different zone simultaneously");
                            return false;
                        }
                    }
                    OccupantConfig zoneConfig = mActiveOccupantConfigs.get(zoneId);
                    if (zoneConfig == null) {
                        Log.w(CarLog.TAG_OCCUPANT, "cannot find the zone(" + zoneId + ")");
                        return false;
                    }
                    if (zoneConfig.userId != UserHandle.USER_NULL && zoneConfig.userId != userId) {
                        Log.w(CarLog.TAG_OCCUPANT,
                                "other user already occupies the zone(" + zoneId + ")");
                        return false;
                    }
                    zoneConfig.userId = userId;
                    return true;
                }
            }

            @Override
            public boolean unassignUserFromOccupantZone(@UserIdInt int userId) {
                synchronized (mLock) {
                    for (OccupantConfig config : mActiveOccupantConfigs.values()) {
                        if (config.userId == userId) {
                            config.userId = UserHandle.USER_NULL;
                            break;
                        }
                    }
                    return true;
                }
            }

            @Override
            public boolean isPassengerDisplayAvailable() {
                for (OccupantZoneInfo ozi : getAllOccupantZones()) {
                    if (getDisplayForOccupant(ozi.zoneId,
                            CarOccupantZoneManager.DISPLAY_TYPE_MAIN) != Display.INVALID_DISPLAY
                            && ozi.occupantType != CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER) {
                        return true;
                    }
                }
                return false;
            }
        };
        userService.setZoneUserBindingHelper(helper);
    }

    @Override
    public void release() {
        mDisplayManager.unregisterDisplayListener(mDisplayListener);
        CarUserService userService = CarLocalServices.getService(CarUserService.class);
        userService.removeUserLifecycleListener(mUserLifecycleListener);
        userService.removePassengerCallback(mPassengerCallback);
        synchronized (mLock) {
            mOccupantsConfig.clear();
            mDisplayConfigs.clear();
            mAudioZoneIdToOccupantZoneIdMapping.clear();
            mActiveOccupantConfigs.clear();
        }
    }

    /** Return cloned mOccupantsConfig for testing */
    @VisibleForTesting
    @NonNull
    public HashMap<Integer, OccupantZoneInfo> getOccupantsConfig() {
        synchronized (mLock) {
            return (HashMap<Integer, OccupantZoneInfo>) mOccupantsConfig.clone();
        }
    }

    /** Return cloned mDisplayConfigs for testing */
    @VisibleForTesting
    @NonNull
    public HashMap<Integer, DisplayConfig> getDisplayConfigs() {
        synchronized (mLock) {
            return (HashMap<Integer, DisplayConfig>) mDisplayConfigs.clone();
        }
    }

    /** Return cloned mAudioConfigs for testing */
    @VisibleForTesting
    @NonNull
    SparseIntArray getAudioConfigs() {
        synchronized (mLock) {
            return mAudioZoneIdToOccupantZoneIdMapping.clone();
        }
    }

    /** Return cloned mActiveOccupantConfigs for testing */
    @VisibleForTesting
    @NonNull
    public HashMap<Integer, OccupantConfig> getActiveOccupantConfigs() {
        synchronized (mLock) {
            return (HashMap<Integer, OccupantConfig>) mActiveOccupantConfigs.clone();
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*OccupantZoneService*");
        synchronized (mLock) {
            writer.println("**mOccupantsConfig**");
            for (Map.Entry<Integer, OccupantZoneInfo> entry : mOccupantsConfig.entrySet()) {
                writer.println(" zoneId=" + entry.getKey()
                        + " info=" + entry.getValue().toString());
            }
            writer.println("**mDisplayConfigs**");
            for (Map.Entry<Integer, DisplayConfig> entry : mDisplayConfigs.entrySet()) {
                writer.println(" port=" + Integer.toHexString(entry.getKey())
                        + " config=" + entry.getValue().toString());
            }
            writer.println("**mAudioZoneIdToOccupantZoneIdMapping**");
            for (int index = 0; index < mAudioZoneIdToOccupantZoneIdMapping.size(); index++) {
                int audioZoneId = mAudioZoneIdToOccupantZoneIdMapping.keyAt(index);
                writer.println(" audioZoneId=" + Integer.toHexString(audioZoneId)
                        + " zoneId=" + mAudioZoneIdToOccupantZoneIdMapping.get(audioZoneId));
            }
            writer.println("**mActiveOccupantConfigs**");
            for (Map.Entry<Integer, OccupantConfig> entry : mActiveOccupantConfigs.entrySet()) {
                writer.println(" zoneId=" + entry.getKey()
                        + " config=" + entry.getValue().toString());
            }
        }
    }

    @Override
    public List<OccupantZoneInfo> getAllOccupantZones() {
        synchronized (mLock) {
            List<OccupantZoneInfo> infos = new ArrayList<>();
            for (Integer zoneId : mActiveOccupantConfigs.keySet()) {
                // no need for deep copy as OccupantZoneInfo itself is static.
                infos.add(mOccupantsConfig.get(zoneId));
            }
            return infos;
        }
    }

    @Override
    public int[] getAllDisplaysForOccupantZone(int occupantZoneId) {
        synchronized (mLock) {
            OccupantConfig config = mActiveOccupantConfigs.get(occupantZoneId);
            if (config == null) {
                return new int[0];
            }
            int[] displayIds = new int[config.displayInfos.size()];
            int i = 0;
            for (DisplayInfo displayInfo : config.displayInfos) {
                displayIds[i] = displayInfo.display.getDisplayId();
                i++;
            }
            return displayIds;
        }
    }

    @Override
    public int getDisplayForOccupant(int occupantZoneId, int displayType) {
        synchronized (mLock) {
            OccupantConfig config = mActiveOccupantConfigs.get(occupantZoneId);
            if (config == null) {
                return Display.INVALID_DISPLAY;
            }
            for (DisplayInfo displayInfo : config.displayInfos) {
                if (displayType == displayInfo.displayType) {
                    return displayInfo.display.getDisplayId();
                }
            }
        }
        return Display.INVALID_DISPLAY;
    }

    @Override
    public int getAudioZoneIdForOccupant(int occupantZoneId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        synchronized (mLock) {
            OccupantConfig config = mActiveOccupantConfigs.get(occupantZoneId);
            if (config != null) {
                return config.audioZoneId;
            }
            // check if the occupant id exist at all
            if (!mOccupantsConfig.containsKey(occupantZoneId)) {
                return CarAudioManager.INVALID_AUDIO_ZONE;
            }
            // Exist but not active
            return getAudioZoneIdForOccupantLocked(occupantZoneId);
        }
    }

    private int getAudioZoneIdForOccupantLocked(int occupantZoneId) {
        for (int index = 0; index < mAudioZoneIdToOccupantZoneIdMapping.size(); index++) {
            int audioZoneId = mAudioZoneIdToOccupantZoneIdMapping.keyAt(index);
            if (occupantZoneId == mAudioZoneIdToOccupantZoneIdMapping.get(audioZoneId)) {
                return audioZoneId;
            }
        }
        return CarAudioManager.INVALID_AUDIO_ZONE;
    }

    @Override
    public CarOccupantZoneManager.OccupantZoneInfo getOccupantForAudioZoneId(int audioZoneId) {
        enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
        synchronized (mLock) {
            int occupantZoneId = mAudioZoneIdToOccupantZoneIdMapping.get(audioZoneId,
                    INVALID_OCCUPANT_ZONE_ID);
            if (occupantZoneId == INVALID_OCCUPANT_ZONE_ID) {
                return null;
            }
            // To support headless zones return the occupant configuration.
            return mOccupantsConfig.get(occupantZoneId);
        }
    }

    @Nullable
    private DisplayConfig findDisplayConfigForDisplayLocked(int displayId) {
        for (Map.Entry<Integer, DisplayConfig> entry : mDisplayConfigs.entrySet()) {
            Display display = mDisplayManager.getDisplay(displayId);
            if (display == null) {
                continue;
            }
            Byte portAddress = getPortAddress(display);
            if (portAddress == null) {
                continue;
            }
            DisplayConfig config =
                    mDisplayConfigs.get(Byte.toUnsignedInt(portAddress));
            return config;
        }
        return null;
    }

    @Override
    public int getDisplayType(int displayId) {
        synchronized (mLock) {
            DisplayConfig config = findDisplayConfigForDisplayLocked(displayId);
            if (config != null) {
                return config.displayType;
            }
        }
        return CarOccupantZoneManager.DISPLAY_TYPE_UNKNOWN;
    }

    @Override
    public int getUserForOccupant(int occupantZoneId) {
        synchronized (mLock) {
            OccupantConfig config = mActiveOccupantConfigs.get(occupantZoneId);
            if (config == null) {
                return UserHandle.USER_NULL;
            }
            return config.userId;
        }
    }

    @Override
    public int getOccupantZoneIdForUserId(int userId) {
        synchronized (mLock) {
            for (int occupantZoneId : mActiveOccupantConfigs.keySet()) {
                OccupantConfig config = mActiveOccupantConfigs.get(occupantZoneId);
                if (config.userId == userId) {
                    return occupantZoneId;
                }
            }
            Log.w(CarLog.TAG_OCCUPANT, "Could not find occupantZoneId for userId" + userId
                    + " returning invalid occupant zone id " + OccupantZoneInfo.INVALID_ZONE_ID);
            return OccupantZoneInfo.INVALID_ZONE_ID;
        }
    }

    /**
     * Sets the mapping for audio zone id to occupant zone id.
     *
     * @param audioZoneIdToOccupantZoneMapping map for audio zone id, where key is the audio zone id
     * and value is the occupant zone id.
     */
    public void setAudioZoneIdsForOccupantZoneIds(
            @NonNull SparseIntArray audioZoneIdToOccupantZoneMapping) {
        Objects.requireNonNull(audioZoneIdToOccupantZoneMapping,
                "audioZoneIdToOccupantZoneMapping can not be null");
        synchronized (mLock) {
            validateOccupantZoneIdsLocked(audioZoneIdToOccupantZoneMapping);
            mAudioZoneIdToOccupantZoneIdMapping.clear();
            for (int index = 0; index < audioZoneIdToOccupantZoneMapping.size(); index++) {
                int audioZoneId = audioZoneIdToOccupantZoneMapping.keyAt(index);
                mAudioZoneIdToOccupantZoneIdMapping.put(audioZoneId,
                        audioZoneIdToOccupantZoneMapping.get(audioZoneId));
            }
            //If there are any active displays for the zone send change event
            handleAudioZoneChangesLocked();
        }
        sendConfigChangeEvent(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_AUDIO);
    }

    private void validateOccupantZoneIdsLocked(SparseIntArray audioZoneIdToOccupantZoneMapping) {
        for (int i = 0; i < audioZoneIdToOccupantZoneMapping.size(); i++) {
            int occupantZoneId =
                    audioZoneIdToOccupantZoneMapping.get(audioZoneIdToOccupantZoneMapping.keyAt(i));
            if (!mOccupantsConfig.containsKey(occupantZoneId)) {
                throw new IllegalArgumentException("occupantZoneId " + occupantZoneId
                        + " does not exist.");
            }
        }
    }

    @Override
    public void registerCallback(ICarOccupantZoneCallback callback) {
        mClientCallbacks.register(callback);
    }

    @Override
    public void unregisterCallback(ICarOccupantZoneCallback callback) {
        mClientCallbacks.unregister(callback);
    }

    private void throwFormatErrorInOccupantZones(String msg) {
        throw new RuntimeException("Format error in config_occupant_zones resource:" + msg);
    }

    // For overriding in test
    @VisibleForTesting
    int getDriverSeat() {
        synchronized (mLock) {
            return mDriverSeat;
        }
    }

    private void parseOccupantZoneConfigsLocked() {
        final Resources res = mContext.getResources();
        // examples:
        // <item>occupantZoneId=0,occupantType=DRIVER,seatRow=1,seatSide=driver</item>
        // <item>occupantZoneId=1,occupantType=FRONT_PASSENGER,seatRow=1,
        // searSide=oppositeDriver</item>
        boolean hasDriver = false;
        int driverSeat = getDriverSeat();
        int driverSeatSide = VehicleAreaSeat.SIDE_LEFT; // default LHD : Left Hand Drive
        if (driverSeat == VehicleAreaSeat.SEAT_ROW_1_RIGHT) {
            driverSeatSide = VehicleAreaSeat.SIDE_RIGHT;
        }
        for (String config : res.getStringArray(R.array.config_occupant_zones)) {
            int zoneId = OccupantZoneInfo.INVALID_ZONE_ID;
            int type = CarOccupantZoneManager.OCCUPANT_TYPE_INVALID;
            int seatRow = 0; // invalid row
            int seatSide = VehicleAreaSeat.SIDE_LEFT;
            String[] entries = config.split(",");
            for (String entry : entries) {
                String[] keyValuePair = entry.split("=");
                if (keyValuePair.length != 2) {
                    throwFormatErrorInOccupantZones("No key/value pair:" + entry);
                }
                switch (keyValuePair[0]) {
                    case "occupantZoneId":
                        zoneId = Integer.parseInt(keyValuePair[1]);
                        break;
                    case "occupantType":
                        switch (keyValuePair[1]) {
                            case "DRIVER":
                                type = CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
                                break;
                            case "FRONT_PASSENGER":
                                type = CarOccupantZoneManager.OCCUPANT_TYPE_FRONT_PASSENGER;
                                break;
                            case "REAR_PASSENGER":
                                type = CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER;
                                break;
                            default:
                                throwFormatErrorInOccupantZones("Unrecognized type:" + entry);
                                break;
                        }
                        break;
                    case "seatRow":
                        seatRow = Integer.parseInt(keyValuePair[1]);
                        break;
                    case "seatSide":
                        switch (keyValuePair[1]) {
                            case "driver":
                                seatSide = driverSeatSide;
                                break;
                            case "oppositeDriver":
                                seatSide = -driverSeatSide;
                                break;
                            case "left":
                                seatSide = VehicleAreaSeat.SIDE_LEFT;
                                break;
                            case "center":
                                seatSide = VehicleAreaSeat.SIDE_CENTER;
                                break;
                            case "right":
                                seatSide = VehicleAreaSeat.SIDE_RIGHT;
                                break;
                            default:
                                throwFormatErrorInOccupantZones("Unregognized seatSide:" + entry);
                                break;

                        }
                        break;
                    default:
                        throwFormatErrorInOccupantZones("Unrecognized key:" + entry);
                        break;
                }
            }
            if (zoneId == OccupantZoneInfo.INVALID_ZONE_ID) {
                throwFormatErrorInOccupantZones("Missing zone id:" + config);
            }
            if (type == CarOccupantZoneManager.OCCUPANT_TYPE_INVALID) {
                throwFormatErrorInOccupantZones("Missing type:" + config);
            }
            if (type == CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER) {
                if (hasDriver) {
                    throwFormatErrorInOccupantZones("Multiple driver:" + config);
                } else {
                    hasDriver = true;
                }

            }
            int seat = VehicleAreaSeat.fromRowAndSide(seatRow, seatSide);
            if (seat == VehicleAreaSeat.SEAT_UNKNOWN) {
                throwFormatErrorInOccupantZones("Invalid seat:" + config);
            }
            OccupantZoneInfo info = new OccupantZoneInfo(zoneId, type, seat);
            if (mOccupantsConfig.containsKey(zoneId)) {
                throwFormatErrorInOccupantZones("Duplicate zone id:" + config);
            }
            mOccupantsConfig.put(zoneId, info);
        }
    }

    private void throwFormatErrorInDisplayMapping(String msg) {
        throw new RuntimeException(
                "Format error in config_occupant_display_mapping resource:" + msg);
    }

    private void parseDisplayConfigsLocked() {
        final Resources res = mContext.getResources();
        // examples:
        // <item>displayPort=0,displayType=MAIN,occupantZoneId=0</item>
        // <item>displayPort=1,displayType=INSTRUMENT_CLUSTER,occupantZoneId=0</item>
        boolean hasDriver = false;
        final int invalidPort = -1;
        for (String config : res.getStringArray(R.array.config_occupant_display_mapping)) {
            int port = invalidPort;
            int type = CarOccupantZoneManager.DISPLAY_TYPE_UNKNOWN;
            int zoneId = OccupantZoneInfo.INVALID_ZONE_ID;
            String[] entries = config.split(",");
            for (String entry : entries) {
                String[] keyValuePair = entry.split("=");
                if (keyValuePair.length != 2) {
                    throwFormatErrorInDisplayMapping("No key/value pair:" + entry);
                }
                switch (keyValuePair[0]) {
                    case "displayPort":
                        port = Integer.parseInt(keyValuePair[1]);
                        break;
                    case "displayType":
                        switch (keyValuePair[1]) {
                            case "MAIN":
                                type = CarOccupantZoneManager.DISPLAY_TYPE_MAIN;
                                break;
                            case "INSTRUMENT_CLUSTER":
                                type = CarOccupantZoneManager.DISPLAY_TYPE_INSTRUMENT_CLUSTER;
                                break;
                            case "HUD":
                                type = CarOccupantZoneManager.DISPLAY_TYPE_HUD;
                                break;
                            case "INPUT":
                                type = CarOccupantZoneManager.DISPLAY_TYPE_INPUT;
                                break;
                            case "AUXILIARY":
                                type = CarOccupantZoneManager.DISPLAY_TYPE_AUXILIARY;
                                break;
                            default:
                                throwFormatErrorInDisplayMapping(
                                        "Unrecognized display type:" + entry);
                                break;
                        }
                        break;
                    case "occupantZoneId":
                        zoneId = Integer.parseInt(keyValuePair[1]);
                        break;
                    default:
                        throwFormatErrorInDisplayMapping("Unrecognized key:" + entry);
                        break;

                }
            }
            // Now check validity
            if (port == invalidPort) {
                throwFormatErrorInDisplayMapping("Missing or invalid displayPort:" + config);
            }

            if (type == CarOccupantZoneManager.DISPLAY_TYPE_UNKNOWN) {
                throwFormatErrorInDisplayMapping("Missing or invalid displayType:" + config);
            }
            if (zoneId == OccupantZoneInfo.INVALID_ZONE_ID) {
                throwFormatErrorInDisplayMapping("Missing or invalid occupantZoneId:" + config);
            }
            if (!mOccupantsConfig.containsKey(zoneId)) {
                throwFormatErrorInDisplayMapping(
                        "Missing or invalid occupantZoneId:" + config);
            }
            if (mDisplayConfigs.containsKey(port)) {
                throwFormatErrorInDisplayMapping("Duplicate displayPort:" + config);
            }
            mDisplayConfigs.put(port, new DisplayConfig(type, zoneId));
        }
    }

    private Byte getPortAddress(Display display) {
        DisplayAddress address = display.getAddress();
        if (address instanceof DisplayAddress.Physical) {
            DisplayAddress.Physical physicalAddress = (DisplayAddress.Physical) address;
            if (physicalAddress != null) {
                return physicalAddress.getPort();
            }
        }
        return null;
    }

    private void handleActiveDisplaysLocked() {
        mActiveOccupantConfigs.clear();
        for (Display display : mDisplayManager.getDisplays()) {
            Byte rawPortAddress = getPortAddress(display);
            if (rawPortAddress == null) {
                continue;
            }

            int portAddress = Byte.toUnsignedInt(rawPortAddress);
            DisplayConfig displayConfig = mDisplayConfigs.get(portAddress);
            if (displayConfig == null) {
                Log.w(CarLog.TAG_OCCUPANT,
                        "Display id:" + display.getDisplayId() + " port:" + portAddress
                                + " does not have configurations");
                continue;
            }
            OccupantConfig occupantConfig = mActiveOccupantConfigs.get(
                    displayConfig.occupantZoneId);
            if (occupantConfig == null) {
                occupantConfig = new OccupantConfig();
                mActiveOccupantConfigs.put(displayConfig.occupantZoneId, occupantConfig);
            }
            occupantConfig.displayInfos.add(new DisplayInfo(display, displayConfig.displayType));
        }
    }

    @VisibleForTesting
    int getCurrentUser() {
        return ActivityManager.getCurrentUser();
    }

    private void handleUserChangesLocked() {
        int driverUserId = getCurrentUser();
        OccupantConfig driverConfig = getDriverOccupantConfigLocked();
        if (driverConfig != null) {
            driverConfig.userId = driverUserId;
        }
    }

    @Nullable
    private OccupantConfig getDriverOccupantConfigLocked() {
        for (Map.Entry<Integer, OccupantZoneInfo> entry: mOccupantsConfig.entrySet()) {
            if (entry.getValue().occupantType == CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER) {
                return mActiveOccupantConfigs.get(entry.getKey());
            }
        }
        return null;
    }

    private void handleAudioZoneChangesLocked() {
        for (int index = 0; index < mAudioZoneIdToOccupantZoneIdMapping.size(); index++) {
            int audioZoneId = mAudioZoneIdToOccupantZoneIdMapping.keyAt(index);
            int occupantZoneId = mAudioZoneIdToOccupantZoneIdMapping.get(audioZoneId);
            OccupantConfig occupantConfig =
                    mActiveOccupantConfigs.get(occupantZoneId);
            if (occupantConfig == null) {
                //no active display for zone just continue
                continue;
            }
            // Found an active configuration, add audio to it.
            occupantConfig.audioZoneId = audioZoneId;
        }
    }

    private void sendConfigChangeEvent(int changeFlags) {
        final int n = mClientCallbacks.beginBroadcast();
        for (int i = 0; i < n; i++) {
            ICarOccupantZoneCallback callback = mClientCallbacks.getBroadcastItem(i);
            try {
                callback.onOccupantZoneConfigChanged(changeFlags);
            } catch (RemoteException ignores) {
                // ignore
            }
        }
        mClientCallbacks.finishBroadcast();
    }

    private void handleUserChange() {
        synchronized (mLock) {
            handleUserChangesLocked();
        }
        sendConfigChangeEvent(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
    }

    private void handlePassengerStarted(@UserIdInt int passengerId, int zoneId) {
        sendConfigChangeEvent(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
    }

    private void handlePassengerStopped(@UserIdInt int passengerId) {
        sendConfigChangeEvent(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_USER);
    }

    private void handleDisplayChange() {
        synchronized (mLock) {
            handleActiveDisplaysLocked();
            //audio zones should be re-checked for changed display
            handleAudioZoneChangesLocked();
            // user should be re-checked for changed displays
            handleUserChangesLocked();
        }
        sendConfigChangeEvent(CarOccupantZoneManager.ZONE_CONFIG_CHANGE_FLAG_DISPLAY);
    }

    private void enforcePermission(String permissionName) {
        if (mContext.checkCallingOrSelfPermission(permissionName)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires permission " + permissionName);
        }
    }
}
