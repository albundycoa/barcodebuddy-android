package de.bulling.barcodebuddyscanner.Update;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.BuildConfig;

/**
 * Fragt beim GitHub-Release nach der neuesten Version. Die Adresse "releases/latest/download/..." führt immer zum
 * zuletzt veröffentlichten Release, braucht kein Konto und hat keine Abfragegrenze.
 */
public final class UpdateChecker {

	public static final String LATEST_URL =
			"https://github.com/albundycoa/barcodebuddy-android/releases/latest/download/latest.json";

	/** So oft wird von selbst nach Updates gefragt: höchstens alle 12 Stunden */
	private static final long AUTO_INTERVAL_MS = 12L * 60 * 60 * 1000;

	private static final String PREF_ENABLED    = "update_check";
	private static final String PREF_LAST_CHECK = "update_last_check";

	public interface Callback {
		/**
		 * @param newer die neuere Version, oder null, wenn die installierte aktuell ist
		 * @param error Fehlertext, oder null, wenn die Abfrage geklappt hat
		 */
		void onResult(UpdateInfo newer, String error);
	}

	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
	private static final Handler         MAIN     = new Handler(Looper.getMainLooper());

	private UpdateChecker() {
	}

	public static boolean isAutoCheckEnabled(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(PREF_ENABLED, true);
	}

	/** Ob jetzt von selbst nachgefragt werden soll: eingeschaltet und die letzte Abfrage ist lange genug her. */
	public static boolean shouldAutoCheck(Context context) {
		if (!isAutoCheckEnabled(context)) {
			return false;
		}
		long last = PreferenceManager.getDefaultSharedPreferences(context).getLong(PREF_LAST_CHECK, 0);
		return System.currentTimeMillis() - last >= AUTO_INTERVAL_MS;
	}

	public static void check(final Context context, final Callback callback) {
		final Context appContext = context.getApplicationContext();
		EXECUTOR.execute(() -> {
			UpdateInfo newer = null;
			String error = null;
			try {
				UpdateInfo latest = UpdateInfo.parse(download(LATEST_URL));
				PreferenceManager.getDefaultSharedPreferences(appContext).edit()
						.putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
				if (latest.isNewerThan(BuildConfig.VERSION_CODE)) {
					newer = latest;
				}
			} catch (IOException | IllegalArgumentException e) {
				error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			}
			final UpdateInfo result = newer;
			final String failure = error;
			MAIN.post(() -> callback.onResult(result, failure));
		});
	}

	private static String download(String address) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
		try {
			connection.setConnectTimeout(8000);
			connection.setReadTimeout(15000);
			int status = connection.getResponseCode();
			if (status != 200) {
				throw new IOException("HTTP " + status);
			}
			StringBuilder body = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null && body.length() < 64 * 1024) {
					body.append(line).append('\n');
				}
			}
			return body.toString();
		} finally {
			connection.disconnect();
		}
	}
}
