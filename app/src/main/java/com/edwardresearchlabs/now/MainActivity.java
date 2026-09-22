package com.edwardresearchlabs.now;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingClient;
import com.google.android.gms.location.GeofencingRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.maplibre.android.MapLibre;
import org.maplibre.android.annotations.MarkerOptions;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.Style;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    public static final String ACTION_VISIT_UPDATED =
            "com.edwardresearchlabs.now.VISIT_UPDATED";

    private static final int REQ_LOCATION = 10;
    private static final int REQ_NOTIFICATION = 11;

    private GeofencingClient geofencingClient;
    private FusedLocationProviderClient locationClient;
    private TextView status, radiusLabel, visits, discoveryStatus;
    private SeekBar radiusBar;
    private LinearLayout candidates;
    private MapView mapView;
    private boolean visitReceiverRegistered = false;

    private final Handler liveHandler = new Handler(Looper.getMainLooper());
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (visits != null && VisitStore.hasActiveVisit(MainActivity.this)) {
                visits.setText(VisitStore.renderVisits(MainActivity.this));
            }
            liveHandler.postDelayed(this, 30000);
        }
    };

    private final BroadcastReceiver visitReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            refresh();
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        MapLibre.getInstance(this);
        VisitStore.migrateLegacyIfNeeded(this);
        geofencingClient = LocationServices.getGeofencingClient(this);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        buildUi(b);
        refresh();
        requestNotificationPermissionIfNeeded();
    }

    private TextView text(String value, int sp) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(20,20,20));
        t.setPadding(0,12,0,12);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setMinHeight(58);
        return b;
    }

    private void buildUi(Bundle state) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36,48,36,60);
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        TextView brand = text("NOW", 34);
        brand.setTextColor(Color.BLACK);
        root.addView(brand);
        root.addView(text("Passive visits · local-first", 16));

        mapView = new MapView(this);
        mapView.onCreate(state);
        root.addView(mapView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 520));

        status = text("", 18);
        status.setPadding(0,20,0,10);
        root.addView(status);

        radiusLabel = text("", 15);
        root.addView(radiusLabel);

        radiusBar = new SeekBar(this);
        radiusBar.setMax(250);
        radiusBar.setProgress(50);
        radiusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                radiusLabel.setText("Detection radius: " + (50+p) + " m");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(radiusBar);

        Button save = button("Set this place for passive sensing");
        save.setOnClickListener(v -> saveCurrentPlace());
        root.addView(save);

        Button inspect = button("Inspect / correct place");
        inspect.setOnClickListener(v -> discoverNearby());
        root.addView(inspect);

        discoveryStatus = text(
                "Place identification runs automatically. " +
                "Use Inspect only when you want to correct it.",
                14);
        root.addView(discoveryStatus);

        candidates = new LinearLayout(this);
        candidates.setOrientation(LinearLayout.VERTICAL);
        root.addView(candidates);

        Button background = button("Background location settings");
        background.setOnClickListener(v -> openAppSettings());
        root.addView(background);

        TextView h = text("VISITS", 13);
        h.setPadding(0,38,0,8);
        root.addView(h);

        visits = text("", 17);
        root.addView(visits);

        TextView note = text(
                "NOW groups raw geofence signals into visits. Duplicate ENTER, DWELL " +
                "and EXIT events are ignored. Existing V0.1 history is migrated automatically.",
                13);
        note.setPadding(0,36,0,30);
        root.addView(note);

        setContentView(scroll);
        radiusLabel.setText("Detection radius: 100 m");
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION
            );
        }
    }

    private boolean foregroundGranted() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void saveCurrentPlace() {
        if (!foregroundGranted()) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_LOCATION
            );
            return;
        }

        float radius = 50 + radiusBar.getProgress();
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc == null) {
                        Toast.makeText(
                                this,
                                "Could not get location. Try again outdoors.",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }
                    showOnMap(loc.getLatitude(), loc.getLongitude(), "You are here");
                    registerGeofence(loc.getLatitude(), loc.getLongitude(), radius);
                })
                .addOnFailureListener(e ->
                        Toast.makeText(
                                this,
                                "Location error: " + e.getMessage(),
                                Toast.LENGTH_LONG
                        ).show()
                );
    }

    private void discoverNearby() {
        if (!foregroundGranted()) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                    },
                    REQ_LOCATION
            );
            return;
        }

        discoveryStatus.setText("Checking nearby places…");
        candidates.removeAllViews();

        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(loc -> {
                    if (loc == null) {
                        discoveryStatus.setText("Could not get your current location.");
                        return;
                    }

                    showOnMap(loc.getLatitude(), loc.getLongitude(), "Current location");
                    PlaceDiscovery.discover(
                            loc.getLatitude(),
                            loc.getLongitude(),
                            new PlaceDiscovery.Callback() {
                                @Override
                                public void onSuccess(List<PlaceDiscovery.Candidate> result) {
                                    runOnUiThread(() -> renderCandidates(result));
                                }

                                @Override
                                public void onError(String message) {
                                    runOnUiThread(() -> discoveryStatus.setText(message));
                                }
                            }
                    );
                });
    }

    private void renderCandidates(List<PlaceDiscovery.Candidate> result) {
        candidates.removeAllViews();

        if (result.isEmpty()) {
            discoveryStatus.setText("No named places found within ~160 m.");
            return;
        }

        PlaceDiscovery.Candidate best = result.get(0);
        VisitStore.suggestPlace(this, best.name, best.confidence);
        status.setText(VisitStore.placeSummary(this));
        visits.setText(VisitStore.renderVisits(this));
        discoveryStatus.setText(
                "Likely: " + best.name + " · " + best.confidence +
                        "% · tap only to correct"
        );

        for (PlaceDiscovery.Candidate c : result) {
            Button b = button(String.format(
                    Locale.US,
                    "%s   ·   %.0f m   ·   %d%%",
                    c.name,
                    c.distanceMeters,
                    c.confidence
            ));
            b.setOnClickListener(v -> {
                VisitStore.confirmPlace(this, c.name, 100);
                showOnMap(c.lat, c.lon, c.name);
                status.setText(VisitStore.placeSummary(this));
                visits.setText(VisitStore.renderVisits(this));
                discoveryStatus.setText("Confirmed: " + c.name);
            });
            candidates.addView(b);
        }
    }

    private void showOnMap(double lat, double lon, String label) {
        mapView.getMapAsync(map -> map.setStyle(
                new Style.Builder().fromUri("https://demotiles.maplibre.org/style.json"),
                style -> {
                    map.clear();
                    LatLng p = new LatLng(lat, lon);
                    map.setCameraPosition(
                            new CameraPosition.Builder()
                                    .target(p)
                                    .zoom(16.5)
                                    .build()
                    );
                    map.addMarker(new MarkerOptions().position(p).title(label));
                }
        ));
    }

    private PendingIntent geofencePendingIntent() {
        Intent i = new Intent(this, GeofenceBroadcastReceiver.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
        return PendingIntent.getBroadcast(this, 101, i, flags);
    }

    private void registerGeofence(double lat, double lon, float radius) {
        if (!foregroundGranted()) return;

        Geofence g = new Geofence.Builder()
                .setRequestId("primary_place")
                .setCircularRegion(lat, lon, radius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                        Geofence.GEOFENCE_TRANSITION_ENTER |
                        Geofence.GEOFENCE_TRANSITION_DWELL |
                        Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .setLoiteringDelay(3 * 60 * 1000)
                .setNotificationResponsiveness(60 * 1000)
                .build();

        GeofencingRequest req = new GeofencingRequest.Builder()
                .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
                .addGeofences(Collections.singletonList(g))
                .build();

        geofencingClient.removeGeofences(geofencePendingIntent()).addOnCompleteListener(x ->
                geofencingClient.addGeofences(req, geofencePendingIntent())
                        .addOnSuccessListener(v -> {
                            VisitStore.savePlace(this, lat, lon, radius);
                            refresh();
                            discoverNearby();
                            Toast.makeText(
                                    this,
                                    "Passive visit sensing is active.",
                                    Toast.LENGTH_LONG
                            ).show();
                        })
                        .addOnFailureListener(e ->
                                Toast.makeText(
                                        this,
                                        "Geofence error: " + e.getMessage(),
                                        Toast.LENGTH_LONG
                                ).show()
                        )
        );
    }

    private void openAppSettings() {
        Intent i = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null)
        );
        startActivity(i);
        Toast.makeText(
                this,
                "Permissions → Location → Allow all the time",
                Toast.LENGTH_LONG
        ).show();
    }

    private void refresh() {
        status.setText(VisitStore.placeSummary(this));
        visits.setText(VisitStore.renderVisits(this));

        if (VisitStore.hasPlace(this)) {
            showOnMap(
                    VisitStore.lat(this),
                    VisitStore.lon(this),
                    VisitStore.currentPlaceName(this)
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grants
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_LOCATION && foregroundGranted()) {
            saveCurrentPlace();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        mapView.onStart();

        IntentFilter filter = new IntentFilter(ACTION_VISIT_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(visitReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(visitReceiver, filter);
        }
        visitReceiverRegistered = true;
        liveHandler.post(liveTick);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mapView.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        mapView.onPause();
        super.onPause();
    }

    @Override
    protected void onStop() {
        liveHandler.removeCallbacks(liveTick);
        if (visitReceiverRegistered) {
            unregisterReceiver(visitReceiver);
            visitReceiverRegistered = false;
        }
        mapView.onStop();
        super.onStop();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        mapView.onLowMemory();
    }

    @Override protected void onDestroy() {
        mapView.onDestroy();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        mapView.onSaveInstanceState(outState);
    }
}
