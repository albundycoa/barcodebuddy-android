package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.preference.PreferenceManager;

import de.bulling.barcodebuddyscanner.Api.BBApi;
import de.bulling.barcodebuddyscanner.Api.GrocyClient;

public class SharedPrefHelper {

	private final SharedPreferences globalPreferences;

	public static boolean noApiDetailsSaved(final Context context) {
		SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
		return preferences.getBoolean("needsSetup", true);
	}

	public static void saveApiDetails(final Context context, final String url, final String key, final boolean isUnsafe) {
		SharedPreferences.Editor preferences = PreferenceManager.getDefaultSharedPreferences(context).edit();
		preferences.putBoolean("needsSetup", false);
		preferences.putString("url", url);
		preferences.putString("key", key);
		preferences.putBoolean("unsafe", isUnsafe);
		preferences.apply();
	}


	public static void clearSettings(final Context context) {
		SharedPreferences.Editor preferences = PreferenceManager.getDefaultSharedPreferences(context).edit();
		preferences.clear();
		preferences.putBoolean("needsSetup", true);
		preferences.apply();
	}

	public SharedPrefHelper(final Context context) {
		this.globalPreferences = PreferenceManager.getDefaultSharedPreferences(context);
	}

	public boolean isVibrationEnabled() {
		return this.globalPreferences.getBoolean("use_vibration", true);
	}

	public boolean isSoundEnabled() {
		return this.globalPreferences.getBoolean("use_beep", true);
	}

	private static final String SCAN_METHOD_SCANNER = "scanner";
	private static final String SCAN_METHOD_CAMERA  = "camera";

	private static String scanMethod(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context)
				.getString("scan_method", SCAN_METHOD_SCANNER);
	}

	/** Bluetooth-Barcodescanner im Tastaturmodus: "Barcodescanner" oder "Beides" */
	public static boolean isBtScannerEnabled(Context context) {
		return !SCAN_METHOD_CAMERA.equals(scanMethod(context));
	}

	/** Kamera-Scanner: "Kamera" oder "Beides" */
	public static boolean isCameraScannerEnabled(Context context) {
		return !SCAN_METHOD_SCANNER.equals(scanMethod(context));
	}

	public float getBeepVolume() {
		int volInInt = this.globalPreferences.getInt("volume_beep", 5);
		return (volInInt / 20f);
	}

	public final static int NO_ORIENTATION_CHANGE = -1;


	@SuppressWarnings("ConstantConditions")
	public int getPreferredOrientation() {
		int orientation = Integer.parseInt(this.globalPreferences.getString("orientation", "0"));
		switch (orientation) {
			case 1:
				return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
			case 2:
				return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
			default:
				return NO_ORIENTATION_CHANGE;
		}
	}

	// ---- Grocy ----

	/** Ob ungültige SSL-Zertifikate akzeptiert werden (gilt für Barcode Buddy und Grocy). */
	public static boolean isUnsafeSsl(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("unsafe", false);
	}

	public static boolean hasGrocy(Context context) {
		SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
		return !p.getString("grocy_url", "").isEmpty() && !p.getString("grocy_key", "").isEmpty();
	}

	public static void saveGrocy(Context context, String url, String key) {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
				.putString("grocy_url", GrocyClient.normalizeUrl(url))
				.putString("grocy_key", key)
				.putLong("grocy_last_sync", 0)
				.apply();
	}

	public static void clearGrocy(Context context) {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
				.remove("grocy_url").remove("grocy_key")
				.remove("grocy_last_sync").remove("grocy_summary")
				.apply();
	}

	public static String getGrocyUrl(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString("grocy_url", "");
	}

	public static long getGrocyLastSync(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getLong("grocy_last_sync", 0);
	}

	public static String getGrocySummary(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString("grocy_summary", "");
	}

	public static void saveGrocySync(Context context, long time, String summary) {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
				.putLong("grocy_last_sync", time)
				.putString("grocy_summary", summary)
				.apply();
	}

	/** null, wenn Grocy nicht gekoppelt ist. */
	public GrocyClient initGrocyClient() {
		String url = this.globalPreferences.getString("grocy_url", "");
		String key = this.globalPreferences.getString("grocy_key", "");
		if (url.isEmpty() || key.isEmpty()) {
			return null;
		}
		return new GrocyClient(url, key, this.globalPreferences.getBoolean("unsafe", false));
	}

	/** Adresse der Barcode-Buddy-Weboberfläche, abgeleitet aus der API-Adresse. */
	public String getBbWebUrl() {
		String url = this.globalPreferences.getString("url", "");
		if (url.endsWith("/")) {
			url = url.substring(0, url.length() - 1);
		}
		if (url.endsWith("/api")) {
			url = url.substring(0, url.length() - 4);
		}
		return url + "/";
	}

	public BBApi initBBApi() {
		return new BBApi(this.globalPreferences.getString("url", null),
				this.globalPreferences.getString("key", null),
				this.globalPreferences.getBoolean("unsafe", false));
	}

}
