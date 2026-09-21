package com.edwardresearchlabs.now;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
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

import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10;
    private GeofencingClient geofencingClient;
    private FusedLocationProviderClient locationClient;
    private TextView status, radiusLabel, history;
    private SeekBar radiusBar;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        geofencingClient = LocationServices.getGeofencingClient(this);
        locationClient = LocationServices.getFusedLocationProviderClient(this);
        buildUi();
        refresh();
    }

    private TextView text(String value, int sp) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(sp); t.setTextColor(Color.rgb(20,20,20));
        t.setPadding(0,12,0,12); return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(16);
        b.setMinHeight(58); return b;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(44,60,44,60);
        root.setBackgroundColor(Color.WHITE);
        scroll.addView(root);

        TextView brand = text("NOW", 34);
        brand.setTextColor(Color.BLACK);
        root.addView(brand);
        root.addView(text("Passive place sensing · local-first", 16));

        status = text("", 18);
        status.setPadding(0,30,0,20);
        root.addView(status);

        radiusLabel = text("", 15);
        root.addView(radiusLabel);
        radiusBar = new SeekBar(this);
        radiusBar.setMax(250);
        radiusBar.setProgress(50); // 100 m
        radiusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { radiusLabel.setText("Detection radius: " + (50+p) + " m"); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        root.addView(radiusBar);

        Button save = button("Save my current place");
        save.setOnClickListener(v -> saveCurrentPlace());
        root.addView(save);

        Button background = button("Enable background location");
        background.setOnClickListener(v -> openAppSettings());
        root.addView(background);

        Button refresh = button("Refresh history");
        refresh.setOnClickListener(v -> refresh());
        root.addView(refresh);

        TextView h = text("VISIT HISTORY", 13);
        h.setPadding(0,40,0,8);
        root.addView(h);
        history = text("", 16);
        root.addView(history);

        TextView note = text("Privacy: no account, no server, no upload. Location and visit history stay on this phone.", 14);
        note.setPadding(0,38,0,30);
        root.addView(note);
        setContentView(scroll);
        radiusLabel.setText("Detection radius: 100 m");
    }

    private boolean foregroundGranted() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void saveCurrentPlace() {
        if (!foregroundGranted()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        float radius = 50 + radiusBar.getProgress();
        locationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener(loc -> {
                if (loc == null) { Toast.makeText(this, "Could not get location. Try again outdoors.", Toast.LENGTH_LONG).show(); return; }
                registerGeofence(loc.getLatitude(), loc.getLongitude(), radius);
            })
            .addOnFailureListener(e -> Toast.makeText(this, "Location error: " + e.getMessage(), Toast.LENGTH_LONG).show());
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
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER | Geofence.GEOFENCE_TRANSITION_DWELL | Geofence.GEOFENCE_TRANSITION_EXIT)
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
                    Toast.makeText(this, "Place saved. Sensing is active.", Toast.LENGTH_LONG).show();
                    refresh();
                })
                .addOnFailureListener(e -> Toast.makeText(this, "Geofence error: " + e.getMessage(), Toast.LENGTH_LONG).show())
        );
    }

    private void openAppSettings() {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
        startActivity(i);
        Toast.makeText(this, "Permissions → Location → Allow all the time", Toast.LENGTH_LONG).show();
    }

    private void refresh() {
        status.setText(VisitStore.placeSummary(this));
        history.setText(VisitStore.renderHistory(this));
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQ_LOCATION && foregroundGranted()) saveCurrentPlace();
    }
}
