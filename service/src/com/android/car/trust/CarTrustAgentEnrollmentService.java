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

package com.android.car.trust;

import static android.car.trust.CarTrustAgentEnrollmentManager.ENROLLMENT_NOT_ALLOWED;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.bluetooth.BluetoothDevice;
import android.car.encryptionrunner.EncryptionRunner;
import android.car.encryptionrunner.EncryptionRunnerFactory;
import android.car.encryptionrunner.HandshakeException;
import android.car.encryptionrunner.HandshakeMessage;
import android.car.encryptionrunner.HandshakeMessage.HandshakeState;
import android.car.encryptionrunner.Key;
import android.car.trust.ICarTrustAgentBleCallback;
import android.car.trust.ICarTrustAgentEnrollment;
import android.car.trust.ICarTrustAgentEnrollmentCallback;
import android.car.trust.TrustedDeviceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.car.R;
import com.android.car.Utils;
import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * A service that is part of the CarTrustedDeviceService that is responsible for allowing a
 * phone to enroll as a trusted device.  The enrolled phone can then be used for authenticating a
 * user on the HU.  This implements the {@link android.car.trust.CarTrustAgentEnrollmentManager}
 * APIs that an app like Car Settings can call to conduct an enrollment.
 */
public class CarTrustAgentEnrollmentService extends ICarTrustAgentEnrollment.Stub {
    private static final String TAG = "CarTrustAgentEnroll";
    private static final String FAKE_AUTH_STRING = "000000";
    private static final String TRUSTED_DEVICE_ENROLLMENT_ENABLED_KEY =
            "trusted_device_enrollment_enabled";
    private static final byte[] CONFIRMATION_SIGNAL = "True".getBytes();
    //Arbirary log size
    private static final int MAX_LOG_SIZE = 20;

    private final CarTrustedDeviceService mTrustedDeviceService;
    // List of clients listening to Enrollment state change events.
    private final List<EnrollmentStateClient> mEnrollmentStateClients = new ArrayList<>();
    // List of clients listening to BLE state changes events during enrollment.
    private final List<BleStateChangeClient> mBleStateChangeClients = new ArrayList<>();
    private final Queue<String> mLogQueue = new LinkedList<>();

    private final CarTrustAgentBleManager mCarTrustAgentBleManager;
    private CarTrustAgentEnrollmentRequestDelegate mEnrollmentDelegate;

    private Object mRemoteDeviceLock = new Object();
    @GuardedBy("mRemoteDeviceLock")
    private BluetoothDevice mRemoteEnrollmentDevice;
    @GuardedBy("this")
    private boolean mEnrollmentHandshakeAccepted;
    private final Map<Long, Boolean> mTokenActiveStateMap = new HashMap<>();
    private String mDeviceName;
    private final Context mContext;

    private EncryptionRunner mEncryptionRunner = EncryptionRunnerFactory.newRunner();
    private HandshakeMessage mHandshakeMessage;
    private Key mEncryptionKey;
    @HandshakeState
    private int mEncryptionState = HandshakeState.UNKNOWN;

    public CarTrustAgentEnrollmentService(Context context, CarTrustedDeviceService service,
            CarTrustAgentBleManager bleService) {
        mContext = context;
        mTrustedDeviceService = service;
        mCarTrustAgentBleManager = bleService;
    }

    public synchronized void init() {
        mCarTrustAgentBleManager.setupEnrollmentBleServer();
    }

    public synchronized void release() {
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            client.mListenerBinder.unlinkToDeath(client, 0);
        }
        for (BleStateChangeClient client : mBleStateChangeClients) {
            client.mListenerBinder.unlinkToDeath(client, 0);
        }
        mEnrollmentStateClients.clear();
        setEnrollmentHandshakeAccepted(false);
    }

    // Implementing the ICarTrustAgentEnrollment interface

    /**
     * Begin BLE advertisement for Enrollment. This should be called from an app that conducts
     * the enrollment of the trusted device.
     */
    @Override
    public void startEnrollmentAdvertising() {
        if (!mTrustedDeviceService.getSharedPrefs()
                .getBoolean(TRUSTED_DEVICE_ENROLLMENT_ENABLED_KEY, true)) {
            Log.e(TAG, "Trusted Device Enrollment disabled");
            for (EnrollmentStateClient client : mEnrollmentStateClients) {
                try {
                    client.mListener.onEnrollmentHandshakeFailure(null, ENROLLMENT_NOT_ALLOWED);
                } catch (RemoteException e) {
                    Log.e(TAG, "onEnrollmentHandshakeFailure dispatch failed", e);
                }
            }
            return;
        }
        // Stop any current broadcasts
        mTrustedDeviceService.getCarTrustAgentUnlockService().stopUnlockAdvertising();
        stopEnrollmentAdvertising();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startEnrollmentAdvertising");
        }
        addEnrollmentServiceLog("startEnrollmentAdvertising");
        mCarTrustAgentBleManager.startEnrollmentAdvertising();
    }

    /**
     * Stop BLE advertisement for Enrollment
     */
    @Override
    public void stopEnrollmentAdvertising() {
        addEnrollmentServiceLog("stopEnrollmentAdvertising");
        mCarTrustAgentBleManager.stopEnrollmentAdvertising();
    }

    /**
     * Called by the client to notify that the user has accepted a pairing code or any out-of-band
     * confirmation, and send confirmation signals to remote bluetooth device.
     *
     * @param device the remote Bluetooth device that will receive the signal.
     */
    @Override
    public void enrollmentHandshakeAccepted(BluetoothDevice device) {
        addEnrollmentServiceLog("enrollmentHandshakeAccepted");
        mCarTrustAgentBleManager.sendPairingCodeConfirmation(device, CONFIRMATION_SIGNAL);
        setEnrollmentHandshakeAccepted(true);
    }

    /**
     * Terminate the Enrollment process.  To be called when an error is encountered during
     * enrollment.  For example - user pressed cancel on pairing code confirmation or user
     * navigated away from the app before completing enrollment.
     */
    @Override
    public void terminateEnrollmentHandshake() {
        setEnrollmentHandshakeAccepted(false);
        addEnrollmentServiceLog("terminateEnrollmentHandshake");
        // Disconnect from BLE
        mCarTrustAgentBleManager.disconnectRemoteDevice();
        // Remove any handles that have not been activated yet.
        Iterator<Map.Entry<Long, Boolean>> it = mTokenActiveStateMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Boolean> pair = it.next();
            boolean isHandleActive = pair.getValue();
            if (!isHandleActive) {
                long handle = pair.getKey();
                int uid = mTrustedDeviceService.getSharedPrefs().getInt(String.valueOf(handle), -1);
                removeEscrowToken(handle, uid);
                it.remove();
            }
        }
    }

    /**
     * Returns if there is an active token for the given user and handle.
     *
     * @param handle handle corresponding to the escrow token
     * @param uid    user id
     * @return True if the escrow token is active, false if not
     */
    @Override
    public boolean isEscrowTokenActive(long handle, int uid) {
        if (mTokenActiveStateMap.get(handle) != null) {
            return mTokenActiveStateMap.get(handle);
        }
        return false;
    }

    /**
     * Remove the Token associated with the given handle for the given user.
     *
     * @param handle handle corresponding to the escrow token
     * @param uid    user id
     */
    @Override
    public void removeEscrowToken(long handle, int uid) {
        mEnrollmentDelegate.removeEscrowToken(handle, uid);
        addEnrollmentServiceLog("removeEscrowToken (handle:" + handle + " uid:" + uid + ")");
    }

    /**
     * Remove all Trusted devices associated with the given user.
     *
     * @param uid user id
     */
    @Override
    public void removeAllTrustedDevices(int uid) {
        for (TrustedDeviceInfo device : getEnrolledDeviceInfosForUser(uid)) {
            removeEscrowToken(device.getHandle(), uid);
        }
    }

    /**
     * Enable or disable enrollment of a Trusted device.  When disabled,
     * {@link android.car.trust.CarTrustAgentEnrollmentManager#ENROLLMENT_NOT_ALLOWED} is returned,
     * when {@link #startEnrollmentAdvertising()} is called by a client.
     *
     * @param isEnabled {@code true} to enable; {@code false} to disable the feature.
     */
    @Override
    public void setTrustedDeviceEnrollmentEnabled(boolean isEnabled) {
        SharedPreferences.Editor editor = mTrustedDeviceService.getSharedPrefs().edit();
        editor.putBoolean(TRUSTED_DEVICE_ENROLLMENT_ENABLED_KEY, isEnabled);
        editor.apply();
    }

    /**
     * Enable or disable authentication of the head unit with a trusted device.
     *
     * @param isEnabled when set to {@code false}, head unit will not be
     *                  discoverable to unlock the user.  Setting it to {@code true} will enable it
     *                  back.
     */
    @Override
    public void setTrustedDeviceUnlockEnabled(boolean isEnabled) {
        mTrustedDeviceService.getCarTrustAgentUnlockService()
                .setTrustedDeviceUnlockEnabled(isEnabled);
    }

    /**
     * Get the Handles and Device Mac Address corresponding to the token for the current user.  The
     * client can use this to list the trusted devices for the user.
     *
     * @param uid user id
     * @return array of trusted device handles and names for the user.
     */
    @NonNull
    @Override
    public List<TrustedDeviceInfo> getEnrolledDeviceInfosForUser(int uid) {
        Set<String> enrolledDeviceInfos = mTrustedDeviceService.getSharedPrefs().getStringSet(
                String.valueOf(uid), new HashSet<>());
        List<TrustedDeviceInfo> trustedDeviceInfos = new ArrayList<>(enrolledDeviceInfos.size());
        for (String deviceInfo : enrolledDeviceInfos) {
            trustedDeviceInfos.add(TrustedDeviceInfo.deserialize(deviceInfo));
        }
        return trustedDeviceInfos;


    }

    /**
     * Registers a {@link ICarTrustAgentEnrollmentCallback} to be notified for changes to the
     * enrollment state.
     *
     * @param listener {@link ICarTrustAgentEnrollmentCallback}
     */
    @Override
    public synchronized void registerEnrollmentCallback(ICarTrustAgentEnrollmentCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }
        // If a new client is registering, create a new EnrollmentStateClient and add it to the list
        // of listening clients.
        EnrollmentStateClient client = findEnrollmentStateClientLocked(listener);
        if (client == null) {
            client = new EnrollmentStateClient(listener);
            try {
                listener.asBinder().linkToDeath(client, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot link death recipient to binder ", e);
                return;
            }
            mEnrollmentStateClients.add(client);
        }
    }

    void onEscrowTokenAdded(byte[] token, long handle, int uid) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onEscrowTokenAdded handle:" + handle + " uid:" + uid);
        }

        if (mRemoteEnrollmentDevice == null) {
            Log.e(TAG, "onEscrowTokenAdded() but no remote device connected!");
            removeEscrowToken(handle, uid);
            return;
        }
        // To conveniently get the user id to unlock when handle is received.
        mTrustedDeviceService.getSharedPrefs().edit().putInt(String.valueOf(handle), uid).apply();
        mTokenActiveStateMap.put(handle, false);
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onEscrowTokenAdded(handle);
            } catch (RemoteException e) {
                Log.e(TAG, "onEscrowTokenAdded dispatch failed", e);
            }
        }
    }

    void onEscrowTokenRemoved(long handle, int uid) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onEscrowTokenRemoved handle:" + handle + " uid:" + uid);
        }
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onEscrowTokenRemoved(handle);
            } catch (RemoteException e) {
                Log.e(TAG, "onEscrowTokenRemoved dispatch failed", e);
            }
        }
        SharedPreferences.Editor editor = mTrustedDeviceService.getSharedPrefs().edit();
        editor.remove(String.valueOf(handle));
        Set<String> deviceInfos = mTrustedDeviceService.getSharedPrefs().getStringSet(
                String.valueOf(uid), new HashSet<>());
        Iterator<String> iterator = deviceInfos.iterator();
        while (iterator.hasNext()) {
            String deviceInfoString = iterator.next();
            if (TrustedDeviceInfo.deserialize(deviceInfoString).getHandle()
                    == handle) {
                iterator.remove();
                break;
            }
        }
        editor.putStringSet(String.valueOf(uid), deviceInfos);
        editor.apply();
    }

    /**
     * @param handle        the handle whose active state change
     * @param isTokenActive the active state of the handle
     * @param uid           id of current user
     */
    void onEscrowTokenActiveStateChanged(long handle, boolean isTokenActive, int uid) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onEscrowTokenActiveStateChanged: " + Long.toHexString(handle));
        }
        mTokenActiveStateMap.put(handle, isTokenActive);
        dispatchEscrowTokenActiveStateChanged(handle, isTokenActive);
        if (isTokenActive) {
            Set<String> deviceInfo = mTrustedDeviceService.getSharedPrefs().getStringSet(
                    String.valueOf(uid), new HashSet<>());
            String deviceName;
            if (mRemoteEnrollmentDevice.getName() != null) {
                deviceName = mRemoteEnrollmentDevice.getName();
            } else if (mDeviceName != null) {
                deviceName = mDeviceName;
            } else {
                deviceName = mContext.getString(R.string.trust_device_default_name);
            }
            addEnrollmentServiceLog("trustedDeviceAdded (handle:" + handle + ", uid:" + uid
                    + ", addr:" + mRemoteEnrollmentDevice.getAddress()
                    + ", name:" + deviceName + ")");
            deviceInfo.add(new TrustedDeviceInfo(handle, mRemoteEnrollmentDevice.getAddress(),
                    deviceName).serialize());
            // To conveniently get the devices info regarding certain user.
            mTrustedDeviceService.getSharedPrefs().edit().putStringSet(String.valueOf(uid),
                    deviceInfo).apply();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Sending handle: " + handle);
            }
            mCarTrustAgentBleManager.sendEnrollmentHandle(mRemoteEnrollmentDevice,
                    mEncryptionKey.encryptData(Utils.longToBytes(handle)));
        } else {
            removeEscrowToken(handle, uid);
        }
    }

    void onEnrollmentAdvertiseStartSuccess() {
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onEnrollmentAdvertisingStarted();
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    void onEnrollmentAdvertiseStartFailure() {
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onEnrollmentAdvertisingFailed();
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    void onRemoteDeviceConnected(BluetoothDevice device) {
        resetEncryptionState();

        addEnrollmentServiceLog("onRemoteDeviceConnected (addr:" + device.getAddress() + ")");
        synchronized (mRemoteDeviceLock) {
            mRemoteEnrollmentDevice = device;
        }
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onBleEnrollmentDeviceConnected(device);
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    void onRemoteDeviceDisconnected(BluetoothDevice device) {
        resetEncryptionState();

        addEnrollmentServiceLog("onRemoteDeviceDisconnected (addr:" + device.getAddress() + ")");
        synchronized (mRemoteDeviceLock) {
            mRemoteEnrollmentDevice = null;
        }
        for (BleStateChangeClient client : mBleStateChangeClients) {
            try {
                client.mListener.onBleEnrollmentDeviceDisconnected(device);
            } catch (RemoteException e) {
                Log.e(TAG, "onAdvertiseSuccess dispatch failed", e);
            }
        }
    }

    void onEnrollmentDataReceived(byte[] value) {
        if (mEnrollmentDelegate == null) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Enrollment Delegate not set");
            }
            return;
        }

        // The phone is not expected to send any data until the user has accepted the
        // pairing.
        if (mEnrollmentHandshakeAccepted) {
            notifyEscrowTokenReceived(value);
            return;
        }

        // Otherwise, the message should be one that continues the encryption handshake.
        try {
            processInitEncryptionMessage(value);
        } catch (HandshakeException e) {
            Log.e(TAG, "HandshakeException during set up of encryption: ", e);
        }
    }

    void onDeviceNameRetrieved(String deviceName) {
        mDeviceName = deviceName;
    }

    private void notifyEscrowTokenReceived(byte[] token) {
        try {
            mEnrollmentDelegate.addEscrowToken(
                    mEncryptionKey.decryptData(token), ActivityManager.getCurrentUser());
        } catch (SignatureException e) {
            Log.e(TAG, "Could not decrypt escrow token", e);
        }
    }

    /**
     * Processes the given message as one that will establish encryption for secure communication.
     *
     * <p>This method should be called continually until {@link #mEnrollmentHandshakeAccepted} is
     * {@code true}, meaning an secure channel has been set up.
     *
     * @param message The message received from the connected device.
     * @throws HandshakeException If an error was encountered during the handshake flow.
     */
    private void processInitEncryptionMessage(byte[] message) throws HandshakeException {
        switch (mEncryptionState) {
            case HandshakeState.UNKNOWN:
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Responding to handshake init request.");
                }

                mHandshakeMessage = mEncryptionRunner.respondToInitRequest(message);
                mEncryptionState = mHandshakeMessage.getHandshakeState();
                mCarTrustAgentBleManager.sendEncryptionHandshakeMessage(
                        mRemoteEnrollmentDevice, mHandshakeMessage.getNextMessage());

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Updated encryption state: " + mEncryptionState);
                }
                break;

            case HandshakeState.IN_PROGRESS:
                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Continuing handshake.");
                }

                mHandshakeMessage = mEncryptionRunner.continueHandshake(message);
                mEncryptionState = mHandshakeMessage.getHandshakeState();

                if (Log.isLoggable(TAG, Log.DEBUG)) {
                    Log.d(TAG, "Updated encryption state: " + mEncryptionState);
                }

                // The state is updated after a call to continueHandshake(). Thus, need to check
                // if we're in the next stage.
                if (mEncryptionState == HandshakeState.VERIFICATION_NEEDED) {
                    showVerificationCode();
                    return;
                }

                mCarTrustAgentBleManager.sendEncryptionHandshakeMessage(
                        mRemoteEnrollmentDevice, mHandshakeMessage.getNextMessage());
                break;
            case HandshakeState.VERIFICATION_NEEDED:
                Log.w(TAG, "Encountered VERIFICATION_NEEDED state when it should have been "
                        + "transitioned to after IN_PROGRESS.");
                // This case should never happen because this state should occur right after
                // a call to "continueHandshake". But just in case, call the appropriate method.
                showVerificationCode();
                break;

            case HandshakeState.FINISHED:
                // Should never reach this case since this state should occur after a verification
                // code has been accepted. But it should mean handshake is done and the message
                // is one for the escrow token.
                notifyEscrowTokenReceived(message);
                break;

            default:
                Log.w(TAG, "Encountered invalid handshake state: " + mEncryptionState);
                break;
        }
    }

    private void showVerificationCode() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "showVerificationCode(): " + mHandshakeMessage.getVerificationCode());
        }

        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onAuthStringAvailable(mRemoteEnrollmentDevice,
                        mHandshakeMessage.getVerificationCode());
            } catch (RemoteException e) {
                Log.e(TAG, "Broadcast verification code failed", e);
            }
        }
    }

    /**
     * Resets the encryption status of this service.
     *
     * <p>This method should be called each time a device connects so that a new handshake can be
     * started and encryption keys exchanged.
     */
    private void resetEncryptionState() {
        setEnrollmentHandshakeAccepted(false);

        mEncryptionRunner = EncryptionRunnerFactory.newRunner();
        mHandshakeMessage = null;
        mEncryptionKey = null;
        mEncryptionState = HandshakeState.UNKNOWN;
    }

    private synchronized void setEnrollmentHandshakeAccepted(boolean accepted) {
        mEnrollmentHandshakeAccepted = accepted;

        if (!accepted) {
            return;
        }

        if (mEncryptionRunner == null) {
            Log.e(TAG, "Received notification that enrollment handshake was accepted, "
                    + "but encryption was never set up.");
            return;
        }

        HandshakeMessage message;
        try {
            message = mEncryptionRunner.verifyPin();
        } catch (HandshakeException e) {
            Log.e(TAG, "Error during PIN verification", e);
            return;
        }

        if (message.getHandshakeState() != HandshakeState.FINISHED) {
            Log.e(TAG, "Handshake not finished after calling verify PIN. Instead got state: "
                    + message.getHandshakeState());
            return;
        }

        mEncryptionState = HandshakeState.FINISHED;
        mEncryptionKey = message.getKey();

        // TODO(ajchen): Save the key to a secure database.
    }

    /**
     * Iterates through the list of registered Enrollment State Change clients -
     * {@link EnrollmentStateClient} and finds if the given client is already registered.
     *
     * @param listener Listener to look for.
     * @return the {@link EnrollmentStateClient} if found, null if not
     */
    @Nullable
    private EnrollmentStateClient findEnrollmentStateClientLocked(
            ICarTrustAgentEnrollmentCallback listener) {
        IBinder binder = listener.asBinder();
        // Find the listener by comparing the binder object they host.
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            if (client.isHoldingBinder(binder)) {
                return client;
            }
        }
        return null;
    }

    /**
     * Unregister the given Enrollment State Change listener
     *
     * @param listener client to unregister
     */
    @Override
    public synchronized void unregisterEnrollmentCallback(
            ICarTrustAgentEnrollmentCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }

        EnrollmentStateClient client = findEnrollmentStateClientLocked(listener);
        if (client == null) {
            Log.e(TAG, "unregisterEnrollmentCallback(): listener was not previously "
                    + "registered");
            return;
        }
        listener.asBinder().unlinkToDeath(client, 0);
        mEnrollmentStateClients.remove(client);
    }

    /**
     * Registers a {@link ICarTrustAgentBleCallback} to be notified for changes to the BLE state
     * changes.
     *
     * @param listener {@link ICarTrustAgentBleCallback}
     */
    @Override
    public synchronized void registerBleCallback(ICarTrustAgentBleCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }
        // If a new client is registering, create a new EnrollmentStateClient and add it to the list
        // of listening clients.
        BleStateChangeClient client = findBleStateClientLocked(listener);
        if (client == null) {
            client = new BleStateChangeClient(listener);
            try {
                listener.asBinder().linkToDeath(client, 0);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot link death recipient to binder " + e);
                return;
            }
            mBleStateChangeClients.add(client);
        }
    }

    /**
     * Iterates through the list of registered BLE State Change clients -
     * {@link BleStateChangeClient} and finds if the given client is already registered.
     *
     * @param listener Listener to look for.
     * @return the {@link BleStateChangeClient} if found, null if not
     */
    @Nullable
    private BleStateChangeClient findBleStateClientLocked(
            ICarTrustAgentBleCallback listener) {
        IBinder binder = listener.asBinder();
        // Find the listener by comparing the binder object they host.
        for (BleStateChangeClient client : mBleStateChangeClients) {
            if (client.isHoldingBinder(binder)) {
                return client;
            }
        }
        return null;
    }

    /**
     * Unregister the given BLE State Change listener
     *
     * @param listener client to unregister
     */
    @Override
    public synchronized void unregisterBleCallback(ICarTrustAgentBleCallback listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Listener is null");
        }

        BleStateChangeClient client = findBleStateClientLocked(listener);
        if (client == null) {
            Log.e(TAG, "unregisterBleCallback(): listener was not previously "
                    + "registered");
            return;
        }
        listener.asBinder().unlinkToDeath(client, 0);
        mBleStateChangeClients.remove(client);
    }

    /**
     * The interface that an enrollment delegate has to implement to add/remove escrow tokens.
     */
    interface CarTrustAgentEnrollmentRequestDelegate {
        /**
         * Add the given escrow token that was generated by the peer device that is being enrolled.
         *
         * @param token the 64 bit token
         * @param uid   user id
         */
        void addEscrowToken(byte[] token, int uid);

        /**
         * Remove the given escrow token.  This should be called when removing a trusted device.
         *
         * @param handle the 64 bit token
         * @param uid    user id
         */
        void removeEscrowToken(long handle, int uid);

        /**
         * Query if the token is active.  The result is asynchronously delivered through a callback
         * {@link CarTrustAgentEnrollmentService#onEscrowTokenActiveStateChanged(long, boolean,
         * int)}
         *
         * @param handle the 64 bit token
         * @param uid    user id
         */
        void isEscrowTokenActive(long handle, int uid);
    }

    void setEnrollmentRequestDelegate(CarTrustAgentEnrollmentRequestDelegate delegate) {
        mEnrollmentDelegate = delegate;
    }

    void dump(PrintWriter writer) {
        writer.println("*CarTrustAgentEnrollmentService*");
        writer.println("Enrollment Service Logs:");
        for (String log : mLogQueue) {
            writer.println("\t" + log);
        }
    }

    private void addEnrollmentServiceLog(String message) {
        if (mLogQueue.size() >= MAX_LOG_SIZE) {
            mLogQueue.remove();
        }
        mLogQueue.add(System.currentTimeMillis() + " : " + message);
    }

    private void dispatchEscrowTokenActiveStateChanged(long handle, boolean active) {
        for (EnrollmentStateClient client : mEnrollmentStateClients) {
            try {
                client.mListener.onEscrowTokenActiveStateChanged(handle, active);
            } catch (RemoteException e) {
                Log.e(TAG, "Cannot notify client of a Token Activation change: " + active);
            }
        }
    }

    /**
     * Class that holds onto client related information - listener interface, process that hosts the
     * binder object etc.
     * <p>
     * It also registers for death notifications of the host.
     */
    private class EnrollmentStateClient implements IBinder.DeathRecipient {
        private final IBinder mListenerBinder;
        private final ICarTrustAgentEnrollmentCallback mListener;

        EnrollmentStateClient(ICarTrustAgentEnrollmentCallback listener) {
            mListener = listener;
            mListenerBinder = listener.asBinder();
        }

        @Override
        public void binderDied() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Binder died " + mListenerBinder);
            }
            mListenerBinder.unlinkToDeath(this, 0);
            synchronized (CarTrustAgentEnrollmentService.this) {
                mEnrollmentStateClients.remove(this);
            }
        }

        /**
         * Returns if the given binder object matches to what this client info holds.
         * Used to check if the listener asking to be registered is already registered.
         *
         * @return true if matches, false if not
         */
        public boolean isHoldingBinder(IBinder binder) {
            return mListenerBinder == binder;
        }
    }

    private class BleStateChangeClient implements IBinder.DeathRecipient {
        private final IBinder mListenerBinder;
        private final ICarTrustAgentBleCallback mListener;

        BleStateChangeClient(ICarTrustAgentBleCallback listener) {
            mListener = listener;
            mListenerBinder = listener.asBinder();
        }

        @Override
        public void binderDied() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Binder died " + mListenerBinder);
            }
            mListenerBinder.unlinkToDeath(this, 0);
            synchronized (CarTrustAgentEnrollmentService.this) {
                mBleStateChangeClients.remove(this);
            }
        }

        /**
         * Returns if the given binder object matches to what this client info holds.
         * Used to check if the listener asking to be registered is already registered.
         *
         * @return true if matches, false if not
         */
        public boolean isHoldingBinder(IBinder binder) {
            return mListenerBinder == binder;
        }

        public void onEnrollmentAdvertisementStarted() {
            try {
                mListener.onEnrollmentAdvertisingStarted();
            } catch (RemoteException e) {
                Log.e(TAG, "onEnrollmentAdvertisementStarted() failed", e);
            }
        }
    }
}
