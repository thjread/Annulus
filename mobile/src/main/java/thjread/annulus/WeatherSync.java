package thjread.annulus;

import android.location.Location;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;

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
import java.nio.ByteBuffer;

import retrofit.Call;
import retrofit.GsonConverterFactory;
import retrofit.Response;
import retrofit.Retrofit;
import thjread.annulus.R;

public class WeatherSync extends WearableListenerService implements GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener, MessageApi.MessageListener {

    private static final String WEATHER_PATH = "/annulus_weather_data";
    private static final String TAG = "thjread.annulus";

    private GoogleApiClient mGoogleApiClient;
    private String mNodeId = null;
    private boolean processingMessage = false;//TODO

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
        if (messageEvent.getPath().equals(WEATHER_PATH) && !processingMessage) {//TODO
            Log.d(TAG, "Weather message received");
            mNodeId = messageEvent.getSourceNodeId();
            WeatherSyncTask task = new WeatherSyncTask();
            processingMessage = true;
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

    private class WeatherSyncTask extends AsyncTask<Void, Void, Void> {

        private byte[] convertToBytes(Object object) throws IOException {
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 ObjectOutput out = new ObjectOutputStream(bos)) {
                out.writeObject(object);
                return bos.toByteArray();
            }
        }

        @Override
        protected Void doInBackground(Void... params) {
            if (mGoogleApiClient.isConnected()) {

                getWeatherData();

                byte[] data;
                try {
                    data = convertToBytes(weatherData);
                } catch (IOException e) {
                    Log.e(TAG, "Weather data conversion to bytes failed");
                    return null;
                }

                Wearable.MessageApi.sendMessage(mGoogleApiClient, mNodeId,
                        WEATHER_PATH, data).setResultCallback(
                        new ResultCallback<MessageApi.SendMessageResult>() {
                            @Override
                            public void onResult(@NonNull MessageApi.SendMessageResult result) {
                                processingMessage = false;//TODO
                            }
                        }
                );
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

        Call call = service.getWeatherData(key, mLastLocation.getLatitude(), mLastLocation.getLongitude());//TODO
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
}