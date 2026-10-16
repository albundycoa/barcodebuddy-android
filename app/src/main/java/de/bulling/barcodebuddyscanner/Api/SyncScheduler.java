package de.bulling.barcodebuddyscanner.Api;

import android.content.Context;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Richtet die regelmäßige Synchronisierung im Hintergrund passend zur Einstellung
 * "Synchronisierung mit Grocy" ein oder schaltet sie aus. Gearbeitet wird nur im WLAN
 * (ungemessene Verbindung), der Grocy-Server steht ja im Heimnetz.
 */
public final class SyncScheduler {

	private static final String WORK_NAME = "grocy-sync";

	private SyncScheduler() {
	}

	public static void apply(Context context) {
		Context app = context.getApplicationContext();
		long hours = SharedPrefHelper.getBackgroundSyncHours(app);
		WorkManager manager = WorkManager.getInstance(app);
		if (hours <= 0 || !SharedPrefHelper.hasGrocy(app)) {
			manager.cancelUniqueWork(WORK_NAME);
			return;
		}
		PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SyncWorker.class, hours, TimeUnit.HOURS)
				.setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
				.build();
		// UPDATE: ein geändertes Intervall ersetzt das alte, ohne die Wartezeit zurückzusetzen
		manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request);
	}
}
