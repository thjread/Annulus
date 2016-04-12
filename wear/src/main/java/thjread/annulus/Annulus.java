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
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

class CalendarData implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public String title;
    public long begin;
    public long end;
}

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

        static final float assumed_max_rain = 8.f;
        static final float max_rain_start = 3.5f;

        static final float day_weather_len = 3.f;
        static final float day_weather_len_max = 5.f;
        static final float day_weather_thick = 0.15f;

        final int rain_color = Color.rgb(100, 181, 246);
        final int dark_rain_color = Color.rgb(13, 71, 161);

        static final int sun_r = 255; static final int sun_g = 213; static final int sun_b = 79;

        static final float calendar_len = 6f;
        static final float calendar_thick = 0.15f;
        static final float first_text = 2f;
        static final float second_text = 4f;
        static final float text_size = 1.25f;

        final int calendar_colors[] = { Color.rgb(33, 150, 243), Color.rgb(171, 71, 188), Color.rgb(255, 87, 34) };
        final int calendar_colors_bright[] = { Color.rgb(144,202,249), Color.rgb(206,147,216), Color.rgb(255,171,145) };

        boolean mIsRound;
        int mChinSize;

        boolean rapid_update = false;

        boolean mApiConnected = false;
        GoogleApiClient mGoogleApiClient;

        boolean showCalendar = true;

        boolean wereEvents = false;

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

            mGoogleApiClient.connect();
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
                    backgroundUpdate();
                    showCalendar = !showCalendar;
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
            }

            long currentTime = System.currentTimeMillis();

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centreX = bounds.width() / 2f;
            float centreY = bounds.height() / 2f;
            float radius = centreX;
            float grid = centreX / grid_size;

            int seconds, minutes, hours;

            mCalendar.setTimeInMillis(currentTime);

            seconds = mCalendar.get(Calendar.SECOND);
            float secRot = (seconds / 30f) * (float) Math.PI;
            minutes = mCalendar.get(Calendar.MINUTE);
            float minRot;
            if (!mAmbient) {
                minRot = ((minutes + (seconds / 60f)) / 30f) * (float) Math.PI;
            } else {
                minRot = (minutes / 30f) * (float) Math.PI;
            }
            hours = mCalendar.get(Calendar.HOUR);
            float hrRot = ((hours + (minutes / 60f)) / 6f) * (float) Math.PI;


            float rainPrediction[] = new float[60];
            java.util.Arrays.fill(rainPrediction, 0);
            float rainProb[] = new float[60];
            java.util.Arrays.fill(rainProb, 0);
            boolean is_rain = false;

            if (weatherData != null && weatherData.currently != null) {
                long t = weatherData.currently.time;
                t *= 1000;
                if (currentTime - t >= 6 * DateUtils.HOUR_IN_MILLIS) {
                    Log.d(TAG, "Weather data too old - deleting");
                    weatherData = null;//Data is too old
                }
            }

            if (weatherData != null && weatherData.minutely != null) {
                for (WeatherService.Datum d : weatherData.minutely.data) {
                    long time = d.time;
                    time *= 1000;
                    mCalendar.setTimeInMillis(time);
                    minutes = mCalendar.get(Calendar.MINUTE);
                    if (d.precipProbability != null && d.precipIntensity != null &&
                            time - currentTime <= 59 * DateUtils.MINUTE_IN_MILLIS &&
                            time - currentTime >= -DateUtils.MINUTE_IN_MILLIS) {
                        double rain = d.precipIntensity * d.precipProbability;
                        rainPrediction[minutes] = (float) rain;
                        double prob = d.precipProbability;
                        rainProb[minutes] = (float) prob;
                        if (rain >= 0.12) {
                            is_rain = true;
                        }
                    }
                }
            }

            if (is_rain) {
                rapid_update = true;
            } else {
                rapid_update = false;
            }

            mCalendar.setTimeInMillis(currentTime);
            minutes = mCalendar.get(Calendar.MINUTE);

            mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.minor_tic_thickenss));
            for (int i = 0; i < 60; ++i) {
                float length = minor_tic_end - minor_tic_start;
                if (rainPrediction[i] >= 0.12) {
                    length += rainPrediction[i] * (minor_tic_start - max_rain_start) / assumed_max_rain;
                    length = Math.min(length, minor_tic_end);
                    float p = rainProb[i];
                    mHandPaint.setColor(Color.rgb((int) (p * Color.red(rain_color) + 255 * (1 - p)),
                            (int) (p * Color.green(rain_color) + 255 * (1 - p)),
                            (int) (p * Color.blue(rain_color) + 255 * (1 - p))));
                } else {
                    mHandPaint.setColor(Color.WHITE);
                }

                if (weatherData != null && is_rain) {
                    int diff = (minutes - i + 60) % 60;
                    if (0 < diff && diff < 5) {
                        switch (diff) {
                            case 4:
                                length = minor_tic_end - minor_tic_start;
                                break;
                            case 3:
                                length = (minor_tic_end - minor_tic_start) * 0.5f;
                                break;
                            case 1:
                            case 2:
                                length = 0;
                                break;
                        }
                    }
                }
                double ticRot = i / 30.f * Math.PI;
                float tic_end = minor_tic_end * grid;
                length *= grid;
                if (-Math.cos(ticRot) * tic_end + centreY > bounds.height() - mChinSize) {
                    tic_end = (float) ((radius * 2 - mChinSize - centreY) / (-Math.cos(ticRot)));
                    length *= ((radius - mChinSize) / radius) / (-Math.cos(ticRot));
                }

                float ticX = (float) Math.sin(ticRot) * (tic_end - length);
                float ticY = (float) -Math.cos(ticRot) * (tic_end - length);
                float ticXEnd = (float) Math.sin(ticRot) * tic_end;
                float ticYEnd = (float) -Math.cos(ticRot) * tic_end;

                canvas.drawLine(centreX + ticX, centreY + ticY, centreX + ticXEnd, centreY + ticYEnd, mHandPaint);
            }


            ArrayList<CalendarData> currentEvents = new ArrayList<CalendarData>();

            if (calendarData != null) {
                for (CalendarData c : calendarData) {
                    if (c.begin < currentTime + DateUtils.MINUTE_IN_MILLIS * 57 && c.end > currentTime) {
                        currentEvents.add(c);
                    }
                }
            }

            if (currentEvents.isEmpty() && wereEvents) {
                showCalendar = false;
                wereEvents = false;
            } else if (!currentEvents.isEmpty() && !wereEvents) {
                showCalendar = true;
                wereEvents = true;
            }

            if (showCalendar) {
                mHandPaint.setStyle(Paint.Style.FILL);
                mHandPaint.setTextSize(text_size * grid);
                int i = 0;
                for (CalendarData c: currentEvents) {
                    mHandPaint.setColor(calendar_colors[i%3]);
                    float start_rot;
                    if (c.begin <= currentTime) {
                        start_rot = minRot;
                    } else {
                        mCalendar.setTimeInMillis(c.begin);
                        float start_minutes = mCalendar.get(Calendar.MINUTE);
                        start_rot = (start_minutes / 30f) * (float) Math.PI;
                    }

                    mCalendar.setTimeInMillis(Math.min(c.end, currentTime + DateUtils.MINUTE_IN_MILLIS*57));
                    float end_minutes = mCalendar.get(Calendar.MINUTE);
                    float end_rot = (end_minutes / 30f) * (float) Math.PI;

                    Path path = arcPath(start_rot, end_rot, calendar_len-calendar_thick, calendar_len, centreX, centreY, grid);
                    canvas.drawPath(path, mHandPaint);

                    mHandPaint.setColor(calendar_colors_bright[i%3]);

                    if (i == 0) {
                        float width = mHandPaint.measureText(c.title);
                        float height;
                        if (currentEvents.size() == 1) {
                            height = (first_text+second_text)*0.5f;
                        } else {
                            height = first_text;
                        }
                        canvas.drawText(c.title, centreX - width / 2.f, centreY + height * grid, mHandPaint);
                    } else if (i == 1) {
                        float width = mHandPaint.measureText(c.title);
                        canvas.drawText(c.title, centreX - width / 2.f, centreY + second_text * grid, mHandPaint);
                    }

                    i++;
                }
            }

            mHandPaint.setColor(Color.WHITE);

            mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.major_tic_thickenss));
            for (int i = 0; i < 12; ++i) {
                double ticRot = i / 6.f * Math.PI;
                float tic_end = major_tic_end * grid;
                float length = (major_tic_end - major_tic_start) * grid;
                if (-Math.cos(ticRot) * tic_end + centreY > bounds.height() - mChinSize) {
                    tic_end = (float) ((bounds.height() - mChinSize - centreY) / (-Math.cos(ticRot)));
                    length *= ((radius - mChinSize) / radius) / (-Math.cos(ticRot));
                }

                float ticX = (float) Math.sin(ticRot) * (tic_end - length);
                float ticY = (float) -Math.cos(ticRot) * (tic_end - length);
                float ticXEnd = (float) Math.sin(ticRot) * tic_end;
                float ticYEnd = (float) -Math.cos(ticRot) * tic_end;

                canvas.drawLine(centreX + ticX, centreY + ticY, centreX + ticXEnd, centreY + ticYEnd, mHandPaint);
            }

            if (!showCalendar) {
                /*mHandPaint.setStyle(Paint.Style.STROKE);
                for (int i=0; i<grid; ++i) {
                    canvas.drawCircle(centreX, centreY, grid * i, mHandPaint);
                }*/

                class DayWeatherPoint {
                    public float rain;
                    public float cloudCover;
                    public float rot;
                    public float len;
                    public long time;
                    public int color;

                    public DayWeatherPoint() {

                    }

                    public DayWeatherPoint(DayWeatherPoint another) {
                        this.rain = another.rain;
                        this.cloudCover = another.cloudCover;
                        this.rot = another.rot;
                        this.len = another.len;
                        this.time = another.time;
                        this.color = another.color;
                    }
                }

                long firstTime = -1;
                if (weatherData != null && weatherData.hourly != null) {//TODO move to update function
                    List<DayWeatherPoint> dailyWeather = new ArrayList<>();

                    for (WeatherService.Datum d : weatherData.hourly.data) {
                        long time = d.time;
                        time *= 1000;

                        if (time - currentTime >= DateUtils.HOUR_IN_MILLIS * 12
                                || time - currentTime < -DateUtils.HOUR_IN_MILLIS) {
                            continue;
                        }
                        if (time < currentTime) {
                            time = currentTime;
                        } else if (time > currentTime + DateUtils.HOUR_IN_MILLIS * 11) {
                            time = currentTime + DateUtils.HOUR_IN_MILLIS * 11;
                        }

                        mCalendar.setTimeInMillis(time);
                        minutes = mCalendar.get(Calendar.MINUTE);
                        hours = mCalendar.get(Calendar.HOUR);

                        float dataRot = ((hours + (minutes / 60f)) / 6f) * (float) Math.PI;


                        DayWeatherPoint p = new DayWeatherPoint();
                        if (d.precipProbability != null && d.precipIntensity != null) {
                            double prob = d.precipProbability;
                            double intensity = d.precipIntensity;
                            if (time <= currentTime) {
                                if (weatherData.currently != null && weatherData.currently.precipProbability != null) {
                                    prob = Math.max(prob, weatherData.currently.precipProbability);
                                }
                            }
                            double rain = prob * intensity;
                            p.rain = (float) rain;
                        } else {
                            p.rain = 0;
                        }

                        if (d.cloudCover != null) {
                            double cloud = d.cloudCover;
                            p.cloudCover = (float) cloud;
                        }
                        p.rot = dataRot;

                        p.time = d.time;
                        if (firstTime == -1 || p.time < firstTime) {
                            firstTime = p.time;
                        }

                        dailyWeather.add(p);
                    }

                    boolean do_sun = false;
                    if (weatherData.daily != null && weatherData.daily.data.size() >= 2) {
                        do_sun = true;
                    }
                    if (do_sun) {
                        long nextChange;
                        if (firstTime < weatherData.daily.data.get(0).sunriseTime) {
                            nextChange = weatherData.daily.data.get(0).sunriseTime;
                        } else if (firstTime < weatherData.daily.data.get(0).sunsetTime) {
                            nextChange = weatherData.daily.data.get(0).sunsetTime;
                        } else {
                            nextChange = weatherData.daily.data.get(1).sunriseTime;
                        }

                        if (nextChange - currentTime < DateUtils.HOUR_IN_MILLIS * 12
                                || nextChange - currentTime >= -DateUtils.HOUR_IN_MILLIS) {

                            DayWeatherPoint prev = null;
                            int index;
                            boolean found = false;
                            for (index = 0; index < dailyWeather.size(); ++index) {
                                long t = dailyWeather.get(index).time;
                                if (t > nextChange) {
                                    index--;
                                    break;
                                }
                                prev = dailyWeather.get(index);
                            }

                            DayWeatherPoint copy;
                            if (prev == null) {
                                prev = dailyWeather.get(0);
                            }

                            copy = new DayWeatherPoint(prev);

                            copy.time = nextChange;
                            long time = copy.time;
                            time *= 1000;

                            mCalendar.setTimeInMillis(time);
                            minutes = mCalendar.get(Calendar.MINUTE);
                            hours = mCalendar.get(Calendar.HOUR);

                            float rot = ((hours + (minutes / 60f)) / 6f) * (float) Math.PI;
                            copy.rot = rot;

                            dailyWeather.add(index+1, copy);
                        }
                    }

                    DayWeatherPoint prev = null;
                    mHandPaint.setStyle(Paint.Style.FILL);
                    for (DayWeatherPoint p : dailyWeather) {
                        boolean dark = false;
                        long t = p.time;
                        if (do_sun) {
                            if (t < weatherData.daily.data.get(0).sunriseTime
                                    || (t >= weatherData.daily.data.get(0).sunsetTime &&
                                    t < weatherData.daily.data.get(1).sunriseTime)) {
                                dark = true;
                            }
                        }

                        float len = day_weather_len;
                        if (p.rain >= 0.06) {
                            len += (day_weather_len_max - day_weather_len) * p.rain / assumed_max_rain;
                            if (!dark) {
                                p.color = rain_color;
                            } else {
                                p.color = dark_rain_color;
                            }
                        } else {
                            int r, g, b;
                            if (!dark) {
                                r = g = b = (int) (p.cloudCover * 255.f);
                                r += (int) ((float) sun_r) * (1 - p.cloudCover);
                                g += (int) ((float) sun_g) * (1 - p.cloudCover);
                                b += (int) ((float) sun_b) * (1 - p.cloudCover);
                            } else {
                                r = g = b = (int) (66 + p.cloudCover * 92.f);
                            }
                            p.color = Color.rgb(r, g, b);
                        }

                        p.len = len;

                        if (prev != null) {
                            Path path = arcPath(prev.rot, p.rot, day_weather_len, prev.len + day_weather_thick,
                                    centreX, centreY, grid);

                            mHandPaint.setColor(prev.color);
                            canvas.drawPath(path, mHandPaint);
                        }
                        prev = p;
                    }
                }
            }

            mHandPaint.setColor(Color.WHITE);
            mHandPaint.setStyle(Paint.Style.STROKE);

            float secLength = second_length * grid;
            float minLength = minute_length * grid;
            float hrLength = hour_length * grid;

            mHandPaint.setStyle(Paint.Style.STROKE);

            if (!mAmbient) {
                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                mHandPaint.setStrokeWidth(mRes.getDimension(R.dimen.second_thickness));
                canvas.drawLine(centreX, centreY, centreX + secX, centreY + secY, mHandPaint);
            }

            float minute_thickness = mRes.getDimension(R.dimen.minute_thickness);
            float minute_tip_thickness = mRes.getDimension(R.dimen.minute_tip_thickness);
            float minute_tip_length = mRes.getDimension(R.dimen.minute_tip_length);
            mHandPaint.setStyle(Paint.Style.FILL);
            Path p = handPath(minRot, minute_thickness, minute_tip_thickness, minLength, minute_tip_length,
                    centreX, centreY);
            canvas.drawPath(p, mHandPaint);

            float hour_thickness = mRes.getDimension(R.dimen.hour_thickness);
            float hour_tip_thickness = mRes.getDimension(R.dimen.hour_tip_thickness);
            float hour_tip_length = mRes.getDimension(R.dimen.hour_tip_length);
            p = handPath(hrRot, hour_thickness, hour_tip_thickness, hrLength, hour_tip_length,
                    centreX, centreY);
            canvas.drawPath(p, mHandPaint);

            mHandPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centreX, centreY, grid * circle_size, mHandPaint);
        }

        private Path arcPath(float start_rot, float end_rot, float inner_radius, float outer_radius,
                             float centreX, float centreY, float grid) {
            Path path = new Path();

            float ang = start_rot * 180.f / ((float) Math.PI) - 90.f;
            float sweep = end_rot * 180.f / ((float) Math.PI) - 90.f - ang;
            sweep = (720 + sweep) % 360;
            sweep += 1;//ensure segments overlap

            path.arcTo(centreX - outer_radius * grid,
                    centreY - outer_radius * grid,
                    centreX + outer_radius * grid,
                    centreY + outer_radius * grid,
                    ang, sweep, true);

            float x = centreX + (float) Math.sin(end_rot) * inner_radius * grid;
            float y = centreY - (float) Math.cos(end_rot) * inner_radius * grid;
            path.lineTo(x, y);

            path.arcTo(centreX - inner_radius * grid, centreY - inner_radius * grid,
                    centreX + inner_radius * grid,
                    centreY + inner_radius * grid,
                    ang + sweep, -sweep, false);

            path.close();

            return path;
        }

        private Path handPath(float rot, float thickness, float tip_thickness, float length,
                              float tip_length, float centreX, float centreY) {
            float leftX = (float) Math.sin(rot-Math.PI/2.);
            float leftY = (float) -Math.cos(rot-Math.PI/2.);
            float upX = (float) Math.sin(rot);
            float upY = (float) -Math.cos(rot);
            Path p = new Path();
            float x, y;
            p.moveTo(centreX, centreY);
            x = leftX*thickness/2.f;
            y = leftY*thickness/2.f;
            p.lineTo(centreX + x, centreY + y);
            x = leftX*tip_thickness/2.f+upX*(length-tip_length);
            y = leftY*tip_thickness/2.f+upY*(length-tip_length);
            p.lineTo(centreX+x, centreY+y);
            x = upX*length;
            y = upY*length;
            p.lineTo(centreX + x, centreY + y);
            x = -leftX*tip_thickness/2.f+upX*(length-tip_length);
            y = -leftY*tip_thickness/2.f+upY*(length-tip_length);
            p.lineTo(centreX + x, centreY + y);
            x = -leftX*thickness/2.f;
            y = -leftY*thickness/2.f;
            p.lineTo(centreX + x, centreY + y);
            p.close();

            return p;
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

            if (!rapid_update && weatherData != null) {
                if (mAmbient) {
                    if (mCalendar.getTimeInMillis() - lastBackgroundUpdate >= DateUtils.MINUTE_IN_MILLIS * 10
                            && mCalendar.get(Calendar.MINUTE) % 20 < 4) {
                        backgroundUpdate();
                    }
                } else {
                    if (mCalendar.getTimeInMillis() - lastBackgroundUpdate >= DateUtils.MINUTE_IN_MILLIS * 5) {
                        backgroundUpdate();
                    }
                }
            } else {
                if (mAmbient && weatherData != null){
                    if (mCalendar.getTimeInMillis() - lastBackgroundUpdate >= DateUtils.MINUTE_IN_MILLIS*3
                            && mCalendar.get(Calendar.MINUTE) % 5 <= 1) {
                        backgroundUpdate();
                    }
                } else {
                    if (mCalendar.getTimeInMillis() - lastBackgroundUpdate >= DateUtils.MINUTE_IN_MILLIS) {
                        backgroundUpdate();
                    }
                }
            }
        }

        private void backgroundUpdate() {
            lastBackgroundUpdate = System.currentTimeMillis();

            byte[] data = {};

            Log.d(TAG, "Background update");

            if (mWeatherNodeId != null && mApiConnected) {
                Wearable.MessageApi.sendMessage(mGoogleApiClient, mWeatherNodeId,
                        WEATHER_PATH, data).setResultCallback(
                        new ResultCallback<MessageApi.SendMessageResult>() {
                            @Override
                            public void onResult(@NonNull MessageApi.SendMessageResult result) {
                            }
                        }
                );
            }

            if (mWeatherNodeId != null && mApiConnected) {
                Wearable.MessageApi.sendMessage(mGoogleApiClient, mWeatherNodeId,
                        CALENDAR_PATH, data).setResultCallback(
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
        private static final String CALENDAR_PATH = "/annulus_calendar_data";

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
            if (weatherData == null) {
                backgroundUpdate();
            } else {
                checkBackgroundUpdate();
            }
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
        ArrayList<CalendarData> calendarData = null;

        @Override
        public void onMessageReceived(MessageEvent messageEvent) {
            byte[] d = messageEvent.getData();
            if (messageEvent.getPath().equals(WEATHER_PATH)) {
                Log.d(TAG, "Processing weather message");
                WeatherService.WeatherData data = null;
                try {
                    data = (WeatherService.WeatherData) convertFromBytes(d);
                } catch (IOException e) {
                    Log.e(TAG, "Weather data conversion from bytes failed");
                    Log.e(TAG, e.getMessage());
                } catch (ClassNotFoundException e) {

                }

                if (data != null) {
                    Log.d(TAG, "Weather data received");
                    weatherData = data;
                    invalidate();
                }
            } else if (messageEvent.getPath().equals(CALENDAR_PATH)) {
                Log.d(TAG, "Processing calendar message");
                ArrayList<CalendarData> data = null;
                try {
                    data = (ArrayList<CalendarData>) convertFromBytes(d);
                } catch (IOException e) {
                    Log.e(TAG, "Calendar data conversion from bytes failed");
                } catch (ClassNotFoundException e) {

                }

                if (data != null) {
                    Log.d(TAG, "Calendar data received");
                    calendarData = data;
                    invalidate();
                }
            }
        }

        public void onConnectionSuspended(int cause) {
            mApiConnected = false;
        }

        public void onConnectionFailed(@NonNull ConnectionResult cause) {
            mApiConnected = false;
        }
    }
}
