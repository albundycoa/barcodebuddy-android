package de.bulling.barcodebuddyscanner.wear;

import android.content.ComponentName;
import android.content.Context;

import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

/** Sagt den Zifferblättern, dass sich Scanliste oder Einkaufsliste geändert haben. */
final class Complications {

	private Complications() {
	}

	static void refresh(Context context) {
		Context app = context.getApplicationContext();
		try {
			ComplicationDataSourceUpdateRequester
					.create(app, new ComponentName(app, ScansComplicationService.class)).requestUpdateAll();
			ComplicationDataSourceUpdateRequester
					.create(app, new ComponentName(app, ShoppingComplicationService.class)).requestUpdateAll();
		} catch (RuntimeException e) {
			// Ohne Zifferblatt mit dieser Komplikation gibt es nichts zu aktualisieren
		}
	}
}
