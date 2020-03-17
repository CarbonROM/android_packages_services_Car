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

import static org.testng.Assert.assertThrows;

import android.car.Car;
import android.car.VehicleAreaType;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarInternalErrorException;
import android.car.hardware.property.CarPropertyManager;
import android.car.hardware.property.PropertyAccessDeniedSecurityException;
import android.car.hardware.property.PropertyNotAvailableAndRetryException;
import android.car.hardware.property.PropertyNotAvailableException;
import android.car.hardware.property.VehicleHalStatusCode;
import android.hardware.automotive.vehicle.V2_0.VehicleArea;
import android.hardware.automotive.vehicle.V2_0.VehicleAreaSeat;
import android.hardware.automotive.vehicle.V2_0.VehiclePropValue;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyGroup;
import android.hardware.automotive.vehicle.V2_0.VehiclePropertyType;
import android.os.Build;
import android.os.ServiceSpecificException;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;

import com.android.car.vehiclehal.test.MockedVehicleHal.VehicleHalPropertyHandler;

import com.google.common.truth.Truth;

import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 * Test for {@link android.car.hardware.property.CarPropertyManager}
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class CarPropertyManagerTest extends MockedCarTestBase {

    private static final String TAG = CarPropertyManagerTest.class.getSimpleName();

    /**
     * configArray[0], 1 indicates the property has a String value
     * configArray[1], 1 indicates the property has a Boolean value .
     * configArray[2], 1 indicates the property has a Integer value
     * configArray[3], the number indicates the size of Integer[]  in the property.
     * configArray[4], 1 indicates the property has a Long value .
     * configArray[5], the number indicates the size of Long[]  in the property.
     * configArray[6], 1 indicates the property has a Float value .
     * configArray[7], the number indicates the size of Float[] in the property.
     * configArray[8], the number indicates the size of byte[] in the property.
     */
    private static final java.util.Collection<Integer> CONFIG_ARRAY_1 =
            Arrays.asList(1, 0, 1, 0, 1, 0, 0, 0, 0);
    private static final java.util.Collection<Integer> CONFIG_ARRAY_2 =
            Arrays.asList(1, 1, 1, 0, 0, 0, 0, 2, 0);
    private static final Object[] EXPECTED_VALUE_1 = {"android", 1, 1L};
    private static final Object[] EXPECTED_VALUE_2 = {"android", true, 3, 1.1f, 2f};

    private static final int CUSTOM_GLOBAL_MIXED_PROP_ID_1 =
            0x1101 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.MIXED | VehicleArea.SEAT;
    private static final int CUSTOM_GLOBAL_MIXED_PROP_ID_2 =
            0x1102 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.MIXED | VehicleArea.GLOBAL;

    // Vendor properties for testing exceptions.
    private static final int PROP_CAUSE_STATUS_CODE_TRY_AGAIN =
            0x1201 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.INT32 | VehicleArea.GLOBAL;
    private static final int PROP_CAUSE_STATUS_CODE_INVALID_ARG =
            0x1202 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.INT32 | VehicleArea.GLOBAL;
    private static final int PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE =
            0x1203 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.INT32 | VehicleArea.GLOBAL;
    private static final int PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR =
            0x1204 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.INT32 | VehicleArea.GLOBAL;
    private static final int PROP_CAUSE_STATUS_CODE_ACCESS_DENIED =
            0x1205 | VehiclePropertyGroup.VENDOR | VehiclePropertyType.INT32 | VehicleArea.GLOBAL;

    // Use FAKE_PROPERTY_ID to test api return null or throw exception.
    private static final int FAKE_PROPERTY_ID = 0x111;

    private static final int DRIVER_SIDE_AREA_ID = VehicleAreaSeat.ROW_1_LEFT
                                                    | VehicleAreaSeat.ROW_2_LEFT;
    private static final int PASSENGER_SIDE_AREA_ID = VehicleAreaSeat.ROW_1_RIGHT
                                                    | VehicleAreaSeat.ROW_2_CENTER
                                                    | VehicleAreaSeat.ROW_2_RIGHT;
    private static final float INIT_TEMP_VALUE = 16f;
    private static final float CHANGED_TEMP_VALUE = 20f;

    private CarPropertyManager mManager;

    @Rule public TestName mTestName = new TestName();

    @Override
    public void setUp() throws Exception {
        super.setUp();
        setUpTargetSdk();
        mManager = (CarPropertyManager) getCar().getCarManager(Car.PROPERTY_SERVICE);
        Assert.assertNotNull(mManager);
    }

    private void setUpTargetSdk() {
        if (mTestName.getMethodName().endsWith("InQ")) {
            getContext().getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.Q;
        } else if (mTestName.getMethodName().endsWith("InR")) {
            getContext().getApplicationInfo().targetSdkVersion = Build.VERSION_CODES.R;
        }
    }

    @Test
    public void testMixedPropertyConfigs() {
        List<CarPropertyConfig> configs = mManager.getPropertyList();

        for (CarPropertyConfig cfg : configs) {
            switch (cfg.getPropertyId()) {
                case CUSTOM_GLOBAL_MIXED_PROP_ID_1:
                    Assert.assertArrayEquals(CONFIG_ARRAY_1.toArray(),
                            cfg.getConfigArray().toArray());
                    break;
                case CUSTOM_GLOBAL_MIXED_PROP_ID_2:
                    Assert.assertArrayEquals(CONFIG_ARRAY_2.toArray(),
                            cfg.getConfigArray().toArray());
                    break;
                case VehiclePropertyIds.HVAC_TEMPERATURE_SET:
                case PROP_CAUSE_STATUS_CODE_ACCESS_DENIED:
                case PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR:
                case PROP_CAUSE_STATUS_CODE_TRY_AGAIN:
                case PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE:
                case PROP_CAUSE_STATUS_CODE_INVALID_ARG:
                    break;
                default:
                    Assert.fail("Unexpected CarPropertyConfig: " + cfg.toString());
            }
        }
    }

    @Test
    public void testGetMixTypeProperty() {
        mManager.setProperty(Object[].class, CUSTOM_GLOBAL_MIXED_PROP_ID_1,
                0, EXPECTED_VALUE_1);
        CarPropertyValue<Object[]> result = mManager.getProperty(
                CUSTOM_GLOBAL_MIXED_PROP_ID_1, 0);
        Assert.assertArrayEquals(EXPECTED_VALUE_1, result.getValue());

        mManager.setProperty(Object[].class, CUSTOM_GLOBAL_MIXED_PROP_ID_2,
                0, EXPECTED_VALUE_2);
        result = mManager.getProperty(
                CUSTOM_GLOBAL_MIXED_PROP_ID_2, 0);
        Assert.assertArrayEquals(EXPECTED_VALUE_2, result.getValue());
    }

    @Test
    public void testGetPropertyConfig() {
        CarPropertyConfig config = mManager.getCarPropertyConfig(CUSTOM_GLOBAL_MIXED_PROP_ID_1);
        Assert.assertEquals(CUSTOM_GLOBAL_MIXED_PROP_ID_1, config.getPropertyId());
        // return null if can not find the propertyConfig for the property.
        Assert.assertNull(mManager.getCarPropertyConfig(FAKE_PROPERTY_ID));
    }

    @Test
    public void testGetAreaId() {
        int result = mManager.getAreaId(CUSTOM_GLOBAL_MIXED_PROP_ID_1, VehicleAreaSeat.ROW_1_LEFT);
        Assert.assertEquals(DRIVER_SIDE_AREA_ID, result);

        //test for the GLOBAL property
        int globalAreaId =
                mManager.getAreaId(CUSTOM_GLOBAL_MIXED_PROP_ID_2, VehicleAreaSeat.ROW_1_LEFT);
        Assert.assertEquals(VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, globalAreaId);

        //test exception
        try {
            int areaId = mManager.getAreaId(CUSTOM_GLOBAL_MIXED_PROP_ID_1,
                    VehicleAreaSeat.ROW_3_CENTER);
            Assert.fail("Unexpected areaId: " + areaId);
        } catch (IllegalArgumentException e) {
            Log.v(TAG, e.getMessage());
        }

        try {
            // test exception
            int areaIdForFakeProp = mManager.getAreaId(FAKE_PROPERTY_ID,
                    VehicleAreaSeat.ROW_1_LEFT);
            Assert.fail("Unexpected areaId for fake property: " + areaIdForFakeProp);
        } catch (IllegalArgumentException e) {
            Log.v(TAG, e.getMessage());
        }
    }

    @Test
    public void testNotReceiveOnErrorEvent() {
        TestCallback callback = new TestCallback();
        mManager.registerCallback(callback, VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        injectErrorEvent(VehiclePropertyIds.HVAC_TEMPERATURE_SET, PASSENGER_SIDE_AREA_ID,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
        // app never change the value of HVAC_TEMPERATURE_SET, it won't get an error code.
        SystemClock.sleep(SHORT_WAIT_TIMEOUT_MS);
        Assert.assertFalse(callback.mReceivedErrorEventWithErrorCode);
        Assert.assertFalse(callback.mReceivedErrorEventWithOutErrorCode);
    }

    @Test
    public void testReceiveOnErrorEvent() {
        TestCallback callback = new TestCallback();
        mManager.registerCallback(callback, VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mManager.setFloatProperty(
                VehiclePropertyIds.HVAC_TEMPERATURE_SET, PASSENGER_SIDE_AREA_ID,
                CHANGED_TEMP_VALUE);
        injectErrorEvent(VehiclePropertyIds.HVAC_TEMPERATURE_SET, PASSENGER_SIDE_AREA_ID,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
        SystemClock.sleep(SHORT_WAIT_TIMEOUT_MS);
        Assert.assertTrue(callback.mReceivedErrorEventWithErrorCode);
        Assert.assertEquals(CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN,
                callback.mErrorCode);
        Assert.assertFalse(callback.mReceivedErrorEventWithOutErrorCode);
    }

    @Test
    public void testNotReceiveOnErrorEventAfterUnregister() {
        TestCallback callback1 = new TestCallback();
        TestCallback callback2 = new TestCallback();
        mManager.registerCallback(callback1, VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mManager.registerCallback(callback2, VehiclePropertyIds.HVAC_TEMPERATURE_SET,
                CarPropertyManager.SENSOR_RATE_ONCHANGE);
        mManager.setFloatProperty(
                VehiclePropertyIds.HVAC_TEMPERATURE_SET, PASSENGER_SIDE_AREA_ID,
                CHANGED_TEMP_VALUE);
        mManager.unregisterCallback(callback1, VehiclePropertyIds.HVAC_TEMPERATURE_SET);
        injectErrorEvent(VehiclePropertyIds.HVAC_TEMPERATURE_SET, PASSENGER_SIDE_AREA_ID,
                CarPropertyManager.CAR_SET_PROPERTY_ERROR_CODE_UNKNOWN);
        SystemClock.sleep(SHORT_WAIT_TIMEOUT_MS);
        Assert.assertFalse(callback1.mReceivedErrorEventWithErrorCode);
        Assert.assertFalse(callback1.mReceivedErrorEventWithOutErrorCode);
    }

    @Test
    public void testSetterExceptionsInQ() {
        Truth.assertThat(getContext().getApplicationInfo().targetSdkVersion)
                .isEqualTo(Build.VERSION_CODES.Q);

        assertThrows(IllegalStateException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_ACCESS_DENIED,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(IllegalStateException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(IllegalStateException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(IllegalArgumentException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_INVALID_ARG,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(RuntimeException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_ACCESS_DENIED,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
    }

    @Test
    public void testSetterExceptionsInR() {
        Truth.assertThat(getContext().getApplicationInfo().targetSdkVersion)
                .isEqualTo(Build.VERSION_CODES.R);

        assertThrows(PropertyAccessDeniedSecurityException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_ACCESS_DENIED,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(PropertyNotAvailableAndRetryException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_TRY_AGAIN,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(PropertyNotAvailableException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(CarInternalErrorException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
        assertThrows(IllegalArgumentException.class,
                ()->mManager.setProperty(Integer.class, PROP_CAUSE_STATUS_CODE_INVALID_ARG,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL, 1));
    }

    @Test
    public void testGetterExceptionsInQ() {
        Truth.assertThat(getContext().getApplicationInfo().targetSdkVersion)
                .isEqualTo(Build.VERSION_CODES.Q);

        assertThrows(IllegalStateException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_ACCESS_DENIED,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        assertThrows(IllegalArgumentException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_INVALID_ARG,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        assertThrows(IllegalStateException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        assertThrows(IllegalStateException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        Truth.assertThat(mManager.getProperty(PROP_CAUSE_STATUS_CODE_TRY_AGAIN,
                VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL)).isNull();
    }

    @Test
    public void testGetterExceptionsInR() {
        Truth.assertThat(getContext().getApplicationInfo().targetSdkVersion)
                .isEqualTo(Build.VERSION_CODES.R);

        assertThrows(PropertyAccessDeniedSecurityException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_ACCESS_DENIED,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        assertThrows(IllegalArgumentException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_INVALID_ARG,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        assertThrows(PropertyNotAvailableAndRetryException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_TRY_AGAIN,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        assertThrows(PropertyNotAvailableException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
        assertThrows(CarInternalErrorException.class,
                ()->mManager.getProperty(PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL));
    }

    @Override
    protected synchronized void configureMockedHal() {
        PropertyHandler handler = new PropertyHandler();
        addProperty(CUSTOM_GLOBAL_MIXED_PROP_ID_1, handler).setConfigArray(CONFIG_ARRAY_1)
                .addAreaConfig(DRIVER_SIDE_AREA_ID).addAreaConfig(PASSENGER_SIDE_AREA_ID);
        addProperty(CUSTOM_GLOBAL_MIXED_PROP_ID_2, handler).setConfigArray(CONFIG_ARRAY_2);

        VehiclePropValue tempValue = new VehiclePropValue();
        tempValue.value.floatValues.add(INIT_TEMP_VALUE);
        tempValue.prop = VehiclePropertyIds.HVAC_TEMPERATURE_SET;
        addProperty(VehiclePropertyIds.HVAC_TEMPERATURE_SET, tempValue)
                .addAreaConfig(DRIVER_SIDE_AREA_ID).addAreaConfig(PASSENGER_SIDE_AREA_ID);

        // Adds properties for testing exceptions.
        addProperty(PROP_CAUSE_STATUS_CODE_ACCESS_DENIED, handler);
        addProperty(PROP_CAUSE_STATUS_CODE_TRY_AGAIN, handler);
        addProperty(PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR, handler);
        addProperty(PROP_CAUSE_STATUS_CODE_INVALID_ARG, handler);
        addProperty(PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE, handler);
    }

    private class PropertyHandler implements VehicleHalPropertyHandler {
        HashMap<Integer, VehiclePropValue> mMap = new HashMap<>();
        @Override
        public synchronized void onPropertySet(VehiclePropValue value) {
            // Simulate HalClient.set() behavior.
            int statusCode = mapPropertyToStatusCode(value.prop);
            if (statusCode == VehicleHalStatusCode.STATUS_INVALID_ARG) {
                throw new IllegalArgumentException();
            }

            if (statusCode != VehicleHalStatusCode.STATUS_OK) {
                throw new ServiceSpecificException(statusCode);
            }

            mMap.put(value.prop, value);
        }

        @Override
        public synchronized VehiclePropValue onPropertyGet(VehiclePropValue value) {
            // Simulate HalClient.get() behavior.
            int statusCode = mapPropertyToStatusCode(value.prop);
            if (statusCode == VehicleHalStatusCode.STATUS_INVALID_ARG) {
                throw new IllegalArgumentException();
            }

            if (statusCode != VehicleHalStatusCode.STATUS_OK) {
                throw new ServiceSpecificException(statusCode);
            }

            VehiclePropValue currentValue = mMap.get(value.prop);
            return currentValue != null ? currentValue : value;
        }

        @Override
        public synchronized void onPropertySubscribe(int property, float sampleRate) {
            Log.d(TAG, "onPropertySubscribe property "
                    + property + " sampleRate " + sampleRate);
        }

        @Override
        public synchronized void onPropertyUnsubscribe(int property) {
            Log.d(TAG, "onPropertyUnSubscribe property " + property);
        }
    }

    private static int mapPropertyToStatusCode(int propId) {
        switch (propId) {
            case PROP_CAUSE_STATUS_CODE_TRY_AGAIN:
                return VehicleHalStatusCode.STATUS_TRY_AGAIN;
            case PROP_CAUSE_STATUS_CODE_NOT_AVAILABLE:
                return VehicleHalStatusCode.STATUS_NOT_AVAILABLE;
            case PROP_CAUSE_STATUS_CODE_ACCESS_DENIED:
                return VehicleHalStatusCode.STATUS_ACCESS_DENIED;
            case PROP_CAUSE_STATUS_CODE_INVALID_ARG:
                return VehicleHalStatusCode.STATUS_INVALID_ARG;
            case PROP_CAUSE_STATUS_CODE_INTERNAL_ERROR:
                return VehicleHalStatusCode.STATUS_INTERNAL_ERROR;
            default:
                return VehicleHalStatusCode.STATUS_OK;
        }
    }

    private static class TestCallback implements CarPropertyManager.CarPropertyEventCallback {

        private static final String CALLBACK_TAG = "ErrorEventTest";
        private boolean mReceivedErrorEventWithErrorCode = false;
        private boolean mReceivedErrorEventWithOutErrorCode = false;
        private int mErrorCode;
        @Override
        public void onChangeEvent(CarPropertyValue value) {
            Log.d(CALLBACK_TAG, "onChangeEvent: " + value);
        }

        @Override
        public void onErrorEvent(int propId, int zone) {
            mReceivedErrorEventWithOutErrorCode = true;
            Log.d(CALLBACK_TAG, "onErrorEvent, propId: " + propId + " zone: " + zone);
        }

        @Override
        public void onErrorEvent(int propId, int areaId, int errorCode) {
            mReceivedErrorEventWithErrorCode = true;
            mErrorCode = errorCode;
            Log.d(CALLBACK_TAG, "onErrorEvent, propId: " + propId + " areaId: " + areaId
                    + "errorCode: " + errorCode);
        }
    }
}
