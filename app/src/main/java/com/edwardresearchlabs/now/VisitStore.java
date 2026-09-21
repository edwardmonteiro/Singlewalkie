package com.edwardresearchlabs.now;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class VisitStore {
    private static final String PREF = "now_store";
    private static final String EVENTS = "events";
    private static final String ENTER_TS = "enter_ts";

    private VisitStore() {}

    public static void addEvent(Context c, String type, long ts) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString(EVENTS, "[]"));
            JSONObject o = new JSONObject();
            o.put("type", type);
            o.put("ts", ts);
            o.put("placeName", p.getString("place_name", "Saved place"));
            if ("ENTER".equals(type)) p.edit().putLong(ENTER_TS, ts).apply();
            if ("EXIT".equals(type)) {
                long enter = p.getLong(ENTER_TS, 0);
                if (enter > 0) o.put("durationMin", Math.max(0, (ts - enter) / 60000));
                p.edit().remove(ENTER_TS).apply();
            }
            a.put(o);
            while (a.length() > 100) {
                JSONArray b = new JSONArray();
                for (int i = 1; i < a.length(); i++) b.put(a.get(i));
                a = b;
            }
            p.edit().putString(EVENTS, a.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static String renderHistory(Context c) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(p.getString(EVENTS, "[]"));
            if (a.length() == 0) return "No visits yet.\n\nSensing is passive after setup.";
            StringBuilder s = new StringBuilder();
            SimpleDateFormat f = new SimpleDateFormat("MMM d  HH:mm", Locale.getDefault());
            for (int i = a.length() - 1; i >= 0; i--) {
                JSONObject o = a.getJSONObject(i);
                String type = o.optString("type");
                long ts = o.optLong("ts");
                String place = o.optString("placeName", "Saved place");
                s.append(place).append("\n");
                s.append(type).append("   ").append(f.format(new Date(ts)));
                if (o.has("durationMin")) s.append("   ·   ").append(o.optLong("durationMin")).append(" min");
                s.append("\n\n");
            }
            return s.toString();
        } catch (Exception e) {
            return "History unavailable.";
        }
    }

    public static void savePlace(Context c, double lat, double lon, float radius) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putLong("lat", Double.doubleToRawLongBits(lat))
                .putLong("lon", Double.doubleToRawLongBits(lon))
                .putFloat("radius", radius)
                .putBoolean("has_place", true)
                .apply();
    }

    public static void confirmPlace(Context c, String name, int confidence) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString("place_name", name)
                .putInt("place_confidence", confidence)
                .putBoolean("place_confirmed", true)
                .apply();
    }

    public static void suggestPlace(Context c, String name, int confidence) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (p.getBoolean("place_confirmed", false)) return;
        p.edit()
                .putString("place_name", name)
                .putInt("place_confidence", confidence)
                .putBoolean("place_confirmed", false)
                .apply();
    }

    public static String currentPlaceName(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString("place_name", "Saved place");
    }

    public static boolean hasPlace(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("has_place", false);
    }

    public static double lat(Context c) {
        return Double.longBitsToDouble(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("lat", 0));
    }

    public static double lon(Context c) {
        return Double.longBitsToDouble(c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("lon", 0));
    }

    public static float radius(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("radius", 100f);
    }

    public static String placeSummary(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        if (!p.getBoolean("has_place", false)) return "No place saved";
        double lat = Double.longBitsToDouble(p.getLong("lat", 0));
        double lon = Double.longBitsToDouble(p.getLong("lon", 0));
        float r = p.getFloat("radius", 100f);
        String name = p.getString("place_name", "Identifying place…");
        boolean confirmed = p.getBoolean("place_confirmed", false);
        int confidence = p.getInt("place_confidence", 0);
        String label = confirmed ? name + " · confirmed" : name;
        if (confidence > 0 && !confirmed) label += " · " + confidence + "% likely";
        return String.format(Locale.US, "%s\n%.5f, %.5f  ·  %.0f m radius", label, lat, lon, r);
    }
}
