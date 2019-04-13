/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.car.audio;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.car.Car;
import android.car.media.CarAudioManager;
import android.car.media.CarAudioPatchHandle;
import android.car.media.ICarAudio;
import android.car.media.ICarVolumeCallback;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.automotive.audiocontrol.V1_0.IAudioControl;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioDevicePort;
import android.media.AudioFormat;
import android.media.AudioGain;
import android.media.AudioGainConfig;
import android.media.AudioManager;
import android.media.AudioPatch;
import android.media.AudioPlaybackConfiguration;
import android.media.AudioPortConfig;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioPolicy;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;

import com.android.car.BinderInterfaceContainer;
import com.android.car.CarLog;
import com.android.car.CarServiceBase;
import com.android.car.R;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service responsible for interaction with car's audio system.
 */
public class CarAudioService extends ICarAudio.Stub implements CarServiceBase {

    // Turning this off will result in falling back to the default focus policy of Android
    // (which boils down to "grant if not in a phone call, else deny").
    // Aside from the obvious effect of ignoring the logic in CarAudioFocus, this will also
    // result in the framework taking over responsibility for ducking in TRANSIENT_LOSS cases.
    // Search for "DUCK_VSHAPE" in PLaybackActivityMonitor.java to see where this happens.
    private static boolean sUseCarAudioFocus = true;

    // Key to persist master mute state in system settings
    private static final String VOLUME_SETTINGS_KEY_MASTER_MUTE = "android.car.MASTER_MUTE";

    // The trailing slash forms a directory-liked hierarchy and
    // allows listening for both GROUP/MEDIA and GROUP/NAVIGATION.
    private static final String VOLUME_SETTINGS_KEY_FOR_GROUP_PREFIX = "android.car.VOLUME_GROUP/";

    // CarAudioService reads configuration from the following paths respectively.
    // If the first one is found, all others are ignored.
    // If no one is found, it fallbacks to car_volume_groups.xml resource file.
    private static final String[] AUDIO_CONFIGURATION_PATHS = new String[] {
            "/vendor/etc/car_audio_configuration.xml",
            "/system/etc/car_audio_configuration.xml"
    };

    /**
     * Gets the key to persist volume for a volume group in settings
     *
     * @param zoneId The audio zone id
     * @param groupId The volume group id
     * @return Key to persist volume index for volume group in system settings
     */
    static String getVolumeSettingsKeyForGroup(int zoneId, int groupId) {
        final int maskedGroupId = (zoneId << 8) + groupId;
        return VOLUME_SETTINGS_KEY_FOR_GROUP_PREFIX + maskedGroupId;
    }

    private final Object mImplLock = new Object();

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final AudioManager mAudioManager;
    private final boolean mUseDynamicRouting;
    private final boolean mPersistMasterMuteState;

    private final AudioPolicy.AudioPolicyVolumeCallback mAudioPolicyVolumeCallback =
            new AudioPolicy.AudioPolicyVolumeCallback() {
        @Override
        public void onVolumeAdjustment(int adjustment) {
            final int usage = getSuggestedAudioUsage();
            Log.v(CarLog.TAG_AUDIO,
                    "onVolumeAdjustment: " + AudioManager.adjustToString(adjustment)
                            + " suggested usage: " + AudioAttributes.usageToString(usage));
            // TODO: Pass zone id into this callback.
            final int zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
            final int groupId = getVolumeGroupIdForUsage(zoneId, usage);
            final int currentVolume = getGroupVolume(zoneId, groupId);
            final int flags = AudioManager.FLAG_FROM_KEY | AudioManager.FLAG_SHOW_UI;
            switch (adjustment) {
                case AudioManager.ADJUST_LOWER:
                    int minValue = Math.max(currentVolume - 1, getGroupMinVolume(zoneId, groupId));
                    setGroupVolume(zoneId, groupId, minValue , flags);
                    break;
                case AudioManager.ADJUST_RAISE:
                    int maxValue =  Math.min(currentVolume + 1, getGroupMaxVolume(zoneId, groupId));
                    setGroupVolume(zoneId, groupId, maxValue, flags);
                    break;
                case AudioManager.ADJUST_MUTE:
                    setMasterMute(true, flags);
                    callbackMasterMuteChange(zoneId, flags);
                    break;
                case AudioManager.ADJUST_UNMUTE:
                    setMasterMute(false, flags);
                    callbackMasterMuteChange(zoneId, flags);
                    break;
                case AudioManager.ADJUST_TOGGLE_MUTE:
                    setMasterMute(!mAudioManager.isMasterMute(), flags);
                    callbackMasterMuteChange(zoneId, flags);
                    break;
                case AudioManager.ADJUST_SAME:
                default:
                    break;
            }
        }
    };

    private final BinderInterfaceContainer<ICarVolumeCallback> mVolumeCallbackContainer =
            new BinderInterfaceContainer<>();

    /**
     * Simulates {@link ICarVolumeCallback} when it's running in legacy mode.
     * This receiver assumes the intent is sent to {@link CarAudioManager#PRIMARY_AUDIO_ZONE}.
     */
    private final BroadcastReceiver mLegacyVolumeChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final int zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
            switch (intent.getAction()) {
                case AudioManager.VOLUME_CHANGED_ACTION:
                    int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                    int groupId = getVolumeGroupIdForStreamType(streamType);
                    if (groupId == -1) {
                        Log.w(CarLog.TAG_AUDIO, "Unknown stream type: " + streamType);
                    } else {
                        callbackGroupVolumeChange(zoneId, groupId, 0);
                    }
                    break;
                case AudioManager.MASTER_MUTE_CHANGED_ACTION:
                    callbackMasterMuteChange(zoneId, 0);
                    break;
            }
        }
    };

    private AudioPolicy mAudioPolicy;
    private CarAudioFocus mFocusHandler;
    private String mCarAudioConfigurationPath;
    private CarAudioZone[] mCarAudioZones;

    public CarAudioService(Context context) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
        mAudioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        mUseDynamicRouting = mContext.getResources().getBoolean(R.bool.audioUseDynamicRouting);
        mPersistMasterMuteState = mContext.getResources().getBoolean(
                R.bool.audioPersistMasterMuteState);
    }

    /**
     * Dynamic routing and volume groups are set only if
     * {@link #mUseDynamicRouting} is {@code true}. Otherwise, this service runs in legacy mode.
     */
    @Override
    public void init() {
        synchronized (mImplLock) {
            if (mUseDynamicRouting) {
                // Enumerate all output bus device ports
                AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(
                        AudioManager.GET_DEVICES_OUTPUTS);
                if (deviceInfos.length == 0) {
                    Log.e(CarLog.TAG_AUDIO, "No output device available, ignore");
                    return;
                }
                SparseArray<CarAudioDeviceInfo> busToCarAudioDeviceInfo = new SparseArray<>();
                for (AudioDeviceInfo info : deviceInfos) {
                    Log.v(CarLog.TAG_AUDIO, String.format("output id=%d address=%s type=%s",
                            info.getId(), info.getAddress(), info.getType()));
                    if (info.getType() == AudioDeviceInfo.TYPE_BUS) {
                        final CarAudioDeviceInfo carInfo = new CarAudioDeviceInfo(info);
                        // See also the audio_policy_configuration.xml,
                        // the bus number should be no less than zero.
                        if (carInfo.getBusNumber() >= 0) {
                            busToCarAudioDeviceInfo.put(carInfo.getBusNumber(), carInfo);
                            Log.i(CarLog.TAG_AUDIO, "Valid bus found " + carInfo);
                        }
                    }
                }
                setupDynamicRouting(busToCarAudioDeviceInfo);
            } else {
                Log.i(CarLog.TAG_AUDIO, "Audio dynamic routing not enabled, run in legacy mode");
                setupLegacyVolumeChangedListener();
            }

            // Restore master mute state if applicable
            if (mPersistMasterMuteState) {
                boolean storedMasterMute = Settings.Global.getInt(mContext.getContentResolver(),
                        VOLUME_SETTINGS_KEY_MASTER_MUTE, 0) != 0;
                setMasterMute(storedMasterMute, 0);
            }
        }
    }

    @Override
    public void release() {
        synchronized (mImplLock) {
            if (mUseDynamicRouting) {
                if (mAudioPolicy != null) {
                    mAudioManager.unregisterAudioPolicyAsync(mAudioPolicy);
                    mAudioPolicy = null;
                    mFocusHandler.setOwningPolicy(null, null);
                    mFocusHandler = null;
                }
            } else {
                mContext.unregisterReceiver(mLegacyVolumeChangedReceiver);
            }

            mVolumeCallbackContainer.clear();
        }
    }

    @Override
    public void dump(PrintWriter writer) {
        writer.println("*CarAudioService*");
        writer.println("\tRun in legacy mode? " + (!mUseDynamicRouting));
        writer.println("\tPersist master mute state? " + mPersistMasterMuteState);
        writer.println("\tMaster muted? " + mAudioManager.isMasterMute());
        if (mCarAudioConfigurationPath != null) {
            writer.println("\tCar audio configuration path: " + mCarAudioConfigurationPath);
        }
        // Empty line for comfortable reading
        writer.println();
        if (mUseDynamicRouting) {
            for (CarAudioZone zone : mCarAudioZones) {
                zone.dump("\t", writer);
            }
        }
    }

    @Override
    public boolean isDynamicRoutingEnabled() {
        return mUseDynamicRouting;
    }

    /**
     * @see {@link android.car.media.CarAudioManager#setGroupVolume(int, int, int, int)}
     */
    @Override
    public void setGroupVolume(int zoneId, int groupId, int index, int flags) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            callbackGroupVolumeChange(zoneId, groupId, flags);
            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                mAudioManager.setStreamVolume(
                        CarAudioDynamicRouting.STREAM_TYPES[groupId], index, flags);
                return;
            }

            CarVolumeGroup group = getCarVolumeGroup(zoneId, groupId);
            group.setCurrentGainIndex(index);
        }
    }

    private void callbackGroupVolumeChange(int zoneId, int groupId, int flags) {
        for (BinderInterfaceContainer.BinderInterface<ICarVolumeCallback> callback :
                mVolumeCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onGroupVolumeChanged(zoneId, groupId, flags);
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_AUDIO, "Failed to callback onGroupVolumeChanged", e);
            }
        }
    }

    private void setMasterMute(boolean mute, int flags) {
        mAudioManager.setMasterMute(mute, flags);

        // When the master mute is turned ON, we want the playing app to get a "pause" command.
        // When the volume is unmuted, we want to resume playback.
        int keycode = mute ? KeyEvent.KEYCODE_MEDIA_PAUSE : KeyEvent.KEYCODE_MEDIA_PLAY;
        mAudioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keycode));
        mAudioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keycode));
    }

    private void callbackMasterMuteChange(int zoneId, int flags) {
        for (BinderInterfaceContainer.BinderInterface<ICarVolumeCallback> callback :
                mVolumeCallbackContainer.getInterfaces()) {
            try {
                callback.binderInterface.onMasterMuteChanged(zoneId, flags);
            } catch (RemoteException e) {
                Log.e(CarLog.TAG_AUDIO, "Failed to callback onMasterMuteChanged", e);
            }
        }

        // Persists master mute state if applicable
        if (mPersistMasterMuteState) {
            Settings.Global.putInt(mContext.getContentResolver(),
                    VOLUME_SETTINGS_KEY_MASTER_MUTE,
                    mAudioManager.isMasterMute() ? 1 : 0);
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupMaxVolume(int, int)}
     */
    @Override
    public int getGroupMaxVolume(int zoneId, int groupId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                return mAudioManager.getStreamMaxVolume(
                        CarAudioDynamicRouting.STREAM_TYPES[groupId]);
            }

            CarVolumeGroup group = getCarVolumeGroup(zoneId, groupId);
            return group.getMaxGainIndex();
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupMinVolume(int, int)}
     */
    @Override
    public int getGroupMinVolume(int zoneId, int groupId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                return mAudioManager.getStreamMinVolume(
                        CarAudioDynamicRouting.STREAM_TYPES[groupId]);
            }

            CarVolumeGroup group = getCarVolumeGroup(zoneId, groupId);
            return group.getMinGainIndex();
        }
    }

    /**
     * @see {@link android.car.media.CarAudioManager#getGroupVolume(int, int)}
     */
    @Override
    public int getGroupVolume(int zoneId, int groupId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                return mAudioManager.getStreamVolume(
                        CarAudioDynamicRouting.STREAM_TYPES[groupId]);
            }

            CarVolumeGroup group = getCarVolumeGroup(zoneId, groupId);
            return group.getCurrentGainIndex();
        }
    }

    private CarVolumeGroup getCarVolumeGroup(int zoneId, int groupId) {
        Preconditions.checkNotNull(mCarAudioZones);
        Preconditions.checkArgumentInRange(zoneId, 0, mCarAudioZones.length - 1,
                "zoneId out of range: " + zoneId);
        return mCarAudioZones[zoneId].getVolumeGroup(groupId);
    }

    private void setupLegacyVolumeChangedListener() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        intentFilter.addAction(AudioManager.MASTER_MUTE_CHANGED_ACTION);
        mContext.registerReceiver(mLegacyVolumeChangedReceiver, intentFilter);
    }

    private void setupDynamicRouting(SparseArray<CarAudioDeviceInfo> busToCarAudioDeviceInfo) {
        final AudioPolicy.Builder builder = new AudioPolicy.Builder(mContext);
        builder.setLooper(Looper.getMainLooper());

        final CarAudioZonesLoader zonesLoader;
        mCarAudioConfigurationPath = getAudioConfigurationPath();
        if (mCarAudioConfigurationPath != null) {
            zonesLoader = new CarAudioZonesHelper(mContext, mCarAudioConfigurationPath,
                    busToCarAudioDeviceInfo);
        } else {
            // In legacy mode, context -> bus mapping is done by querying IAudioControl HAL.
            final IAudioControl audioControl = getAudioControl();
            if (audioControl == null) {
                throw new RuntimeException(
                        "Dynamic routing requested but audioControl HAL not available");
            }
            zonesLoader = new CarAudioZonesHelperLegacy(mContext, R.xml.car_volume_groups,
                    busToCarAudioDeviceInfo, audioControl);
        }
        mCarAudioZones = zonesLoader.loadAudioZones();
        for (CarAudioZone zone : mCarAudioZones) {
            if (!zone.validateVolumeGroups()) {
                throw new RuntimeException("Invalid volume groups configuration");
            }
            // Ensure HAL gets our initial value
            zone.synchronizeCurrentGainIndex();
            Log.v(CarLog.TAG_AUDIO, "Processed audio zone: " + zone);
        }

        // Setup dynamic routing rules by usage
        final CarAudioDynamicRouting dynamicRouting = new CarAudioDynamicRouting(mCarAudioZones);
        dynamicRouting.setupAudioDynamicRouting(builder);

        // Attach the {@link AudioPolicyVolumeCallback}
        builder.setAudioPolicyVolumeCallback(mAudioPolicyVolumeCallback);

        if (sUseCarAudioFocus) {
            // Configure our AudioPolicy to handle focus events.
            // This gives us the ability to decide which audio focus requests to accept and bypasses
            // the framework ducking logic.
            mFocusHandler = new CarAudioFocus(mAudioManager, mContext.getPackageManager());
            builder.setAudioPolicyFocusListener(mFocusHandler);
            builder.setIsAudioFocusPolicy(true);
        }

        mAudioPolicy = builder.build();
        if (sUseCarAudioFocus) {
            // Connect the AudioPolicy and the focus listener
            mFocusHandler.setOwningPolicy(this, mAudioPolicy);
        }

        int r = mAudioManager.registerAudioPolicy(mAudioPolicy);
        if (r != AudioManager.SUCCESS) {
            throw new RuntimeException("registerAudioPolicy failed " + r);
        }
    }

    /**
     * Read from {@link #AUDIO_CONFIGURATION_PATHS} respectively.
     * @return File path of the first hit in {@link #AUDIO_CONFIGURATION_PATHS}
     */
    @Nullable
    private String getAudioConfigurationPath() {
        for (String path : AUDIO_CONFIGURATION_PATHS) {
            File configuration = new File(path);
            if (configuration.exists()) {
                return path;
            }
        }
        return null;
    }

    /**
     * @return Context number for a given audio usage, 0 if the given usage is unrecognized.
     */
    int getContextForUsage(int audioUsage) {
        return CarAudioDynamicRouting.USAGE_TO_CONTEXT.get(audioUsage);
    }

    @Override
    public void setFadeTowardFront(float value) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            final IAudioControl audioControlHal = getAudioControl();
            if (audioControlHal != null) {
                try {
                    audioControlHal.setFadeTowardFront(value);
                } catch (RemoteException e) {
                    Log.e(CarLog.TAG_AUDIO, "setFadeTowardFront failed", e);
                }
            }
        }
    }

    @Override
    public void setBalanceTowardRight(float value) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            final IAudioControl audioControlHal = getAudioControl();
            if (audioControlHal != null) {
                try {
                    audioControlHal.setBalanceTowardRight(value);
                } catch (RemoteException e) {
                    Log.e(CarLog.TAG_AUDIO, "setBalanceTowardRight failed", e);
                }
            }
        }
    }

    /**
     * @return Array of accumulated device addresses, empty array if we found nothing
     */
    @Override
    public @NonNull String[] getExternalSources() {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
            List<String> sourceAddresses = new ArrayList<>();

            AudioDeviceInfo[] devices = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
            if (devices.length == 0) {
                Log.w(CarLog.TAG_AUDIO, "getExternalSources, no input devices found.");
            }

            // Collect the list of non-microphone input ports
            for (AudioDeviceInfo info : devices) {
                switch (info.getType()) {
                    // TODO:  Can we trim this set down? Especially duplicates like FM vs FM_TUNER?
                    case AudioDeviceInfo.TYPE_FM:
                    case AudioDeviceInfo.TYPE_FM_TUNER:
                    case AudioDeviceInfo.TYPE_TV_TUNER:
                    case AudioDeviceInfo.TYPE_HDMI:
                    case AudioDeviceInfo.TYPE_AUX_LINE:
                    case AudioDeviceInfo.TYPE_LINE_ANALOG:
                    case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                    case AudioDeviceInfo.TYPE_USB_ACCESSORY:
                    case AudioDeviceInfo.TYPE_USB_DEVICE:
                    case AudioDeviceInfo.TYPE_USB_HEADSET:
                    case AudioDeviceInfo.TYPE_IP:
                    case AudioDeviceInfo.TYPE_BUS:
                        String address = info.getAddress();
                        if (TextUtils.isEmpty(address)) {
                            Log.w(CarLog.TAG_AUDIO,
                                    "Discarded device with empty address, type=" + info.getType());
                        } else {
                            sourceAddresses.add(address);
                        }
                }
            }

            return sourceAddresses.toArray(new String[0]);
        }
    }

    @Override
    public CarAudioPatchHandle createAudioPatch(String sourceAddress,
            @AudioAttributes.AttributeUsage int usage, int gainInMillibels) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
            return createAudioPatchLocked(sourceAddress, usage, gainInMillibels);
        }
    }

    @Override
    public void releaseAudioPatch(CarAudioPatchHandle carPatch) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_SETTINGS);
            releaseAudioPatchLocked(carPatch);
        }
    }

    private CarAudioPatchHandle createAudioPatchLocked(String sourceAddress,
            @AudioAttributes.AttributeUsage int usage, int gainInMillibels) {
        // Find the named source port
        AudioDeviceInfo sourcePortInfo = null;
        AudioDeviceInfo[] deviceInfos = mAudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        for (AudioDeviceInfo info : deviceInfos) {
            if (sourceAddress.equals(info.getAddress())) {
                // This is the one for which we're looking
                sourcePortInfo = info;
                break;
            }
        }
        Preconditions.checkNotNull(sourcePortInfo,
                "Specified source is not available: " + sourceAddress);

        // Find the output port associated with the given carUsage
        AudioDevicePort sinkPort = Preconditions.checkNotNull(getAudioPort(usage),
                "Sink not available for usage: " + AudioAttributes.usageToString(usage));

        // {@link android.media.AudioPort#activeConfig()} is valid for mixer port only,
        // since audio framework has no clue what's active on the device ports.
        // Therefore we construct an empty / default configuration here, which the audio HAL
        // implementation should ignore.
        AudioPortConfig sinkConfig = sinkPort.buildConfig(0,
                AudioFormat.CHANNEL_OUT_DEFAULT, AudioFormat.ENCODING_DEFAULT, null);
        Log.d(CarLog.TAG_AUDIO, "createAudioPatch sinkConfig: " + sinkConfig);

        // Configure the source port to match the output port except for a gain adjustment
        final CarAudioDeviceInfo helper = new CarAudioDeviceInfo(sourcePortInfo);
        AudioGain audioGain = Preconditions.checkNotNull(helper.getAudioGain(),
                "Gain controller not available for source port");

        // size of gain values is 1 in MODE_JOINT
        AudioGainConfig audioGainConfig = audioGain.buildConfig(AudioGain.MODE_JOINT,
                audioGain.channelMask(), new int[] { gainInMillibels }, 0);
        // Construct an empty / default configuration excepts gain config here and it's up to the
        // audio HAL how to interpret this configuration, which the audio HAL
        // implementation should ignore.
        AudioPortConfig sourceConfig = sourcePortInfo.getPort().buildConfig(0,
                AudioFormat.CHANNEL_IN_DEFAULT, AudioFormat.ENCODING_DEFAULT, audioGainConfig);

        // Create an audioPatch to connect the two ports
        AudioPatch[] patch = new AudioPatch[] { null };
        int result = AudioManager.createAudioPatch(patch,
                new AudioPortConfig[] { sourceConfig },
                new AudioPortConfig[] { sinkConfig });
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("createAudioPatch failed with code " + result);
        }

        Preconditions.checkNotNull(patch[0],
                "createAudioPatch didn't provide expected single handle");
        Log.d(CarLog.TAG_AUDIO, "Audio patch created: " + patch[0]);

        // Ensure the initial volume on output device port
        int groupId = getVolumeGroupIdForUsage(CarAudioManager.PRIMARY_AUDIO_ZONE, usage);
        setGroupVolume(CarAudioManager.PRIMARY_AUDIO_ZONE, groupId,
                getGroupVolume(CarAudioManager.PRIMARY_AUDIO_ZONE, groupId), 0);

        return new CarAudioPatchHandle(patch[0]);
    }

    private void releaseAudioPatchLocked(CarAudioPatchHandle carPatch) {
        // NOTE:  AudioPolicyService::removeNotificationClient will take care of this automatically
        //        if the client that created a patch quits.

        // FIXME {@link AudioManager#listAudioPatches(ArrayList)} returns old generation of
        // audio patches after creation
        ArrayList<AudioPatch> patches = new ArrayList<>();
        int result = AudioSystem.listAudioPatches(patches, new int[1]);
        if (result != AudioManager.SUCCESS) {
            throw new RuntimeException("listAudioPatches failed with code " + result);
        }

        // Look for a patch that matches the provided user side handle
        for (AudioPatch patch : patches) {
            if (carPatch.represents(patch)) {
                // Found it!
                result = AudioManager.releaseAudioPatch(patch);
                if (result != AudioManager.SUCCESS) {
                    throw new RuntimeException("releaseAudioPatch failed with code " + result);
                }
                return;
            }
        }

        // If we didn't find a match, then something went awry, but it's probably not fatal...
        Log.e(CarLog.TAG_AUDIO, "releaseAudioPatch found no match for " + carPatch);
    }

    @Override
    public int getVolumeGroupCount(int zoneId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            // For legacy stream type based volume control
            if (!mUseDynamicRouting) return CarAudioDynamicRouting.STREAM_TYPES.length;

            Preconditions.checkArgumentInRange(zoneId, 0, mCarAudioZones.length - 1,
                    "zoneId out of range: " + zoneId);
            return mCarAudioZones[zoneId].getVolumeGroupCount();
        }
    }

    @Override
    public int getVolumeGroupIdForUsage(int zoneId, @AudioAttributes.AttributeUsage int usage) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);
            Preconditions.checkArgumentInRange(zoneId, 0, mCarAudioZones.length - 1,
                    "zoneId out of range: " + zoneId);

            CarVolumeGroup[] groups = mCarAudioZones[zoneId].getVolumeGroups();
            for (int i = 0; i < groups.length; i++) {
                int[] contexts = groups[i].getContexts();
                for (int context : contexts) {
                    if (getContextForUsage(usage) == context) {
                        return i;
                    }
                }
            }
            return -1;
        }
    }

    @Override
    public @NonNull int[] getUsagesForVolumeGroupId(int zoneId, int groupId) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            // For legacy stream type based volume control
            if (!mUseDynamicRouting) {
                return new int[] { CarAudioDynamicRouting.STREAM_TYPE_USAGES[groupId] };
            }

            CarVolumeGroup group = getCarVolumeGroup(zoneId, groupId);
            Set<Integer> contexts =
                    Arrays.stream(group.getContexts()).boxed().collect(Collectors.toSet());
            final List<Integer> usages = new ArrayList<>();
            for (int i = 0; i < CarAudioDynamicRouting.USAGE_TO_CONTEXT.size(); i++) {
                if (contexts.contains(CarAudioDynamicRouting.USAGE_TO_CONTEXT.valueAt(i))) {
                    usages.add(CarAudioDynamicRouting.USAGE_TO_CONTEXT.keyAt(i));
                }
            }
            return usages.stream().mapToInt(i -> i).toArray();
        }
    }

    @Override
    public void registerVolumeCallback(@NonNull IBinder binder) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            mVolumeCallbackContainer.addBinder(ICarVolumeCallback.Stub.asInterface(binder));
        }
    }

    @Override
    public void unregisterVolumeCallback(@NonNull IBinder binder) {
        synchronized (mImplLock) {
            enforcePermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME);

            mVolumeCallbackContainer.removeBinder(ICarVolumeCallback.Stub.asInterface(binder));
        }
    }

    private void enforcePermission(String permissionName) {
        if (mContext.checkCallingOrSelfPermission(permissionName)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("requires permission " + permissionName);
        }
    }

    /**
     * @return {@link AudioDevicePort} that handles the given car audio usage.
     * Multiple usages may share one {@link AudioDevicePort}
     */
    private @Nullable AudioDevicePort getAudioPort(@AudioAttributes.AttributeUsage int usage) {
        int zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        final int groupId = getVolumeGroupIdForUsage(zoneId, usage);
        final CarVolumeGroup group = Preconditions.checkNotNull(
                mCarAudioZones[zoneId].getVolumeGroup(groupId),
                "Can not find CarVolumeGroup by usage: "
                        + AudioAttributes.usageToString(usage));
        return group.getAudioDevicePortForContext(getContextForUsage(usage));
    }

    /**
     * @return The suggested {@link AudioAttributes} usage to which the volume key events apply
     */
    private @AudioAttributes.AttributeUsage int getSuggestedAudioUsage() {
        int callState = mTelephonyManager.getCallState();
        if (callState == TelephonyManager.CALL_STATE_RINGING) {
            return AudioAttributes.USAGE_NOTIFICATION_RINGTONE;
        } else if (callState == TelephonyManager.CALL_STATE_OFFHOOK) {
            return AudioAttributes.USAGE_VOICE_COMMUNICATION;
        } else {
            List<AudioPlaybackConfiguration> playbacks = mAudioManager
                    .getActivePlaybackConfigurations()
                    .stream()
                    .filter(AudioPlaybackConfiguration::isActive)
                    .collect(Collectors.toList());
            if (!playbacks.isEmpty()) {
                // Get audio usage from active playbacks if there is any, last one if multiple
                return playbacks.get(playbacks.size() - 1).getAudioAttributes().getUsage();
            } else {
                // TODO(b/72695246): Otherwise, get audio usage from foreground activity/window
                return CarAudioDynamicRouting.DEFAULT_AUDIO_USAGE;
            }
        }
    }

    /**
     * Gets volume group by a given legacy stream type
     * @param streamType Legacy stream type such as {@link AudioManager#STREAM_MUSIC}
     * @return volume group id mapped from stream type
     */
    private int getVolumeGroupIdForStreamType(int streamType) {
        int groupId = -1;
        for (int i = 0; i < CarAudioDynamicRouting.STREAM_TYPES.length; i++) {
            if (streamType == CarAudioDynamicRouting.STREAM_TYPES[i]) {
                groupId = i;
                break;
            }
        }
        return groupId;
    }

    @Nullable
    private static IAudioControl getAudioControl() {
        try {
            return IAudioControl.getService();
        } catch (RemoteException e) {
            Log.e(CarLog.TAG_AUDIO, "Failed to get IAudioControl service", e);
        } catch (NoSuchElementException e) {
            Log.e(CarLog.TAG_AUDIO, "IAudioControl service not registered yet");
        }
        return null;
    }

    interface CarAudioZonesLoader {
        CarAudioZone[] loadAudioZones();
    }
}
