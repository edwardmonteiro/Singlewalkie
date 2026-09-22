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

    // Legacy V0.1/V0.2 event storage. Kept only for one-time migration.
    private static final String LEGACY_EVENTS = "events";

    private static final String VISITS = "visits_v3";
    private static final String MIGRATED = "visits_v3_migrated";
    private static final String STATE = "visit_state";
    private static final String CURRENT_START = "current_visit_start";
    private static final String STATE_OUTSIDE = "OUTSIDE";
    private static final String STATE_INSIDE = "INSIDE";
    private static final String STATE_DWELLING = "DWELLING";

    private VisitStore() {}

    public static synchronized boolean processTransition(Context c, String type, long ts) {
        migrateLegacyIfNeeded(c);
        SharedPreferences p = prefs(c);
        String state = p.getString(STATE, STATE_OUTSIDE);

        if ("ENTER".equals(type)) {
            if (!STATE_OUTSIDE.equals(state)) return false; // duplicate ENTER
            p.edit()
                    .putString(STATE, STATE_INSIDE)
                    .putLong(CURRENT_START, ts)
                    .apply();
            return true;
        }

        if ("DWELL".equals(type)) {
            if (STATE_DWELLING.equals(state)) return false; // duplicate DWELL

            if (STATE_OUTSIDE.equals(state)) {
                // Defensive recovery if Android delivered DWELL without ENTER.
                p.edit()
                        .putLong(CURRENT_START, ts)
                        .putString(STATE, STATE_DWELLING)
                        .apply();
            } else {
                p.edit().putString(STATE, STATE_DWELLING).apply();
            }
            return true;
        }

        if ("EXIT".equals(type)) {
            if (STATE_OUTSIDE.equals(state)) return false; // duplicate EXIT

            long start = p.getLong(CURRENT_START, ts);
            appendVisit(c, start, ts, STATE_DWELLING.equals(state));
            p.edit()
                    .putString(STATE, STATE_OUTSIDE)
                    .remove(CURRENT_START)
                    .apply();
            return true;
        }

        return false;
    }

    private static void appendVisit(Context c, long start, long end, boolean dwelled) {
        try {
            SharedPreferences p = prefs(c);
            JSONArray visits = new JSONArray(p.getString(VISITS, "[]"));

            JSONObject v = new JSONObject();
            v.put("startTs", start);
            v.put("endTs", end);
            v.put("durationMin", Math.max(0, Math.round((end - start) / 60000.0)));
            v.put("placeName", p.getString("place_name", "Unknown place"));
            v.put("confidence", p.getInt("place_confidence", 0));
            v.put("confirmed", p.getBoolean("place_confirmed", false));
            v.put("dwelled", dwelled);

            visits.put(v);
            visits = trim(visits, 200);
            p.edit().putString(VISITS, visits.toString()).apply();
        } catch (Exception ignored) {}
    }

    private static JSONArray trim(JSONArray source, int max) throws Exception {
        if (source.length() <= max) return source;
        JSONArray out = new JSONArray();
        int start = source.length() - max;
        for (int i = start; i < source.length(); i++) out.put(source.get(i));
        return out;
    }

    public static synchronized void migrateLegacyIfNeeded(Context c) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean(MIGRATED, false)) return;

        try {
            JSONArray events = new JSONArray(p.getString(LEGACY_EVENTS, "[]"));
            JSONArray visits = new JSONArray();

            String state = STATE_OUTSIDE;
            long currentStart = 0;
            boolean dwelled = false;

            for (int i = 0; i < events.length(); i++) {
                JSONObject e = events.optJSONObject(i);
                if (e == null) continue;

                String type = e.optString("type", "");
                long ts = e.optLong("ts", 0);
                if (ts <= 0) continue;

                if ("ENTER".equals(type)) {
                    if (STATE_OUTSIDE.equals(state)) {
                        currentStart = ts; // first ENTER wins
                        state = STATE_INSIDE;
                        dwelled = false;
                    }
                    // Later ENTERs are intentionally ignored.
                } else if ("DWELL".equals(type)) {
                    if (STATE_OUTSIDE.equals(state)) {
                        currentStart = ts;
                    }
                    state = STATE_DWELLING;
                    dwelled = true;
                } else if ("EXIT".equals(type)) {
                    if (!STATE_OUTSIDE.equals(state) && currentStart > 0) {
                        JSONObject v = new JSONObject();
                        v.put("startTs", currentStart);
                        v.put("endTs", ts);
                        v.put("durationMin", Math.max(0, Math.round((ts - currentStart) / 60000.0)));
                        v.put("placeName", e.optString("placeName",
                                p.getString("place_name", "Unknown place")));
                        v.put("confidence", p.getInt("place_confidence", 0));
                        v.put("confirmed", p.getBoolean("place_confirmed", false));
                        v.put("dwelled", dwelled);
                        visits.put(v);
                    }

                    state = STATE_OUTSIDE;
                    currentStart = 0;
                    dwelled = false;
                }
            }

            SharedPreferences.Editor ed = p.edit()
                    .putString(VISITS, visits.toString())
                    .putString(STATE, state)
                    .putBoolean(MIGRATED, true);

            if (!STATE_OUTSIDE.equals(state) && currentStart > 0) {
                ed.putLong(CURRENT_START, currentStart);
            } else {
                ed.remove(CURRENT_START);
            }

            ed.apply();
        } catch (Exception e) {
            // Never block sensing because of corrupt legacy history.
            p.edit()
                    .putBoolean(MIGRATED, true)
                    .putString(STATE, STATE_OUTSIDE)
                    .apply();
        }
    }

    public static String renderVisits(Context c) {
        migrateLegacyIfNeeded(c);

        try {
            SharedPreferences p = prefs(c);
            StringBuilder s = new StringBuilder();

            String state = p.getString(STATE, STATE_OUTSIDE);
            if (!STATE_OUTSIDE.equals(state)) {
                long start = p.getLong(CURRENT_START, 0);
                if (start > 0) {
                    long now = System.currentTimeMillis();
                    long minutes = Math.max(0, Math.round((now - start) / 60000.0));

                    s.append("CURRENT VISIT\n");
                    s.append(displayPlace(p)).append("\n");
                    s.append(formatTime(start)).append(" → now");
                    s.append("   ·   ").append(minutes).append(" min\n");
                    s.append(STATE_DWELLING.equals(state) ? "Dwelling" : "Inside");
                    appendConfidence(s, p.getInt("place_confidence", 0),
                            p.getBoolean("place_confirmed", false));
                    s.append("\n\n");
                }
            }

            JSONArray visits = new JSONArray(p.getString(VISITS, "[]"));
            if (visits.length() == 0 && s.length() == 0) {
                return "No visits yet.\n\nSensing runs automatically after setup.";
            }

            for (int i = visits.length() - 1; i >= 0; i--) {
                JSONObject v = visits.optJSONObject(i);
                if (v == null) continue;

                long start = v.optLong("startTs", 0);
                long end = v.optLong("endTs", 0);
                long duration = v.optLong("durationMin", 0);
                String place = v.optString("placeName", "Unknown place");
                int confidence = v.optInt("confidence", 0);
                boolean confirmed = v.optBoolean("confirmed", false);

                s.append(place).append("\n");
                s.append(formatTime(start)).append(" → ").append(formatTime(end));
                s.append("   ·   ").append(duration).append(" min");
                appendConfidence(s, confidence, confirmed);
                s.append("\n\n");
            }

            return s.toString();
        } catch (Exception e) {
            return "Visit history unavailable.";
        }
    }

    private static String displayPlace(SharedPreferences p) {
        String name = p.getString("place_name", "Identifying place…");
        int confidence = p.getInt("place_confidence", 0);
        if (confidence == 0 && "Saved place".equals(name)) return "Identifying place…";
        return name;
    }

    private static void appendConfidence(StringBuilder s, int confidence, boolean confirmed) {
        if (confirmed) {
            s.append("   ·   confirmed");
        } else if (confidence > 0) {
            s.append("   ·   ").append(confidence).append("%");
        }
    }

    private static String formatTime(long ts) {
        if (ts <= 0) return "—";
        return new SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                .format(new Date(ts));
    }

    public static boolean hasActiveVisit(Context c) {
        migrateLegacyIfNeeded(c);
        return !STATE_OUTSIDE.equals(prefs(c).getString(STATE, STATE_OUTSIDE));
    }

    public static void savePlace(Context c, double lat, double lon, float radius) {
        prefs(c).edit()
                .putLong("lat", Double.doubleToRawLongBits(lat))
                .putLong("lon", Double.doubleToRawLongBits(lon))
                .putFloat("radius", radius)
                .putBoolean("has_place", true)
                .putString("place_name", "Identifying place…")
                .putInt("place_confidence", 0)
                .putBoolean("place_confirmed", false)
                .apply();
    }

    public static void confirmPlace(Context c, String name, int confidence) {
        prefs(c).edit()
                .putString("place_name", name)
                .putInt("place_confidence", confidence)
                .putBoolean("place_confirmed", true)
                .apply();
    }

    public static void suggestPlace(Context c, String name, int confidence) {
        SharedPreferences p = prefs(c);
        if (p.getBoolean("place_confirmed", false)) return;

        p.edit()
                .putString("place_name", name)
                .putInt("place_confidence", confidence)
                .putBoolean("place_confirmed", false)
                .apply();
    }

    public static String currentPlaceName(Context c) {
        return prefs(c).getString("place_name", "Identifying place…");
    }

    public static boolean hasPlace(Context c) {
        return prefs(c).getBoolean("has_place", false);
    }

    public static double lat(Context c) {
        return Double.longBitsToDouble(prefs(c).getLong("lat", 0));
    }

    public static double lon(Context c) {
        return Double.longBitsToDouble(prefs(c).getLong("lon", 0));
    }

    public static float radius(Context c) {
        return prefs(c).getFloat("radius", 100f);
    }

    public static String placeSummary(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("has_place", false)) return "No sensing place configured";

        double lat = Double.longBitsToDouble(p.getLong("lat", 0));
        double lon = Double.longBitsToDouble(p.getLong("lon", 0));
        float r = p.getFloat("radius", 100f);
        String name = p.getString("place_name", "Identifying place…");
        boolean confirmed = p.getBoolean("place_confirmed", false);
        int confidence = p.getInt("place_confidence", 0);

        String label = name;
        if (confirmed) label += " · confirmed";
        else if (confidence > 0) label += " · " + confidence + "% likely";

        return String.format(Locale.US,
                "%s\n%.5f, %.5f  ·  %.0f m radius",
                label, lat, lon, r);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
