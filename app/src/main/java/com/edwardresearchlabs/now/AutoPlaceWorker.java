package com.edwardresearchlabs.now;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;

public class AutoPlaceWorker extends Worker {
    public AutoPlaceWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context c = getApplicationContext();
        if (!VisitStore.hasPlace(c)) return Result.success();

        try {
            List<PlaceDiscovery.Candidate> candidates =
                    PlaceDiscovery.discoverBlocking(VisitStore.lat(c), VisitStore.lon(c));

            if (!candidates.isEmpty()) {
                PlaceDiscovery.Candidate best = candidates.get(0);
                if (best.confidence >= 70) {
                    VisitStore.suggestPlace(c, best.name, best.confidence);
                }
            }

            Intent update = new Intent(MainActivity.ACTION_VISIT_UPDATED);
            update.setPackage(c.getPackageName());
            c.sendBroadcast(update);
            return Result.success();
        } catch (Exception e) {
            return Result.retry();
        }
    }
}
