package io.github.bandbridge;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;

import io.github.bandbridge.core.Probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Weather for the band from Open-Meteo (free, no key). Only a coarse location is sent
 * (rounded to 0.1° ≈ 10 km). The result is cached in SharedPreferences and handed to
 * BandSession in the format WeatherSync expects.
 */
final class WeatherFetch {

    static final String SP = "bandbridge_weather";

    static JSONObject cached(Context c, long maxAgeMs) {
        try {
            SharedPreferences sp = c.getSharedPreferences(SP, Context.MODE_PRIVATE);
            if (System.currentTimeMillis() - sp.getLong("at", 0) > maxAgeMs) return null;
            String s = sp.getString("data", null);
            return s == null ? null : new JSONObject(s);
        } catch (Throwable t) { return null; }
    }

    @SuppressLint("MissingPermission")
    private static Location coarse(Context c) {
        if (c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        Location best = null;
        for (String p : new String[]{LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER}) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (Throwable ignored) { }
        }
        return best;
    }

    /** Blocking; call off the main thread. Returns true when fresh weather was stored. */
    static boolean fetch(Context c) {
        try {
            Location l = coarse(c);
            if (l == null) { Probe.post("weather", "no location (permission off or no fix yet)"); return false; }
            double lat = Math.round(l.getLatitude() * 10) / 10.0, lng = Math.round(l.getLongitude() * 10) / 10.0;
            String u = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f", lat, lng)
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m,wind_direction_10m,uv_index"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability,uv_index&forecast_hours=24"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset&forecast_days=7"
                    + "&timezone=auto&timeformat=unixtime";
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(15000);
            if (h.getResponseCode() != 200) { Probe.post("weather", "http " + h.getResponseCode()); return false; }
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = h.getInputStream()) { byte[] buf = new byte[8192]; int n; while ((n = in.read(buf)) > 0) b.write(buf, 0, n); }
            JSONObject j = new JSONObject(b.toString("UTF-8"));
            JSONObject cur = j.getJSONObject("current"), hr = j.getJSONObject("hourly"), dy = j.getJSONObject("daily");

            JSONArray hourly = new JSONArray();
            JSONArray ht = hr.getJSONArray("time");
            for (int i = 0; i < ht.length(); i++) hourly.put(new JSONObject().put("t", ht.getLong(i))
                    .put("temp", hr.getJSONArray("temperature_2m").optDouble(i)).put("code", hr.getJSONArray("weather_code").optInt(i))
                    .put("pop", hr.getJSONArray("precipitation_probability").optDouble(i, 0)).put("uv", hr.getJSONArray("uv_index").optDouble(i, 0)));
            JSONArray daily = new JSONArray();
            JSONArray dt = dy.getJSONArray("time");
            for (int i = 0; i < dt.length(); i++) daily.put(new JSONObject().put("t", dt.getLong(i))
                    .put("max", dy.getJSONArray("temperature_2m_max").optDouble(i)).put("min", dy.getJSONArray("temperature_2m_min").optDouble(i))
                    .put("code", dy.getJSONArray("weather_code").optInt(i))
                    .put("rise", dy.getJSONArray("sunrise").optLong(i)).put("set", dy.getJSONArray("sunset").optLong(i)));

            String place = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getString("place", "");
            JSONObject w = new JSONObject();
            w.put("loc", place.isEmpty() ? "Here" : place);
            w.put("t", cur.optLong("time", System.currentTimeMillis() / 1000L));
            w.put("cur", new JSONObject().put("temp", cur.optDouble("temperature_2m")).put("hum", cur.optDouble("relative_humidity_2m"))
                    .put("feel", cur.optDouble("apparent_temperature")).put("code", cur.optInt("weather_code"))
                    .put("wind", cur.optDouble("wind_speed_10m")).put("dir", cur.optDouble("wind_direction_10m")).put("uv", cur.optDouble("uv_index", 0)));
            if (daily.length() > 0) {
                JSONObject d0 = daily.getJSONObject(0);
                w.put("today", new JSONObject().put("max", d0.opt("max")).put("min", d0.opt("min")).put("rise", d0.opt("rise")).put("set", d0.opt("set")));
            }
            w.put("hourly", hourly);
            w.put("daily", daily);
            c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit().putString("data", w.toString()).putLong("at", System.currentTimeMillis()).apply();
            Probe.post("weather", "ok | " + cur.optDouble("temperature_2m") + "° code " + cur.optInt("weather_code"));
            return true;
        } catch (Throwable t) {
            Probe.post("weather", "fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return false;
        }
    }
}
