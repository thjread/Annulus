package thjread.annulus;

import java.util.ArrayList;
import java.util.List;

import retrofit.Call;
import retrofit.http.GET;
import retrofit.http.Path;

public interface WeatherService {
    @GET("forecast/{api_key}/{latitude},{longitude}?units=si")
    Call<WeatherData> getWeatherData(@Path("api_key") String api_key,
                                     @Path("latitude") double latitude,
                                     @Path("longitude") double longitude);

    class Daily implements java.io.Serializable {
        public String summary;
        public String icon;
        public List<Datum> data = new ArrayList<>();
    }

    class Hourly implements java.io.Serializable {
        public String summary;
        public String icon;
        public List<Datum> data = new ArrayList<>();
    }

    class Minutely implements java.io.Serializable {
        public String summary;
        public String icon;
        public List<Datum> data = new ArrayList<>();
    }

    class Datum implements java.io.Serializable {
        public Integer time;
        public String summary;
        public String icon;
        public Integer sunriseTime;
        public Integer sunsetTime;
        public Double moonPhase;
        public Double precipIntensity;
        public Double precipIntensityMax;
        public Integer precipIntensityMaxTime;
        public Double precipProbability;
        public String precipType;
        public Double temperature;
        public Double temperatureMin;
        public Integer temperatureMinTime;
        public Double temperatureMax;
        public Integer temperatureMaxTime;
        public Double apparentTemperatureMin;
        public Integer apparentTemperatureMinTime;
        public Double apparentTemperatureMax;
        public Integer apparentTemperatureMaxTime;
        public Double dewPoint;
        public Double humidity;
        public Double windSpeed;
        public Integer windBearing;
        public Double visibility;
        public Double cloudCover;
        public Double pressure;
        public Double ozone;
    }

    class Flags implements java.io.Serializable {
        public List<String> sources = new ArrayList<>();
        public List<String> darkskyStations = new ArrayList<>();
        public List<String> datapointStations = new ArrayList<>();
        public String metnoLicense;
        public List<String> isdStations = new ArrayList<>();
        public List<String> madisStations = new ArrayList<>();
        public String units;
    }

    class WeatherData implements java.io.Serializable{
        private static final long serialVersionUID = 1L;

        public Double latitude;
        public Double longitude;
        public String timezone;
        public Integer offset;
        public Datum currently;
        public Minutely minutely;
        public Hourly hourly;
        public Daily daily;
        public Flags flags;
    }
}
