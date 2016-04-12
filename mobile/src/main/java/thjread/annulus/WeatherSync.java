package thjread.annulus;

import android.content.ContentUris;
import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.support.annotation.NonNull;
import android.text.format.DateUtils;
import android.util.Log;
import android.database.Cursor;
import android.net.Uri;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.MessageApi;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.android.gms.location.LocationServices;
import com.squareup.okhttp.OkHttpClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.util.ArrayList;

import retrofit.Call;
import retrofit.GsonConverterFactory;
import retrofit.Response;
import retrofit.Retrofit;

class CalendarData implements java.io.Serializable {
    private static final long serialVersionUID = 1L;

    public String title;
    public long begin;
    public long end;
}

public class WeatherSync extends WearableListenerService implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, MessageApi.MessageListener {

    private static final String WEATHER_PATH = "/annulus_weather_data";
    private static final String CALENDAR_PATH = "/annulus_calendar_data";
    private static final String TAG = "thjread.annulus";

    private GoogleApiClient mGoogleApiClient;
    private String mNodeId = null;
    private boolean processingWeatherMessage = false;//TODO
    private boolean processingCalendarMessage = false;//TODO

    private static String key;

    @Override
    public void onCreate() {
        key = getResources().getString(R.string.forecast_api_key);

        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .addApi(LocationServices.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .useDefaultAccount()
                .build();

        Log.d(TAG, "Created");

        mGoogleApiClient.connect();
    }

    @Override
    public void onConnected(Bundle connectionHint) {
        Log.d(TAG, "Connected to Google Api Service");
        Wearable.MessageApi.addListener(mGoogleApiClient, this);

        WeatherSyncTask task = new WeatherSyncTask();
        task.execute();
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult result) {
        Log.d(TAG, "Connection failed");
    }

    public void onConnectionSuspended(int r) {
        Log.d(TAG, "Connection suspended");
    }

    @Override
    public void onMessageReceived(MessageEvent messageEvent) {
        if (messageEvent.getPath().equals(WEATHER_PATH) && !processingWeatherMessage) {//TODO
            Log.d(TAG, "Weather message received");
            mNodeId = messageEvent.getSourceNodeId();
            WeatherSyncTask task = new WeatherSyncTask();
            processingWeatherMessage = true;
            task.execute();
        } else if (messageEvent.getPath().equals(CALENDAR_PATH) && !processingCalendarMessage) {//TODO
            Log.d(TAG, "Calendar message received");
            mNodeId = messageEvent.getSourceNodeId();
            CalendarSyncTask task = new CalendarSyncTask();
            processingCalendarMessage = true;
            task.execute();
        }
    }

    @Override
    public void onPeerConnected(Node peer) {
        Log.d(TAG, "Peer connected: " + peer.getDisplayName());
    }

    @Override
    public void onPeerDisconnected(Node peer) {
        Log.d(TAG, "Peer disconnected: " + peer.getDisplayName());
    }

    private byte[] convertToBytes(Object object) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutput out = new ObjectOutputStream(bos)) {
            out.writeObject(object);
            return bos.toByteArray();
        }
    }

    private class WeatherSyncTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            if (mGoogleApiClient.isConnected()) {
                getWeatherData();

                byte[] data;
                try {
                    data = convertToBytes(weatherData);
                } catch (IOException e) {
                    Log.e(TAG, "Weather data conversion to bytes failed");
                    processingWeatherMessage = false;
                    return null;
                }

                if (data != null) {
                    Wearable.MessageApi.sendMessage(mGoogleApiClient, mNodeId,
                            WEATHER_PATH, data).setResultCallback(
                            new ResultCallback<MessageApi.SendMessageResult>() {
                                @Override
                                public void onResult(@NonNull MessageApi.SendMessageResult result) {
                                    Log.d(TAG, "Sent weather message");
                                    processingWeatherMessage = false;
                                }
                            }
                    );
                } else {
                    processingWeatherMessage = false;
                }
            }
            return null;
        }
    }

    private ArrayList<CalendarData> calendarData;

    private class CalendarSyncTask extends AsyncTask<Void, Void, Void> {
        @Override
        protected Void doInBackground(Void... params) {
            if (mGoogleApiClient.isConnected()) {
                getCalendarData();

                byte data[];
                try {
                    data = convertToBytes(calendarData);
                } catch (IOException e) {
                    Log.e(TAG, "Calendar data conversion to bytes failed");
                    Log.e(TAG, e.getMessage());
                    processingCalendarMessage = false;
                    return null;
                }

                if (data != null) {
                    Wearable.MessageApi.sendMessage(mGoogleApiClient, mNodeId,
                            CALENDAR_PATH, data).setResultCallback(
                            new ResultCallback<MessageApi.SendMessageResult>() {
                                @Override
                                public void onResult(@NonNull MessageApi.SendMessageResult result) {
                                    Log.d(TAG, "Sent calendar message");
                                    processingCalendarMessage = false;
                                }
                            }
                    );
                } else {
                    processingCalendarMessage = false;
                }
            }
            return null;
        }
    }

    private Location mLastLocation = null;
    private WeatherService.WeatherData weatherData = null;

    private WeatherService.WeatherData getWeatherData() {
        OkHttpClient client = new OkHttpClient();

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://api.forecast.io/")
                .addConverterFactory(GsonConverterFactory.create())
                .client(client)
                .build();

        WeatherService service = retrofit.create(WeatherService.class);

        Location l = LocationServices.FusedLocationApi.getLastLocation(mGoogleApiClient);
        if (l != null) {
            mLastLocation = l;
        }
        if (mLastLocation == null) {
            return weatherData;
        }

        Call call = service.getWeatherData(key, mLastLocation.getLatitude(), mLastLocation.getLongitude());
        try {
            Response<WeatherService.WeatherData> r = call.execute();
            WeatherService.WeatherData data = r.body();
            if (data != null) {
                weatherData = data;
            }
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
        }

        return weatherData;
    }

    private ArrayList<CalendarData> getCalendarData() {
        final String[] INSTANCE_PROJECTION = new String[] {
                CalendarContract.Instances.EVENT_ID,      // 0
                CalendarContract.Instances.BEGIN,         // 1
                CalendarContract.Instances.END,          // 2
                CalendarContract.Instances.TITLE,   // 3
                CalendarContract.Instances.ALL_DAY      // 4
        };

        Uri.Builder builder = CalendarContract.Instances.CONTENT_URI.buildUpon();
        long begin = System.currentTimeMillis();
        ContentUris.appendId(builder, begin);
        ContentUris.appendId(builder, begin+ DateUtils.DAY_IN_MILLIS);

        /*final Cursor cursor = getContentResolver().query(builder.build(),
                INSTANCE_PROJECTION,
                null,
                null,
                null);*/
        final Cursor cursor = CalendarContract.Instances.query(getContentResolver(),
                INSTANCE_PROJECTION,
                begin,
                begin+DateUtils.DAY_IN_MILLIS);

        ArrayList<CalendarData> data = new ArrayList<CalendarData>();

        while (cursor.moveToNext()) {
            if (!cursor.getString(4).equals("1")) {//if not all day event
                CalendarData d = new CalendarData();

                d.begin = cursor.getLong(1);
                d.end = cursor.getLong(2);
                d.title = cursor.getString(3);
                int bIndex = d.title.indexOf('(');
                if (bIndex != -1) {
                    int b2Index = d.title.indexOf(')', bIndex);
                    if (b2Index != -1) {
                        d.title = d.title.substring(bIndex+1, b2Index);
                    }
                }
                data.add(d);
            }
        }

        cursor.close();

        calendarData = data;
        Log.d(TAG, "Calendar data stored");

        return calendarData;
    }
}