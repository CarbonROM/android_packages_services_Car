/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.car.cluster.sample;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.car.cluster.navigation.Destination;
import androidx.car.cluster.navigation.Destination.Traffic;
import androidx.car.cluster.navigation.Distance;
import androidx.car.cluster.navigation.Maneuver;
import androidx.car.cluster.navigation.NavigationState;
import androidx.car.cluster.navigation.Segment;
import androidx.car.cluster.navigation.Step;

import java.time.Duration;
import java.time.ZonedDateTime;

/**
 * View controller for navigation state rendering.
 */
public class NavStateController {
    private static final String TAG = "Cluster.NavController";

    private ImageView mManeuver;
    private LaneView mLane;
    private TextView mDistance;
    private TextView mSegment;
    private TextView mEta;
    private CueView mCue;
    private Context mContext;
    private ImageResolver mImageResolver;

    /**
     * Creates a controller to coordinate updates to the views displaying navigation state
     * data.
     *
     * @param container {@link View} containing the navigation state views
     */
    public NavStateController(View container) {
        mManeuver = container.findViewById(R.id.maneuver);
        mLane = container.findViewById(R.id.lane);
        mDistance = container.findViewById(R.id.distance);
        mSegment = container.findViewById(R.id.segment);
        mEta = container.findViewById(R.id.eta);
        mCue = container.findViewById(R.id.cue);

        mContext = container.getContext();
    }

    public void setImageResolver(@Nullable ImageResolver imageResolver) {
        mImageResolver = imageResolver;
    }

    /**
     * Updates views to reflect the provided navigation state
     */
    public void update(@Nullable NavigationState state) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Updating nav state: " + state);
        }
        Step step = state != null && state.getSteps().size() > 0 ? state.getSteps().get(0) : null;
        Destination destination = state != null && !state.getDestinations().isEmpty()
                ? state.getDestinations().get(0) : null;
        ZonedDateTime eta = destination != null ? destination.getEta() : null;
        Traffic traffic = destination != null ? destination.getTraffic() : null;

        mEta.setText(eta != null ? formatEta(eta) : null);
        mEta.setTextColor(getTrafficColor(traffic));
        mManeuver.setImageDrawable(getManeuverIcon(step != null ? step.getManeuver() : null));
        mDistance.setText(formatDistance(step != null ? step.getDistance() : null));
        mSegment.setText(state != null ? getSegmentString(state.getCurrentSegment()) : null);
        mCue.setRichText(step != null ? step.getCue() : null, mImageResolver);

        if (step != null && step.getLanes().size() > 0) {
            mLane.setLanes(step.getLanes());
            mLane.setVisibility(View.VISIBLE);
        } else {
            mLane.setVisibility(View.GONE);
        }
    }


    private int getTrafficColor(@Nullable Traffic traffic) {
        if (traffic == Traffic.LOW) {
            return mContext.getColor(R.color.low_traffic);
        } else if (traffic == Traffic.MEDIUM) {
            return mContext.getColor(R.color.medium_traffic);
        } else if (traffic == Traffic.HIGH) {
            return mContext.getColor(R.color.high_traffic);
        }

        return mContext.getColor(R.color.unknown_traffic);
    }

    private String formatEta(@Nullable ZonedDateTime eta) {
        ZonedDateTime now = ZonedDateTime.now();
        Duration duration = Duration.between(now, eta);
        long seconds = duration.getSeconds();

        // TODO: move formatting into common lib somewhere
        long minutes = (seconds / 60) % 60;
        long hours = (seconds / 3600) % 24;
        long days = seconds / (3600 * 24);

        if (days > 0) {
            return String.format("%d d %d hr", days, hours);
        } else if (hours > 0) {
            return String.format("%d hr %d min", hours, minutes);
        } else {
            return String.format("%d min", minutes);
        }
    }

    private String getSegmentString(Segment segment) {
        if (segment != null) {
            return segment.getName();
        }

        return null;
    }

    private Drawable getManeuverIcon(@Nullable Maneuver maneuver) {
        if (maneuver == null) {
            return null;
        }
        switch (maneuver.getType()) {
            case UNKNOWN:
                return null;
            case DEPART:
                return mContext.getDrawable(R.drawable.direction_depart);
            case NAME_CHANGE:
                return mContext.getDrawable(R.drawable.direction_new_name_straight);
            case KEEP_LEFT:
                return mContext.getDrawable(R.drawable.direction_continue_left);
            case KEEP_RIGHT:
                return mContext.getDrawable(R.drawable.direction_continue_right);
            case TURN_SLIGHT_LEFT:
                return mContext.getDrawable(R.drawable.direction_turn_slight_left);
            case TURN_SLIGHT_RIGHT:
                return mContext.getDrawable(R.drawable.direction_turn_slight_right);
            case TURN_NORMAL_LEFT:
                return mContext.getDrawable(R.drawable.direction_turn_left);
            case TURN_NORMAL_RIGHT:
                return mContext.getDrawable(R.drawable.direction_turn_right);
            case TURN_SHARP_LEFT:
                return mContext.getDrawable(R.drawable.direction_turn_sharp_left);
            case TURN_SHARP_RIGHT:
                return mContext.getDrawable(R.drawable.direction_turn_sharp_right);
            case U_TURN_LEFT:
                return mContext.getDrawable(R.drawable.direction_uturn);
            case U_TURN_RIGHT:
                return mContext.getDrawable(R.drawable.direction_uturn_right);
            case ON_RAMP_SLIGHT_LEFT:
                return mContext.getDrawable(R.drawable.direction_on_ramp_slight_left);
            case ON_RAMP_SLIGHT_RIGHT:
                return mContext.getDrawable(R.drawable.direction_on_ramp_slight_right);
            case ON_RAMP_NORMAL_LEFT:
                return mContext.getDrawable(R.drawable.direction_on_ramp_left);
            case ON_RAMP_NORMAL_RIGHT:
                return mContext.getDrawable(R.drawable.direction_on_ramp_right);
            case ON_RAMP_SHARP_LEFT:
                return mContext.getDrawable(R.drawable.direction_on_ramp_sharp_left);
            case ON_RAMP_SHARP_RIGHT:
                return mContext.getDrawable(R.drawable.direction_on_ramp_sharp_right);
            case ON_RAMP_U_TURN_LEFT:
                return mContext.getDrawable(R.drawable.direction_uturn);
            case ON_RAMP_U_TURN_RIGHT:
                return mContext.getDrawable(R.drawable.direction_uturn_right);
            case OFF_RAMP_SLIGHT_LEFT:
                return mContext.getDrawable(R.drawable.direction_off_ramp_slight_left);
            case OFF_RAMP_SLIGHT_RIGHT:
                return mContext.getDrawable(R.drawable.direction_off_ramp_slight_right);
            case OFF_RAMP_NORMAL_LEFT:
                return mContext.getDrawable(R.drawable.direction_off_ramp_left);
            case OFF_RAMP_NORMAL_RIGHT:
                return mContext.getDrawable(R.drawable.direction_off_ramp_right);
            case FORK_LEFT:
                return mContext.getDrawable(R.drawable.direction_fork_left);
            case FORK_RIGHT:
                return mContext.getDrawable(R.drawable.direction_fork_right);
            case MERGE_LEFT:
                return mContext.getDrawable(R.drawable.direction_merge_left);
            case MERGE_RIGHT:
                return mContext.getDrawable(R.drawable.direction_merge_right);
            case ROUNDABOUT_ENTER:
                return mContext.getDrawable(R.drawable.direction_roundabout);
            case ROUNDABOUT_EXIT:
                return mContext.getDrawable(R.drawable.direction_roundabout);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_RIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_sharp_right);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_RIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_right);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_RIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_slight_right);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_STRAIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_straight);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_LEFT:
                return mContext.getDrawable(R.drawable.direction_roundabout_sharp_left);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_LEFT:
                return mContext.getDrawable(R.drawable.direction_roundabout_left);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_LEFT:
                return mContext.getDrawable(R.drawable.direction_roundabout_slight_left);
            case ROUNDABOUT_ENTER_AND_EXIT_CW_U_TURN:
                return mContext.getDrawable(R.drawable.direction_uturn_right);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_RIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_sharp_right);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_RIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_right);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_RIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_slight_right);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_STRAIGHT:
                return mContext.getDrawable(R.drawable.direction_roundabout_straight);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_LEFT:
                return mContext.getDrawable(R.drawable.direction_roundabout_sharp_left);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_LEFT:
                return mContext.getDrawable(R.drawable.direction_roundabout_left);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_LEFT:
                return mContext.getDrawable(R.drawable.direction_roundabout_slight_left);
            case ROUNDABOUT_ENTER_AND_EXIT_CCW_U_TURN:
                return mContext.getDrawable(R.drawable.direction_uturn);
            case STRAIGHT:
                return mContext.getDrawable(R.drawable.direction_continue);
            case FERRY_BOAT:
                return mContext.getDrawable(R.drawable.direction_close);
            case FERRY_TRAIN:
                return mContext.getDrawable(R.drawable.direction_close);
            case DESTINATION:
                return mContext.getDrawable(R.drawable.direction_arrive);
            case DESTINATION_STRAIGHT:
                return mContext.getDrawable(R.drawable.direction_arrive_straight);
            case DESTINATION_LEFT:
                return mContext.getDrawable(R.drawable.direction_arrive_left);
            case DESTINATION_RIGHT:
                return mContext.getDrawable(R.drawable.direction_arrive_right);
        }
        return null;
    }

    private String formatDistance(@Nullable Distance distance) {
        if (distance == null || distance.getDisplayUnit() == Distance.Unit.UNKNOWN) {
            return null;
        }

        String unit = "";

        switch(distance.getDisplayUnit()){
            case METERS:
                unit = "m";
                break;
            case KILOMETERS:
                unit = "km";
                break;
            case MILES:
                unit = "mi";
                break;
            case YARDS:
                unit = "yd";
                break;
            case FEET:
                unit = "ft";
                break;
        }
        return String.format("In %s %s", distance.getDisplayValue(), unit);
    }
}
