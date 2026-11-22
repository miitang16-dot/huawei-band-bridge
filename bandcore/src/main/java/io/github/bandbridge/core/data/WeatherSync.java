package io.github.bandbridge.core.data;

import io.github.bandbridge.core.Probe;
import io.github.bandbridge.core.proto.HuaweiPacket;
import io.github.bandbridge.core.proto.packets.Weather;
import io.github.bandbridge.core.transport.RequestRunner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 *   [开始 0x09] → 单位 0x05（摄氏）→ [支持位 0x02] → 扩展支持 0x06 → 日月支持 0x0a → 当前 0x01 → 预报 0x08
 *
 *   { loc, t(观测秒), cur:{temp, hum, feel, code(WMO), wind(km/h), dir(度), uv},
 *     today:{max, min, rise, set}, hourly:[{t, temp, code, pop, uv}], daily:[{t, max, min, code, rise, set}] }
 */
public final class WeatherSync {

    private final RequestRunner rr;
    private final boolean uv, extHourly;

    public WeatherSync(RequestRunner rr, boolean uvSupported, boolean extendedHourly) {
        this.rr = rr; this.uv = uvSupported; this.extHourly = extendedHourly;
    }

    /** WMO 天气码（Open-Meteo）→ 华为图标 */
    static Weather.WeatherIcon icon(int c) {
        switch (c) {
            case 0: case 1: return Weather.WeatherIcon.SUNNY;
            case 2: return Weather.WeatherIcon.CLOUDY;
            case 3: return Weather.WeatherIcon.OVERCAST;
            case 45: case 48: return Weather.WeatherIcon.FOG;
            case 51: case 53: case 55: case 61: return Weather.WeatherIcon.LIGHT_RAIN;
            case 56: case 57: case 66: case 67: return Weather.WeatherIcon.FREEZING_RAIN;
            case 63: return Weather.WeatherIcon.RAIN;
            case 65: case 82: return Weather.WeatherIcon.HEAVY_RAIN;
            case 71: return Weather.WeatherIcon.LIGHT_SNOW;
            case 73: case 85: return Weather.WeatherIcon.SNOW;
            case 75: case 86: return Weather.WeatherIcon.HEAVY_SNOW;
            case 77: return Weather.WeatherIcon.SNOW_FLURRIES;
            case 80: case 81: return Weather.WeatherIcon.SHOWERS;
            case 95: return Weather.WeatherIcon.THUNDERSTORMS;
            case 96: case 99: return Weather.WeatherIcon.THUNDER_AND_HAIL;
            default: return Weather.WeatherIcon.UNKNOWN;
        }
    }

    /** km/h → 蒲福风级（GB WeatherSpec.windSpeedAsBeaufort 同一张表）*/
    static byte beaufort(double kmh) {
        double[] lim = {1, 6, 12, 20, 29, 39, 50, 62, 75, 89, 103, 118};
        for (int i = 0; i < lim.length; i++) if (kmh < lim[i]) return (byte) i;
        return 12;
    }

    private HuaweiPacket tryCall(HuaweiPacket req, long ms, String what) {
        try {
            HuaweiPacket r = rr.call(req, ms, what);
            int code = -1;
            try { code = r.getTlv() == null ? -1 : r.getTlv().getAsInteger(0x7f, -1); } catch (Throwable ignored) { }
            if (code != -1 && code != 0x000186A0) probe(what + " code=0x" + Integer.toHexString(code));
            return r;
        }
        catch (Throwable t) { probe(what + " no reply (" + t.getClass().getSimpleName() + ")"); return null; }
    }

    private static Byte b(JSONObject o, String k) { return o != null && o.has(k) && !o.isNull(k) ? (byte) Math.round(o.optDouble(k)) : null; }
    private static Integer i(JSONObject o, String k) { return o != null && o.has(k) && !o.isNull(k) ? (int) Math.round(o.optDouble(k)) : null; }

    public boolean send(JSONObject w) {
        if (w == null) return false;
        try {
            HuaweiPacket.ParamsProvider pp = rr.params();
            Weather.Settings s = new Weather.Settings();
            s.uvIndexSupported = uv;
            s.extendedHourlyForecast = extHourly;

            tryCall(new Weather.WeatherStart.Request(pp), 1500, "weatherStart");
            tryCall(new Weather.WeatherUnitRequest(pp, Weather.HuaweiTemperatureFormat.CELSIUS), 1500, "weatherUnit");

            HuaweiPacket sp = tryCall(new Weather.WeatherSupport.Request(pp), 1500, "weatherSupport");
            if (sp instanceof Weather.WeatherSupport.Response) {
                Weather.WeatherSupport.Response r = (Weather.WeatherSupport.Response) sp;
                s.weatherSupported = r.weatherSupported; s.windSupported = r.windSupported; s.pm25Supported = r.pm25Supported;
                s.temperatureSupported = r.temperatureSupported; s.locationNameSupported = r.locationNameSupported;
                s.currentTemperatureSupported = r.currentTemperatureSupported; s.unitSupported = r.unitSupported;
                s.airQualityIndexSupported = r.airQualityIndexSupported;
            } else {
                s.weatherSupported = true; s.windSupported = true; s.temperatureSupported = true; s.locationNameSupported = true;
                s.currentTemperatureSupported = true; s.unitSupported = true;
            }
            HuaweiPacket ep = tryCall(new Weather.WeatherExtendedSupport.Request(pp), 1500, "weatherExt");
            if (ep instanceof Weather.WeatherExtendedSupport.Response) {
                Weather.WeatherExtendedSupport.Response r = (Weather.WeatherExtendedSupport.Response) ep;
                s.timeSupported = r.timeSupported; s.sourceSupported = r.sourceSupported; s.weatherIconSupported = r.weatherIconSupported;
            } else { s.timeSupported = true; s.weatherIconSupported = true; }
            HuaweiPacket mp = tryCall(new Weather.WeatherSunMoonSupport.Request(pp), 1500, "weatherSunMoon");
            if (mp instanceof Weather.WeatherSunMoonSupport.Response) {
                Weather.WeatherSunMoonSupport.Response r = (Weather.WeatherSunMoonSupport.Response) mp;
                s.sunRiseSetSupported = r.sunRiseSetSupported; s.moonPhaseSupported = r.moonPhaseSupported;
            } else s.sunRiseSetSupported = true;
            probe("settings | icon=" + s.weatherIconSupported + " temp=" + s.temperatureSupported + " cur=" + s.currentTemperatureSupported
                    + " wind=" + s.windSupported + " loc=" + s.locationNameSupported + " sun=" + s.sunRiseSetSupported + " uv=" + s.uvIndexSupported);

            JSONObject cur = w.optJSONObject("cur"), today = w.optJSONObject("today");
            int code = cur == null ? -1 : cur.optInt("code", -1);
            Integer feel = i(cur, "feel");
            HuaweiPacket cr = new Weather.CurrentWeatherRequest(pp, s,
                    icon(code), i(cur, "dir"), cur == null ? null : beaufort(cur.optDouble("wind", 0)),
                    b(today, "min"), b(today, "max"), null, w.optString("loc", ""),
                    b(cur, "temp"), Weather.HuaweiTemperatureFormat.CELSIUS, null,
                    (int) w.optLong("t", System.currentTimeMillis() / 1000L),
                    cur != null && cur.has("uv") ? (float) cur.optDouble("uv") : null,
                    "Open-Meteo", b(cur, "hum"), i(cur, "wind"), feel);
            HuaweiPacket crr = tryCall(cr, 3000, "weatherCurrent");

            List<Weather.WeatherForecastData.TimeData> hs = new ArrayList<>();
            JSONArray ha = w.optJSONArray("hourly");
            for (int k = 0; ha != null && k < ha.length() && hs.size() < 24; k++) {
                JSONObject h = ha.getJSONObject(k);
                Weather.WeatherForecastData.TimeData td = new Weather.WeatherForecastData.TimeData();
                td.timestamp = (int) h.optLong("t");
                td.icon = icon(h.optInt("code", -1));
                td.temperature = b(h, "temp");
                td.precipitation = b(h, "pop");
                td.uvIndex = b(h, "uv");
                hs.add(td);
            }
            List<Weather.WeatherForecastData.DayData> ds = new ArrayList<>();
            JSONArray da = w.optJSONArray("daily");
            for (int k = 0; da != null && k < da.length() && ds.size() < 8; k++) {
                JSONObject d = da.getJSONObject(k);
                Weather.WeatherForecastData.DayData dd = new Weather.WeatherForecastData.DayData();
                dd.timestamp = (int) d.optLong("t");
                dd.icon = icon(d.optInt("code", -1));
                dd.highTemperature = b(d, "max");
                dd.lowTemperature = b(d, "min");
                dd.sunriseTime = i(d, "rise");
                dd.sunsetTime = i(d, "set");
                ds.add(dd);
            }
            HuaweiPacket frr = tryCall(new Weather.WeatherForecastData.Request(pp, s, hs, ds), 3000, "weatherForecast");
            probe("replies | cur=" + tlvHex(crr) + " fc=" + tlvHex(frr));
            probe("sent | loc=" + w.optString("loc", "") + " temp=" + b(cur, "temp") + " code=" + code + " hourly=" + hs.size() + " daily=" + ds.size());
            return true;
        } catch (Throwable t) {
            probe("fail | " + t.getClass().getSimpleName() + ":" + t.getMessage());
            return false;
        }
    }

    /** 手环来要天气（0x0f/0x04）时先回一个「收到」（GB SendWeatherDeviceRequest）*/
    public void ackDevice() {
        try { rr.sendOnly(new Weather.WeatherDeviceRequest(rr.params(), 0x000186A0)); } catch (Throwable ignored) { }
    }

    /** 没有天气数据时告诉手环（GB SendWeatherErrorRequest WEATHER_DISABLED），别让它一直转圈 */
    public void sendNoData(boolean extended) {
        try {
            HuaweiPacket p = extended
                    ? new Weather.WeatherErrorExtended.Request(rr.params(), Weather.ErrorCode.NETWORK_ERROR, false)
                    : new Weather.WeatherErrorSimple.Request(rr.params(), Weather.ErrorCode.NETWORK_ERROR);
            rr.sendOnly(p);
            probe("no data → error sent");
        } catch (Throwable ignored) { }
    }

    private static String tlvHex(HuaweiPacket p) {
        try {
            if (p == null || p.getTlv() == null) return "none";
            byte[] b = p.getTlv().serialize();
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < Math.min(b.length, 24); k++) sb.append(String.format("%02x", b[k]));
            return sb.toString();
        } catch (Throwable t) { return "?"; }
    }

    private static void probe(String s) {
        try { Probe.post("hwweather", s); } catch (Throwable ignored) { }
    }
}
