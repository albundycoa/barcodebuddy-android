package de.bulling.barcodebuddyscanner.Api;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Hintergrund-Synchronisierung mit Grocy, ausgelöst von {@link SyncScheduler}.
 */
public class SyncWorker extends Worker {

	public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
		super(context, params);
	}

	@NonNull
	@Override
	public Result doWork() {
		// Bei einem Fehler (z. B. Server gerade nicht erreichbar) versucht WorkManager es später erneut
		return GrocySync.syncBlocking(getApplicationContext()) ? Result.success() : Result.retry();
	}
}
