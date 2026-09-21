package com.edwardresearchlabs.now;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import com.google.android.gms.location.Geofence;
import com.google.android.gms.location.GeofencingEvent;

public class GeofenceBroadcastReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        GeofencingEvent e = GeofencingEvent.fromIntent(intent);
        if (e == null || e.hasError()) return;
        String type;
        switch (e.getGeofenceTransition()) {
            case Geofence.GEOFENCE_TRANSITION_ENTER: type = "ENTER"; break;
            case Geofence.GEOFENCE_TRANSITION_DWELL: type = "DWELL"; break;
            case Geofence.GEOFENCE_TRANSITION_EXIT: type = "EXIT"; break;
            default: return;
        }
        VisitStore.addEvent(context, type, System.currentTimeMillis());
    }
}
