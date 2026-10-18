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


	/** Meldet von Barcode Buddy ab; Grocy-Verbindung, Scanner und alle anderen Einstellungen bleiben erhalten. */
	public static void clearSettings(final Context context) {
		SharedPreferences.Editor preferences = PreferenceManager.getDefaultSharedPreferences(context).edit();
		preferences.remove("url");
		preferences.remove("key");
		preferences.putBoolean("needsSetup", true);
		preferences.apply();
	}

	public static void setUnsafeSsl(final Context context, final boolean unsafe) {
		PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("unsafe", unsafe).apply();
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
	private static final String SCAN_METHOD_SPP     = "spp";
	private static final String SCAN_METHOD_CAMERA  = "camera";
	private static final String SCAN_METHOD_BOTH    = "both";
	private static final String SCAN_METHOD_SPP_CAMERA = "spp_camera";

	private static String scanMethod(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context)
				.getString("scan_method", SCAN_METHOD_SCANNER);
	}

	/** Bluetooth-Barcodescanner im Tastaturmodus (HID): "Barcodescanner" oder "Beides" */
	public static boolean isBtScannerEnabled(Context context) {
		String method = scanMethod(context);
		return SCAN_METHOD_SCANNER.equals(method) || SCAN_METHOD_BOTH.equals(method);
	}

	/** Bluetooth-Barcodescanner im seriellen Modus (SPP), läuft über einen Hintergrunddienst */
	/** Nach jedem Scan eine Benachrichtigung zeigen (wird auf die Smartwatch gespiegelt). */
	public static boolean isScanNotificationEnabled(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("scan_notification", true);
	}

	/**
	 * Scanner automatisch steuern (Standard an): beim Verbinden "Nie ausschalten", nach dem Senden des
	 * Einkaufs "30 Sekunden" und Ruhezustand.
	 */
	public static boolean isScannerAutoControl(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean("scanner_auto_control", true);
	}

	/** Abstand der Akku-Abfrage beim Scanner in Millisekunden (Standard 5 Minuten), 0 = nie automatisch. */
	public static long getBatteryIntervalMs(Context context) {
		String value = PreferenceManager.getDefaultSharedPreferences(context).getString("battery_interval", "5");
		try {
			return Math.max(0, Long.parseLong(value)) * 60_000L;
		} catch (NumberFormatException e) {
			return 5 * 60_000L;
		}
	}

	/** "SPP-Scanner" oder "SPP-Scanner + Kamera" */
	public static boolean isSppScannerEnabled(Context context) {
		String method = scanMethod(context);
		return SCAN_METHOD_SPP.equals(method) || SCAN_METHOD_SPP_CAMERA.equals(method);
	}

	/** Kamera-Scanner: "Kamera" oder eine der Kombinationen mit einem Scanner */
	public static boolean isCameraScannerEnabled(Context context) {
		String method = scanMethod(context);
		return SCAN_METHOD_CAMERA.equals(method) || SCAN_METHOD_BOTH.equals(method)
				|| SCAN_METHOD_SPP_CAMERA.equals(method);
	}

	// ---- SPP-Scanner ----

	/** Scanner-Dienst dauerhaft aktiv halten (statt nur, solange der Scanner verbunden ist). */
	public static boolean isScannerAlwaysOn(Context context) {
		return "always".equals(PreferenceManager.getDefaultSharedPreferences(context)
				.getString("scanner_standby", "connected"));
	}

	public static String getSppAddress(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString("spp_address", "");
	}

	public static String getSppName(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString("spp_name", "");
	}

	public static void saveSppDevice(Context context, String address, String name) {
		PreferenceManager.getDefaultSharedPreferences(context).edit()
				.putString("spp_address", address)
				.putString("spp_name", name)
				.apply();
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

	/** Bekannte Produkte direkt in Grocy buchen (Standard) oder alles über Barcode Buddy. */
	public static boolean isDirectBookingEnabled(Context context) {
		return !"bb".equals(PreferenceManager.getDefaultSharedPreferences(context).getString("booking_mode", "grocy"));
	}

	private static String syncMode(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getString("sync_mode", "open");
	}

	/** Beim Öffnen der App automatisch synchronisieren (alle Modi außer "Nur manuell"). */
	public static boolean isSyncOnOpenEnabled(Context context) {
		return !"manual".equals(syncMode(context));
	}

	/** Intervall der Hintergrund-Synchronisierung in Stunden, 0 = aus. */
	public static long getBackgroundSyncHours(Context context) {
		switch (syncMode(context)) {
			case "bg1":
				return 1;
			case "bg6":
				return 6;
			case "bg24":
				return 24;
			default:
				return 0;
		}
	}

	/** Version der lokalen Datenbank-Kopie, damit nach einem App-Update neu synchronisiert wird. */
	public static boolean isGrocyCacheCurrent(Context context, int version) {
		return PreferenceManager.getDefaultSharedPreferences(context).getInt("grocy_cache_version", 0) == version;
	}

	public static void setGrocyCacheVersion(Context context, int version) {
		PreferenceManager.getDefaultSharedPreferences(context).edit().putInt("grocy_cache_version", version).apply();
	}

	/** Von Hand abgehakte Produkte beim Senden einbuchen ("book", Standard) oder nur in Grocy abhaken ("mark"). */
	public static boolean isManualDoneBooked(Context context) {
		return !"mark".equals(PreferenceManager.getDefaultSharedPreferences(context).getString("manual_done_mode", "book"));
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
