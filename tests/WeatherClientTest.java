package com.portalframe;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Run with Android app_process, so fixtures use the device's actual JSON implementation. */
public final class WeatherClientTest {
  private static int assertions;

  private static void check(boolean value) {
    assertions++;
    if (!value) throw new AssertionError("Assertion " + assertions);
  }

  private static JSONObject json(String value) throws Exception {
    return new JSONObject(value);
  }

  private static class Fake implements WeatherClient.Transport {
    JSONObject ip, forecast;
    boolean failIp, failWeather;
    List<String> urls = new ArrayList<>();

    Fake() throws Exception {
      ip =
          json(
              "{'success':true,'city':'Santa"
                  + " Clara','region':'California','region_code':'CA','country':'United"
                  + " States','country_code':'US','latitude':37.35,'longitude':-121.96}");
      forecast = json("{'current':{'temperature_2m':18.3,'weather_code':0,'is_day':0}}");
    }

    public JSONObject get(String url) throws Exception {
      urls.add(url);
      boolean location = url.startsWith("https://ipwho.is/");
      if (location ? failIp : failWeather) throw new IOException("Fixture network failure");
      return location ? ip : forecast;
    }
  }

  private static void fails(JSONObject config, JSONObject cache, Fake fake) throws Exception {
    boolean failed = false;
    try {
      WeatherClient.load(config, cache, fake);
    } catch (Exception expected) {
      failed = true;
    }
    check(failed);
  }

  public static void main(String[] args) throws Exception {
    JSONObject config =
        json(
            "{'weatherLocation':'ip','units':'celsius','city':'Ignored"
                + " name','latitude':'0','longitude':'0'}");
    Fake fake = new Fake();
    WeatherClient.Result result = WeatherClient.load(config, new JSONObject(), fake);
    check(result.weather.getString("place").equals("Santa Clara, CA"));
    check(result.weather.getDouble("latitude") == 37.35);
    check(result.weather.getDouble("temperature") == 18.3);
    check(!result.weather.getBoolean("isDay"));
    check(result.weather.getString("locationMode").equals("ip"));
    check(!result.weather.getBoolean("locationCached"));
    check(result.warning.isEmpty());
    check(result.location.getLong("updated") > 0);
    check(!result.location.has("ip"));
    check(fake.urls.size() == 2);
    check(fake.urls.get(0).contains("fields=success,city,"));
    check(fake.urls.get(1).contains("latitude=37.35&longitude=-121.96"));
    check(fake.urls.get(1).contains("temperature_unit=celsius"));
    check(
        WeatherClient.key(config)
            .equals(WeatherClient.key(json("{'weatherLocation':'ip','units':'celsius'}"))));
    check(
        !WeatherClient.key(config)
            .equals(WeatherClient.key(json("{'weatherLocation':'ip','units':'fahrenheit'}"))));
    check(
        WeatherClient.place(
                json(
                    "{'city':'Vancouver','region':'British"
                        + " Columbia','region_code':'BC','country_code':'CA'}"))
            .equals("Vancouver, British Columbia"));
    check(
        WeatherClient.place(json("{'city':'Singapore','region':'Singapore'}")).equals("Singapore"));
    check(
        WeatherClient.place(json("{'region':'California','region_code':'','country_code':'US'}"))
            .equals("California"));
    check(WeatherClient.place(json("{'country':'Iceland'}")).equals("Iceland"));
    check(WeatherClient.coordinates(0, 0));
    check(WeatherClient.coordinates(-90, 180));
    check(!WeatherClient.coordinates(91, 0));
    check(!WeatherClient.coordinates(0, -181));
    check(!WeatherClient.coordinates(Double.NaN, 0));
    check(!WeatherClient.coordinates(0, Double.POSITIVE_INFINITY));
    Fake offline = new Fake();
    offline.failIp = true;
    WeatherClient.Result cached = WeatherClient.load(config, result.location, offline);
    check(cached.weather.getBoolean("locationCached"));
    check(!cached.warning.isEmpty());
    check(cached.weather.getString("place").equals("Santa Clara, CA"));
    fails(config, new JSONObject(), offline);
    Fake denied = new Fake();
    denied.ip.put("success", false);
    fails(config, new JSONObject(), denied);
    check(denied.urls.size() == 1);
    Fake invalid = new Fake();
    invalid.ip.put("latitude", 100);
    fails(config, new JSONObject(), invalid);
    Fake missing = new Fake();
    missing.ip.remove("longitude");
    fails(config, new JSONObject(), missing);
    Fake noForecast = new Fake();
    noForecast.failWeather = true;
    fails(config, result.location, noForecast);
    Fake nullTemperature = new Fake();
    nullTemperature.forecast.getJSONObject("current").put("temperature_2m", JSONObject.NULL);
    fails(config, new JSONObject(), nullTemperature);
    JSONObject manual =
        json(
            "{'weatherLocation':'manual','units':'fahrenheit','city':'Test"
                + " point','latitude':'0','longitude':'0'}");
    Fake manualFake = new Fake();
    WeatherClient.Result m = WeatherClient.load(manual, result.location, manualFake);
    check(manualFake.urls.size() == 1);
    check(manualFake.urls.get(0).startsWith("https://api.open-meteo.com/"));
    check(manualFake.urls.get(0).contains("temperature_unit=fahrenheit"));
    check(m.weather.getString("place").equals("Test point"));
    check(m.weather.getDouble("latitude") == 0);
    check(m.location == result.location);
    check(m.weather.getString("locationMode").equals("manual"));
    check(!WeatherClient.key(manual).equals(WeatherClient.key(config)));
    manual.put("latitude", "");
    Fake blank = new Fake();
    fails(manual, new JSONObject(), blank);
    check(blank.urls.isEmpty());
    manual.put("latitude", "91");
    fails(manual, new JSONObject(), new Fake());
    System.out.println("PASS: " + assertions + " weather/location assertions");
  }
}
