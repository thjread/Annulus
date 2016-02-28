/*
 * Copyright (C) 2014 The Android Open Source Project
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

package thjread.annulus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.CapabilityApi;
import com.google.android.gms.wearable.CapabilityInfo;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class Annulus extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<Annulus.Engine> mWeakReference;

        public EngineHandler(Annulus.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            Annulus.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener, MessageApi.MessageListener {
        public final String TAG = "thjread.annulus";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHandPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        Resources mRes;

        static final int grid_size = 8;
        static final float hour_length = 4.f;
        static final float minute_length = 6.f;
        static final float second_length = 7.f;

        static final float major_tic_start = 6f;
        static final float major_tic_end = 7.5f;
        static final float minor_tic_start = 7.f;
        static final float minor_tic_end = 7.5f;

        static final float circle_size = 0.3f;

        boolean mIsRound;
        int mChinSize;

        boolean mApiConnected = false;
        GoogleApiClient mGoogleApiClient;

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);
            mIsRound = insets.isRound();
            mChinSize = insets.getSystemWindowInsetBottom();
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(Annulus.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setAmbientPeekMode(WatchFaceStyle.AMBIENT_PEEK_MODE_HIDDEN)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            mRes = Annulus.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mRes.getColor(R.color.background));

            mHandPaint = new Paint();
            mHandPaint.setColor(mRes.getColor(R.color.analog_hands));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(Annulus.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            checkBackgroundUpdate();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                /*if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }*///TODO
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    //mBackgroundPaint.setColor(mRes.getColor(mTapCount % 2 == 0 ?
                            //R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mCalendar.setTimeInMillis(System.currentTimeMillis());

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centreX = bounds.width() / 2f;
            float centreY = bounds.height() / 2f;
            float grid = centreX / grid_size;

            int seconds = mCalendar.get(Calendar.SECOND);
            float secRot = (seconds / 30f) * (float) Math.PI;
            int minutes = mCalendar.get(Calendar.MINUTE);
            float minRot;
            if (!mAmbient) {
                minRot = ((minutes + (seconds / 60f)) / 30f) * (float) Math.PI;
            } else {
                minRot = (minutes / 30f) * (float) Math.PI;
            }
            int hours = mCalendar.get(Calendar.HOUR);
            float hrRot = ((hours + (minutes / 60f)) / 6f) * (float) Math.PI;

            float secLength = second_length * grid;
            float minLength = minute_length * grid;
            float hrLength = hour_length * grid;

            mHandPaint.setStyle(Paint.Style.STROKE);

            if (!mAmbient) {
                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.second_thickenss));
                canvas.drawLine(centreX, centreY, centreX + secX, centreY + secY, mHandPaint);
            }

            float minX = (float) Math.sin(minRot) * minLength;
            float minY = (float) -Math.cos(minRot) * minLength;
            mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.minute_thickenss));
            canvas.drawLine(centreX, centreY, centreX + minX, centreY + minY, mHandPaint);

            float hrX = (float) Math.sin(hrRot) * hrLength;
            float hrY = (float) -Math.cos(hrRot) * hrLength;
            mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.hour_thickenss));
            canvas.drawLine(centreX, centreY, centreX + hrX, centreY + hrY, mHandPaint);

            mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.major_tic_thickenss));
            for (int i=0; i<12; ++i) {
                double ticRot = i/6.f * Math.PI;
                float ticX = (float) Math.sin(ticRot) * major_tic_start * grid;
                float ticY = (float) -Math.cos(ticRot) * major_tic_start * grid;
                float ticXEnd = (float) Math.sin(ticRot) * major_tic_end * grid;
                float ticYEnd = (float) -Math.cos(ticRot) * major_tic_end * grid;
                if (ticYEnd+centreY > bounds.height()-mChinSize) {
                    ticY -= (ticYEnd+centreY - (bounds.height()-mChinSize))/2;
                    ticYEnd = bounds.height()-mChinSize-centreY;
                }
                canvas.drawLine(centreX + ticX, centreY + ticY, centreX + ticXEnd, centreY + ticYEnd, mHandPaint);
            }

            mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.minor_tic_thickenss));
            for (int i=0; i<60; ++i) {
                if (i%5 == 0) {
                    continue;
                }
                double ticRot = i/30.f * Math.PI;
                float ticX = (float) Math.sin(ticRot) * minor_tic_start * grid;
                float ticY = (float) -Math.cos(ticRot) * minor_tic_start * grid;
                float ticXEnd = (float) Math.sin(ticRot) * minor_tic_end * grid;
                float ticYEnd = (float) -Math.cos(ticRot) * minor_tic_end * grid;
                if (ticYEnd+centreY > bounds.height()-mChinSize) {
                    float height = ticYEnd - ticY;
                    ticYEnd = bounds.height()-mChinSize-centreY;
                    ticY = ticYEnd - height*0.8f;
                }
                canvas.drawLine(centreX + ticX, centreY + ticY, centreX + ticXEnd, centreY + ticYEnd, mHandPaint);
            }

            mHandPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centreX, centreY, grid*circle_size, mHandPaint);

            /*mHandPaint.setStyle(Paint.Style.STROKE);
            for (int i=0; i<grid; ++i) {
                canvas.drawCircle(centreX, centreY, grid * i, mHandPaint);
            }*/

            class TempPoint {
                public float temperature;
                public float rot;
            }

            if (weatherData != null && weatherData.hourly != null) {//TODO move to update function
                List<TempPoint> temperatures = new ArrayList<>();

                Float minTemp = null; Float maxTemp = null;

                for (WeatherService.Datum d : weatherData.hourly.data) {
                    long time = d.time;
                    time *= 1000;
                    mCalendar.setTimeInMillis(time);
                    minutes = mCalendar.get(Calendar.MINUTE);
                    hours = mCalendar.get(Calendar.HOUR);

                    float dataRot = ((hours + (minutes / 60f)) / 6f) * (float) Math.PI;

                    if (d.temperature == null) {
                        continue;
                    }
                    if (mCalendar.getTimeInMillis() - System.currentTimeMillis() > DateUtils.HOUR_IN_MILLIS * 11.1) {
                        break;
                    }

                    TempPoint p = new TempPoint();
                    double t = d.temperature;
                    p.temperature = (float) t;
                    p.rot = dataRot;

                    temperatures.add(p);

                    if (minTemp == null || p.temperature < minTemp) {
                        minTemp = p.temperature;
                    }
                    if (maxTemp == null || p.temperature > maxTemp) {
                        maxTemp = p.temperature;
                    }
                }

                for (TempPoint p: temperatures) {
                    float dataLen = p.temperature;
                    dataLen -= minTemp;
                    dataLen *= grid*5/(maxTemp-minTemp);
                    dataLen += grid*1;

                    float dataX = (float) Math.sin(p.rot) * dataLen;
                    float dataY = (float) -Math.cos(p.rot) * dataLen;
                    canvas.drawCircle(centreX + dataX, centreY + dataY, 2.0f, mHandPaint);
                }
            }
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                mGoogleApiClient.connect();
                invalidate();
            } else {
                if (mGoogleApiClient.isConnected()) {
                    Wearable.MessageApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            Annulus.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            Annulus.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                checkBackgroundUpdate();
            }
        }

        private long lastBackgroundUpdate = 0;

        private void checkBackgroundUpdate() {
            mCalendar.setTimeInMillis(System.currentTimeMillis());

            if (mCalendar.getTimeInMillis()-lastBackgroundUpdate > DateUtils.MINUTE_IN_MILLIS*3
                    && mCalendar.get(Calendar.MINUTE)%5 <= 1) {
                backgroundUpdate();
            }
        }

        private void backgroundUpdate() {
            lastBackgroundUpdate = System.currentTimeMillis();

            byte[] data = {};

            Log.d(TAG, "Background update");

            if (mWeatherNodeId != null && mApiConnected && !mAmbient) {
                Wearable.MessageApi.sendMessage(mGoogleApiClient, mWeatherNodeId,
                        WEATHER_PATH, data).setResultCallback(
                        new ResultCallback<MessageApi.SendMessageResult>() {
                            @Override
                            public void onResult(@NonNull MessageApi.SendMessageResult result) {
                            }
                        }
                );
            }
        }

        private static final String WEATHER_CAPABILITY_NAME = "annulus_weather_data";
        private static final String WEATHER_PATH = "/annulus_weather_data";

        @Override
        public void onConnected(Bundle connectionHint) {
            mApiConnected = true;
            Wearable.MessageApi.addListener(mGoogleApiClient, this);

            Wearable.CapabilityApi.getCapability(mGoogleApiClient, WEATHER_CAPABILITY_NAME,
                    CapabilityApi.FILTER_REACHABLE).setResultCallback(
                    new ResultCallback<CapabilityApi.GetCapabilityResult>() {
                        @Override
                        public void onResult(@NonNull CapabilityApi.GetCapabilityResult getCapabilityResult) {
                            updateWeatherCapability(getCapabilityResult.getCapability());
                        }
                    }
            );

            CapabilityApi.CapabilityListener capabilityListener =
                    new CapabilityApi.CapabilityListener() {
                        @Override
                        public void onCapabilityChanged(CapabilityInfo capabilityInfo) {
                            updateWeatherCapability(capabilityInfo);
                        }
                    };
            Wearable.CapabilityApi.addCapabilityListener(
                    mGoogleApiClient,
                    capabilityListener,
                    WEATHER_CAPABILITY_NAME);
        }

        private String mWeatherNodeId = null;

        private void updateWeatherCapability(CapabilityInfo capabilityInfo) {
            Set<Node> connectedNodes = capabilityInfo.getNodes();
            mWeatherNodeId = pickBestNodeId(connectedNodes);
            backgroundUpdate();
        }

        private String pickBestNodeId(Set<Node> nodes) {
            String bestNodeId = null;
            // Find a nearby node or pick one arbitrarily
            for (Node node : nodes) {
                if (node.isNearby()) {
                    return node.getId();
                }
                bestNodeId = node.getId();
            }
            return bestNodeId;
        }

        private Object convertFromBytes(byte[] bytes) throws IOException, ClassNotFoundException {
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
                 ObjectInput in = new ObjectInputStream(bis)) {
                return in.readObject();
            }
        }

        WeatherService.WeatherData weatherData = null;

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            byte[] d = messageEvent.getData();
            WeatherService.WeatherData data = null;
            try {
                data = (WeatherService.WeatherData) convertFromBytes(d);
            } catch (IOException e) {
                Log.e(TAG, "Weather data conversion from bytes failed");
            } catch (ClassNotFoundException e) {

            }

            if (data != null) {
                Log.d(TAG, "Weather data received");
                weatherData = data;
            }
        }

        public void onConnectionSuspended(int cause) {
            mApiConnected = false;
        }

        public void onConnectionFailed(@NonNull ConnectionResult cause) {
            mApiConnected = false;
        }

        /*private class WeatherDatapoint {
            Integer time;
            Double temperature;
            Double cloudCover;
            Double precipProbability;
            Double precipIntensity;
        }

        List<WeatherDatapoint> weatherData = null;

        private void processWeatherData(WeatherService.WeatherData data) {
            rawData = data;
            weatherData = new ArrayList<>();

            if (data.hourly != null && data.hourly.data != null) {
                for (int i=data.hourly.data.size()-1; i>=0; --i) {
                    if (!weatherData.isEmpty()) {
                        weatherData.add(toDatapoint(data.hourly.data.get(i), weatherData.get(weatherData.size() - 1)));
                    } else {
                        weatherData.add(toDatapoint(data.hourly.data.get(i), null));
                    }
                }
            }

            if (data.minutely != null && data.minutely.data != null) {
                for (int i=data.minutely.data.size()-1; i>=0; --i) {
                    if (!weatherData.isEmpty()) {
                        weatherData.add(toDatapoint(data.minutely.data.get(i), weatherData.get(weatherData.size() - 1)));
                    } else {
                        weatherData.add(toDatapoint(data.minutely.data.get(i), null));
                    }
                }
            }

            if (data.currently != null) {
                if (!weatherData.isEmpty()) {
                    weatherData.add(toDatapoint(data.currently, weatherData.get(weatherData.size() - 1)));
                } else {
                    weatherData.add(toDatapoint(data.currently, null));
                }
            }
        }

        private WeatherDatapoint toDatapoint(WeatherService.Datum datum, WeatherDatapoint backup) {
            WeatherDatapoint datapoint = new WeatherDatapoint();

            datapoint.time = datum.time;

            if (datum.temperature != null) {
                datapoint.temperature = datum.temperature;
            } else if (backup != null) {
                datapoint.temperature = backup.temperature;
            }
            if (datum.cloudCover != null) {
                datapoint.cloudCover = datum.cloudCover;
            } else if (backup != null) {
                datapoint.cloudCover = backup.cloudCover;
            }
            if (datum.precipIntensity != null) {
                datapoint.precipIntensity = datum.precipIntensity;
            } else if (backup != null) {
                datapoint.precipIntensity = backup.precipIntensity;
            }
            if (datum.precipProbability != null) {
                datapoint.precipProbability = datum.precipProbability;
            } else if (backup != null) {
                datapoint.precipProbability = backup.precipProbability;
            }

            return datapoint;
        }*/
    }
}
