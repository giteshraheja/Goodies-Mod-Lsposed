package dev.gitesh.sbmods;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import org.json.JSONObject;

/**
 * Picks the settings header wallpaper: weather (when a city is set), else the sky by time of day, blended between
 * moods around the real sunrise and sunset. No background work: the weather is fetched at most once per 3 hours,
 * only while the settings app is open, with one tiny request that also brings sunrise, sunset and temperature.
 */
public final class WallTheme {
    public static final String K_MODE = "wallMode", K_CITY = "wallCity", K_WX = "wallWx", K_WXT = "wallWxT", K_WXC = "wallWxCity",
            K_LAT = "wallLat", K_LON = "wallLon", K_TEMP = "wallTemp", K_SR = "wallSr", K_SS = "wallSs", K_ALERT = "wallAlert";
    private static final long FRESH = 3 * 3600_000L, USABLE = 8 * 3600_000L, SUN_USABLE = 30 * 86400_000L;
    private static final int DAY = 86400, RISE = 75 * 60, FALL = 50 * 60;   // seconds: dawn to day, dusk to night
    private static final int HOT = 0xFFFFA63D, COLD = 0xFFA8DCFF;

    public static final String[] NAMES = {"Dawn", "Day", "Dusk", "Night", "Cloudy", "Rain", "Storm", "Snow", "Fog"};
    // gradient top, middle, bottom, glow
    private static final int[][] PAL = {
        {0xFF2A1B4D, 0xFF9A4F7A, 0xFFF0A070, 0xFFFFD0A0},
        {0xFF12407A, 0xFF2E78C8, 0xFF5AA5E0, 0xFFFFF0B8},
        {0xFF1E1448, 0xFF7A3A7A, 0xFFE0708A, 0xFFFFB070},
        {0xFF050818, 0xFF0E1A44, 0xFF1F2F78, 0xFF9FB4FF},
        {0xFF2B3442, 0xFF4A586C, 0xFF7384A0, 0xFFD8E0EC},
        {0xFF141C2A, 0xFF26364E, 0xFF3C5878, 0xFF7FA6D8},
        {0xFF0C0A18, 0xFF241E3E, 0xFF48386E, 0xFFFFE070},
        {0xFF2C3E58, 0xFF5A7498, 0xFFA8C0DC, 0xFFFFFFFF},
        {0xFF2E3338, 0xFF566068, 0xFF8A949A, 0xFFE0E4E6},
    };

    // calm red when the phone is hot, amber when the battery is low
    private static final int[] PAL_HOT = {0xFF1E0A10, 0xFF5E1C28, 0xFFA8443C, 0xFFFFB4A0},
            PAL_LOW = {0xFF1E1408, 0xFF5E4016, 0xFFB07E2C, 0xFFFFE0A0};
    public static final int HOT_TENTHS = 400, LOW_PCT = 15;
    private static int battPct = -1, battTenths;
    private static boolean charging;

    /** Reads battery level and temperature once from what Android already holds (no root, nothing left running). */
    public static void readPhone(Context c) {
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return;
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            battPct = lvl < 0 || scale <= 0 ? -1 : lvl * 100 / scale;
            battTenths = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
            charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        } catch (Throwable ignored) {}
    }

    /** 0 nothing, 1 the phone is hot, 2 the battery is low (and not charging). Heat comes first. */
    public static int alert(SharedPreferences p) {
        if (p.getInt(K_MODE, 0) != 1 || !p.getBoolean(K_ALERT, true)) return 0;
        if (battTenths >= HOT_TENTHS) return 1;
        return battPct >= 0 && battPct <= LOW_PCT && !charging ? 2 : 0;
    }

    private static String alertText(int a) {
        return a == 1 ? "Phone is hot · " + Math.round(battTenths / 10f) + "°C" : "Battery low · " + battPct + "%";
    }

    /** The cached weather belongs to the typed city and is recent enough to show. */
    private static boolean wxOk(SharedPreferences p) {
        String city = p.getString(K_CITY, "").trim();
        return !city.isEmpty() && city.equalsIgnoreCase(p.getString(K_WXC, ""))
                && System.currentTimeMillis() - p.getLong(K_WXT, 0) < USABLE;
    }

    /**
     * The sky at a moment: {from, to, how far 0..1} over the moods dawn 0, day 1, dusk 2, night 3. Times are unix
     * seconds; sunrise and sunset may be from another day (they move about a minute a day).
     */
    public static float[] sky(long now, long sunrise, long sunset) {
        long len = sunset - sunrise;
        long t = Math.floorMod(now - sunrise, (long) DAY);   // seconds since the latest sunrise
        if (t < RISE) return new float[]{0, 1, t / (float) RISE};
        if (t < len - RISE) return new float[]{1, 1, 0};
        if (t < len) return new float[]{1, 2, (t - (len - RISE)) / (float) RISE};
        if (t < len + FALL) return new float[]{2, 3, (t - len) / (float) FALL};
        if (t < DAY - FALL) return new float[]{3, 3, 0};
        return new float[]{3, 0, (t - (DAY - FALL)) / (float) FALL};
    }

    private static float[] skyNow(SharedPreferences p) {
        long now = System.currentTimeMillis() / 1000, sr = p.getLong(K_SR, 0), ss = p.getLong(K_SS, 0);
        String city = p.getString(K_CITY, "").trim();
        boolean real = !city.isEmpty() && city.equalsIgnoreCase(p.getString(K_WXC, "")) && sr > 0
                && System.currentTimeMillis() - p.getLong(K_WXT, 0) < SUN_USABLE
                && ss - sr >= 3 * 3600 && ss - sr <= 21 * 3600;
        if (!real) {   // no city (or far north in midsummer): 06:15 to 18:15 on this phone's clock
            Calendar c = Calendar.getInstance();
            c.set(Calendar.HOUR_OF_DAY, 6); c.set(Calendar.MINUTE, 15); c.set(Calendar.SECOND, 0);
            sr = c.getTimeInMillis() / 1000;
            ss = sr + 12 * 3600;
        }
        return sky(now, sr, ss);
    }

    private static int nearest(float[] s) { return (int) (s[2] < 0.5f ? s[0] : s[1]); }

    /** Mood index into NAMES / PAL, or -1 for the normal (smart) wallpaper. */
    public static int mood(SharedPreferences p) {
        if (p.getInt(K_MODE, 0) != 1) return -1;
        int w = wxOk(p) ? fromCode(p.getInt(K_WX, 0)) : -1;
        return w >= 0 ? w : nearest(skyNow(p));
    }

    /** Gradient top, middle, bottom and glow colour; null for the normal look. */
    public static int[] palette(SharedPreferences p) {
        if (p.getInt(K_MODE, 0) != 1) return null;
        int al = alert(p);
        if (al != 0) return (al == 1 ? PAL_HOT : PAL_LOW).clone();
        boolean wx = wxOk(p);
        int w = wx ? fromCode(p.getInt(K_WX, 0)) : -1;
        int[] out = new int[4];
        if (w >= 0) System.arraycopy(PAL[w], 0, out, 0, 4);
        else {
            float[] s = skyNow(p);
            for (int i = 0; i < 4; i++) out[i] = mix(PAL[(int) s[0]][i], PAL[(int) s[1]][i], s[2]);
        }
        if (wx && p.contains(K_TEMP)) out[3] = tint(out[3], p.getFloat(K_TEMP, 20));
        return out;
    }

    /** Hot days warm the glow, cold days cool it. */
    static int tint(int glow, float c) {
        if (c > 35) return mix(glow, HOT, 0.4f + 0.4f * Math.min(1, (c - 35) / 7));
        if (c < 10) return mix(glow, COLD, 0.4f + 0.4f * Math.min(1, (10 - c) / 12));
        return glow;
    }

    public static int mix(int a, int b, float f) {
        if (f <= 0) return a;
        if (f >= 1) return b;
        int o = 0;
        for (int sh = 0; sh <= 24; sh += 8) {
            int x = (a >>> sh) & 0xFF, y = (b >>> sh) & 0xFF;
            o |= (Math.round(x + (y - x) * f) & 0xFF) << sh;
        }
        return o;
    }

    public static String describe(SharedPreferences p) {
        int m = mood(p);
        if (m < 0) return "Normal violet wallpaper";
        if (alert(p) != 0) return alertText(alert(p));
        return NAMES[m] + (wxOk(p) ? " (weather in " + p.getString(K_WXC, "") + ")" : " (time of day)");
    }

    /** The small line under the title, e.g. "Rain · 24°C · Mumbai"; empty for the normal look. */
    public static String caption(SharedPreferences p) {
        int m = mood(p);
        if (m < 0) return "";
        if (alert(p) != 0) return alertText(alert(p));
        if (!wxOk(p)) return NAMES[m];
        int code = p.getInt(K_WX, 0);
        String s = code == 0 || code == 1 ? "Clear" : NAMES[m];
        if (p.contains(K_TEMP)) s += " · " + Math.round(p.getFloat(K_TEMP, 0)) + "°C";
        return s + " · " + p.getString(K_WXC, "");
    }

    /** Weather code (WMO) to mood; -1 for a clear sky (the time of day decides) or an unknown code. */
    private static int fromCode(int c) {
        if (c == 2 || c == 3) return 4;
        if (c == 45 || c == 48) return 8;
        if ((c >= 71 && c <= 77) || c == 85 || c == 86) return 7;
        if (c >= 95) return 6;
        if (c >= 51) return 5;
        return -1;
    }

    public static boolean stale(SharedPreferences p) {
        if (p.getInt(K_MODE, 0) != 1) return false;
        String city = p.getString(K_CITY, "").trim();
        if (city.isEmpty()) return false;
        return !city.equalsIgnoreCase(p.getString(K_WXC, "")) || !p.contains(K_SR) || System.currentTimeMillis() - p.getLong(K_WXT, 0) > FRESH;
    }

    /** Blocking: call off the main thread. Returns true when the cache changed. */
    public static boolean refresh(SharedPreferences p) {
        try {
            String city = p.getString(K_CITY, "").trim();
            if (city.isEmpty()) return false;
            double lat, lon;
            if (city.equalsIgnoreCase(p.getString(K_WXC, "")) && p.contains(K_LAT)) {
                lat = Double.longBitsToDouble(p.getLong(K_LAT, 0));
                lon = Double.longBitsToDouble(p.getLong(K_LON, 0));
            } else {
                JSONObject g = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                        + URLEncoder.encode(city, "UTF-8")));
                if (!g.has("results")) return false;
                JSONObject r = g.getJSONArray("results").getJSONObject(0);
                lat = r.getDouble("latitude");
                lon = r.getDouble("longitude");
            }
            JSONObject w = new JSONObject(get("https://api.open-meteo.com/v1/forecast?current=weather_code,temperature_2m"
                    + "&daily=sunrise,sunset&timezone=auto&timeformat=unixtime&forecast_days=1&latitude=" + lat + "&longitude=" + lon));
            JSONObject cur = w.getJSONObject("current"), day = w.optJSONObject("daily");
            SharedPreferences.Editor e = p.edit().putString(K_WXC, city).putInt(K_WX, cur.getInt("weather_code"))
                    .putLong(K_WXT, System.currentTimeMillis())
                    .putLong(K_LAT, Double.doubleToLongBits(lat)).putLong(K_LON, Double.doubleToLongBits(lon));
            double t = cur.optDouble("temperature_2m");
            if (Double.isNaN(t)) e.remove(K_TEMP); else e.putFloat(K_TEMP, (float) t);
            long sr = day == null || day.optJSONArray("sunrise") == null ? 0 : day.optJSONArray("sunrise").optLong(0);
            long ss = day == null || day.optJSONArray("sunset") == null ? 0 : day.optJSONArray("sunset").optLong(0);
            e.putLong(K_SR, sr).putLong(K_SS, ss).apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String get(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            for (String l; (l = r.readLine()) != null; ) sb.append(l);
            return sb.toString();
        } finally {
            c.disconnect();
        }
    }

    private WallTheme() {}
}
