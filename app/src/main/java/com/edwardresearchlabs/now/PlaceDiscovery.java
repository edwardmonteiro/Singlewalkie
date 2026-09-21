package com.edwardresearchlabs.now;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PlaceDiscovery {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    private PlaceDiscovery() {}

    public static final class Candidate {
        public final String name;
        public final String type;
        public final double lat;
        public final double lon;
        public final double distanceMeters;
        public int confidence;

        Candidate(String name, String type, double lat, double lon, double distanceMeters) {
            this.name = name;
            this.type = type;
            this.lat = lat;
            this.lon = lon;
            this.distanceMeters = distanceMeters;
        }
    }

    public interface Callback {
        void onSuccess(List<Candidate> candidates);
        void onError(String message);
    }

    public static void discover(double lat, double lon, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                String around = "(around:160," + lat + "," + lon + ")";
                String q = "[out:json][timeout:8];(" +
                        "nwr" + around + "[\"shop\"][\"name\"];" +
                        "nwr" + around + "[\"amenity\"][\"name\"];" +
                        "nwr" + around + "[\"tourism\"][\"name\"];" +
                        "nwr" + around + "[\"leisure\"][\"name\"];" +
                        "nwr" + around + "[\"office\"][\"name\"];" +
                        "nwr" + around + "[\"healthcare\"][\"name\"];" +
                        "nwr" + around + "[\"craft\"][\"name\"];" +
                        ");out center tags 30;";

                String endpoint = "https://overpass-api.de/api/interpreter?data=" +
                        URLEncoder.encode(q, StandardCharsets.UTF_8.name());

                HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(9000);
                conn.setRequestProperty("User-Agent", "NOW-EdwardResearchLabs/0.2 personal-pilot");
                conn.setRequestProperty("Accept", "application/json");

                int code = conn.getResponseCode();
                if (code != 200) {
                    callback.onError("Place lookup unavailable (" + code + ")");
                    conn.disconnect();
                    return;
                }

                StringBuilder body = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) body.append(line);
                }
                conn.disconnect();

                JSONObject root = new JSONObject(body.toString());
                JSONArray elements = root.optJSONArray("elements");
                List<Candidate> out = new ArrayList<>();
                if (elements != null) {
                    for (int i = 0; i < elements.length(); i++) {
                        JSONObject e = elements.optJSONObject(i);
                        if (e == null) continue;
                        JSONObject tags = e.optJSONObject("tags");
                        if (tags == null) continue;
                        String name = tags.optString("name", "").trim();
                        if (name.isEmpty()) continue;

                        double eLat = e.has("lat") ? e.optDouble("lat") : Double.NaN;
                        double eLon = e.has("lon") ? e.optDouble("lon") : Double.NaN;
                        JSONObject center = e.optJSONObject("center");
                        if ((Double.isNaN(eLat) || Double.isNaN(eLon)) && center != null) {
                            eLat = center.optDouble("lat", Double.NaN);
                            eLon = center.optDouble("lon", Double.NaN);
                        }
                        if (Double.isNaN(eLat) || Double.isNaN(eLon)) continue;

                        double d = distanceMeters(lat, lon, eLat, eLon);
                        if (d <= 180) out.add(new Candidate(name, typeOf(tags), eLat, eLon, d));
                    }
                }

                out.sort(Comparator.comparingDouble(c -> c.distanceMeters));

                List<Candidate> unique = new ArrayList<>();
                for (Candidate c : out) {
                    boolean duplicate = false;
                    for (Candidate u : unique) {
                        if (u.name.equalsIgnoreCase(c.name)) { duplicate = true; break; }
                    }
                    if (!duplicate) unique.add(c);
                    if (unique.size() == 5) break;
                }

                for (Candidate c : unique) c.confidence = baseConfidence(c.distanceMeters);
                if (unique.size() >= 2) {
                    double delta = unique.get(1).distanceMeters - unique.get(0).distanceMeters;
                    if (delta < 15) unique.get(0).confidence = Math.min(unique.get(0).confidence, 66);
                    else if (delta < 35) unique.get(0).confidence = Math.min(unique.get(0).confidence, 76);
                }

                callback.onSuccess(unique);
            } catch (Exception e) {
                callback.onError("Place lookup failed");
            }
        });
    }

    private static int baseConfidence(double d) {
        if (d <= 20) return 94;
        if (d <= 40) return 88;
        if (d <= 70) return 80;
        if (d <= 110) return 70;
        return 58;
    }

    private static String typeOf(JSONObject tags) {
        String[] keys = {"shop","amenity","tourism","leisure","office","healthcare","craft"};
        for (String k : keys) {
            String v = tags.optString(k, "");
            if (!v.isEmpty()) return k + ":" + v;
        }
        return "place";
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double r = 6371000.0;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = Math.toRadians(lat2 - lat1);
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp/2) * Math.sin(dp/2) +
                Math.cos(p1) * Math.cos(p2) * Math.sin(dl/2) * Math.sin(dl/2);
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
    }
}
