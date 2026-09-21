package com.edwardresearchlabs.now;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

public class GeofenceBroadcastReceiver extends BroadcastReceiver {
    private static final String CHANNEL_ID = "now_visits";

    @Override
    public void onReceive(Context context, Intent intent) {
        GeofencingEvent e = GeofencingEvent.fromIntent(intent);
        if (e == null || e.hasError()) return;

        String type;
        switch (e.getGeofenceTransition()) {
            case Geofence.GEOFENCE_TRANSITION_ENTER:
                type = "ENTER";
                break;
            case Geofence.GEOFENCE_TRANSITION_DWELL:
                type = "DWELL";
                break;
            case Geofence.GEOFENCE_TRANSITION_EXIT:
                type = "EXIT";
                break;
            default:
                return;
        }

        VisitStore.addEvent(context, type, System.currentTimeMillis());

        Intent update = new Intent(MainActivity.ACTION_VISIT_UPDATED);
        update.setPackage(context.getPackageName());
        context.sendBroadcast(update);

        if ("ENTER".equals(type) || "DWELL".equals(type)) {
            OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(AutoPlaceWorker.class).build();
            WorkManager.getInstance(context).enqueueUniqueWork(
                    "now-auto-place",
                    ExistingWorkPolicy.REPLACE,
                    work
            );
        }

        showNotification(context, type);
    }

    private void showNotification(Context context, String type) {
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager nm =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "NOW visit sensing",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Passive ENTER, DWELL and EXIT detections");
            nm.createNotificationChannel(channel);
        }

        Intent open = new Intent(context, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 202, open, flags);

        String place = VisitStore.currentPlaceName(context);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ID)
                : new Notification.Builder(context);

        b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("NOW · " + type)
                .setContentText(place)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true);

        nm.notify(303, b.build());
    }
}
