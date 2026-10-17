package com.portalframe;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/** HTTPS weather and approximate public-IP location, independent of the frame UI. */
public final class WeatherClient {
  public interface Transport {
    JSONObject get(String url) throws Exception;
  }

  public static final class Result {
    public final JSONObject weather, location;
    public final String warning;

    Result(JSONObject weather, JSONObject location, String warning) {
      this.weather = weather;
      this.location = location;
      this.warning = warning;
    }
  }

  public static String mode(JSONObject config) {
    return config.optString("weatherLocation", "ip").equals("manual") ? "manual" : "ip";
  }

  public static String key(JSONObject config) {
    JSONArray key = new JSONArray().put(mode(config)).put(config.optString("units", "celsius"));
    if (mode(config).equals("manual"))
      key.put(config.optString("latitude"))
          .put(config.optString("longitude"))
          .put(config.optString("city"));
    return key.toString();
  }

  public static boolean coordinates(double latitude, double longitude) {
    return Double.isFinite(latitude)
        && Double.isFinite(longitude)
        && Math.abs(latitude) <= 90
        && Math.abs(longitude) <= 180;
  }

  public static String place(JSONObject data) {
    String city = data.optString("city", "").trim();
    String region =
        data.optString(data.optString("country_code").equals("US") ? "region_code" : "region", "")
            .trim();
    if (region.isEmpty()) region = data.optString("region", "").trim();
    if (city.isEmpty()) return region.isEmpty() ? data.optString("country", "").trim() : region;
    return region.isEmpty() || city.equalsIgnoreCase(region) ? city : city + ", " + region;
  }

  private static boolean usableLocation(JSONObject data) {
    return coordinates(
            data.optDouble("latitude", Double.NaN), data.optDouble("longitude", Double.NaN))
        && !data.optString("place").trim().isEmpty();
  }

  public static Result load(JSONObject config, JSONObject cachedLocation, Transport transport)
      throws Exception {
    JSONObject location = cachedLocation;
    String mode = mode(config), warning = "", label;
    double latitude, longitude;
    if (mode.equals("ip")) {
      try {
        JSONObject data =
            transport.get(
                "https://ipwho.is/?fields=success,city,region,region_code,country,country_code,latitude,longitude");
        if (!data.optBoolean("success")) throw new IOException("IP lookup failed");
        JSONObject detected = new JSONObject();
        for (String name :
            new String[] {
              "city", "region", "region_code", "country", "country_code", "latitude", "longitude"
            }) detected.put(name, data.get(name));
        detected.put("place", place(data));
        detected.put("updated", System.currentTimeMillis());
        if (!usableLocation(detected)) throw new IOException("Incomplete IP location");
        location = detected;
      } catch (Exception error) {
        if (!usableLocation(cachedLocation))
          throw new IOException("Could not detect IP location. Refresh or choose Manual location.");
        warning = "IP lookup unavailable · using saved location";
      }
      latitude = location.getDouble("latitude");
      longitude = location.getDouble("longitude");
      label = location.getString("place");
    } else {
      latitude = Double.parseDouble(config.optString("latitude"));
      longitude = Double.parseDouble(config.optString("longitude"));
      label = config.optString("city", "").trim();
      if (label.isEmpty()) label = "Manual location";
    }
    if (!coordinates(latitude, longitude)) throw new IOException("Invalid weather coordinates");
    String units =
        config.optString("units", "celsius").equals("fahrenheit") ? "fahrenheit" : "celsius";
    JSONObject current;
    try {
      current =
          transport
              .get(
                  "https://api.open-meteo.com/v1/forecast?latitude="
                      + latitude
                      + "&longitude="
                      + longitude
                      + "&current=temperature_2m,weather_code,is_day&temperature_unit="
                      + units)
              .getJSONObject("current");
      if (!Double.isFinite(current.getDouble("temperature_2m")) || current.isNull("weather_code"))
        throw new IOException("Invalid forecast");
    } catch (Exception error) {
      throw new IOException("Weather unavailable. Check your connection and refresh.");
    }
    JSONObject value = new JSONObject();
    value.put("temperature", current.getDouble("temperature_2m"));
    value.put("code", current.getInt("weather_code"));
    value.put("isDay", current.optInt("is_day", 1) == 1);
    value.put("units", units);
    value.put("latitude", latitude);
    value.put("longitude", longitude);
    value.put("place", label);
    value.put("locationMode", mode);
    value.put("locationCached", !warning.isEmpty());
    value.put("requestKey", key(config));
    value.put("updated", System.currentTimeMillis());
    return new Result(value, location, warning);
  }

  public static JSONObject getJson(String address) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(12000);
    connection.setInstanceFollowRedirects(false);
    try {
      if (connection.getResponseCode() != 200) throw new IOException("Service unavailable");
      try (InputStream in = connection.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) != -1) {
          if (out.size() + count > 100000) throw new IOException("Response too large");
          out.write(buffer, 0, count);
        }
        return new JSONObject(out.toString("UTF-8"));
      }
    } finally {
      connection.disconnect();
    }
  }
}
