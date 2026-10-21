package de.bulling.barcodebuddyscanner.wear;

import android.content.Context;
import android.content.SharedPreferences;

/** Einstellungen der Uhr-App (nur auf der Uhr gespeichert). */
final class Settings {

	private static final String PREFS = "settings";
	private static final String KEY_KEEP_SCANS = "keep_scans_visible";
	private static final String KEY_KEEP_SHOPPING = "keep_shopping_visible";

	private Settings() {
	}

	/**
	 * Bleibt das Display bei der Scanliste an (gedimmt in Ruhe, hell bei Bewegung oder Tippen)? Standard: ja, weil man beim
	 * Scannen nicht ständig die Uhr wecken will.
	 */
	static boolean keepScansVisible(Context context) {
		return prefs(context).getBoolean(KEY_KEEP_SCANS, true);
	}

	static void setKeepScansVisible(Context context, boolean keep) {
		prefs(context).edit().putBoolean(KEY_KEEP_SCANS, keep).apply();
	}

	/** Bleibt das Display bei der Einkaufsliste an (gedimmt, hell beim Anheben)? Standard: ja. */
	static boolean keepShoppingVisible(Context context) {
		return prefs(context).getBoolean(KEY_KEEP_SHOPPING, true);
	}

	static void setKeepShoppingVisible(Context context, boolean keep) {
		prefs(context).edit().putBoolean(KEY_KEEP_SHOPPING, keep).apply();
	}

	private static SharedPreferences prefs(Context context) {
		return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
