package de.bulling.barcodebuddyscanner.Update;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import de.bulling.barcodebuddyscanner.BuildConfig;
import de.bulling.barcodebuddyscanner.R;

/**
 * Fragt nach Updates, lädt die neue APK in den eigenen Cache-Ordner, prüft die Prüfsumme und startet die
 * Installation direkt aus der App.
 * <p>
 * Warum nicht einfach in "Downloads" ablegen: dann öffnet eine andere App die Datei, und die braucht die Erlaubnis
 * "Unbekannte Apps installieren". So braucht nur diese App die Erlaubnis, und zwar einmal. Android prüft bei der
 * Installation selbst, dass die Signatur zur installierten App passt: ein fremdes APK lässt sich nicht
 * drüberinstallieren.
 */
public final class AppUpdater {

	private static final String PREF_PENDING = "update_pending";

	private final Activity activity;
	private volatile boolean cancelled;

	public AppUpdater(Activity activity) {
		this.activity = activity;
	}

	/**
	 * Fragt nach einer neueren Version und bietet sie an.
	 *
	 * @param manual true bei "Nach Updates suchen": dann meldet die App auch "aktuell" und Fehler; von selbst
	 *               bleibt sie still, solange es nichts zu tun gibt
	 */
	public void checkAndOffer(final boolean manual) {
		UpdateChecker.check(activity, (newer, error) -> {
			if (activity.isFinishing() || activity.isDestroyed()) {
				return;
			}
			if (error != null) {
				if (manual) {
					Toast.makeText(activity, activity.getString(R.string.update_check_failed, error), Toast.LENGTH_LONG).show();
				}
			} else if (newer == null) {
				if (manual) {
					Toast.makeText(activity, activity.getString(R.string.update_none, BuildConfig.VERSION_NAME),
							Toast.LENGTH_LONG).show();
				}
			} else {
				offer(newer);
			}
		});
	}

	private void offer(final UpdateInfo info) {
		StringBuilder message = new StringBuilder(activity.getString(R.string.update_available_text,
				BuildConfig.VERSION_NAME, info.version,
				String.format(java.util.Locale.GERMANY, "%.1f", info.size / 1024.0 / 1024.0)));
		if (info.notes != null && !info.notes.trim().isEmpty()) {
			message.append("\n\n").append(info.notes.trim());
		}
		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.update_available_title)
				.setMessage(message.toString())
				.setPositiveButton(R.string.update_install, (d, w) -> start(info))
				.setNegativeButton(R.string.update_later, null)
				.show();
	}

	private boolean canInstall() {
		return activity.getPackageManager().canRequestPackageInstalls();
	}

	/** Update laden und installieren; fragt vorher (einmalig) nach der Erlaubnis dafür. */
	private void start(final UpdateInfo info) {
		if (canInstall()) {
			download(info);
			return;
		}
		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.update_permission_title)
				.setMessage(R.string.update_permission_text)
				.setPositiveButton(R.string.update_permission_continue, (d, w) -> {
					prefs().edit().putString(PREF_PENDING, System.currentTimeMillis() + "|" + info.toJson()).apply();
					try {
						activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
								Uri.parse("package:" + activity.getPackageName())));
					} catch (ActivityNotFoundException e) {
						prefs().edit().remove(PREF_PENDING).apply();
						Toast.makeText(activity, R.string.update_settings_failed, Toast.LENGTH_LONG).show();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Aus der Einstellungsseite zurück: War die Erlaubnis erteilt, geht es mit dem Download weiter. */
	public void onResume() {
		String stored = prefs().getString(PREF_PENDING, null);
		if (stored == null) {
			return;
		}
		prefs().edit().remove(PREF_PENDING).apply();
		int bar = stored.indexOf('|');
		if (bar < 0) {
			return;
		}
		// Nur kurz nach dem Wechsel in die Einstellungen weitermachen, nicht Stunden später überraschend laden
		long age;
		try {
			age = System.currentTimeMillis() - Long.parseLong(stored.substring(0, bar));
		} catch (NumberFormatException e) {
			return;
		}
		if (age < 0 || age > 10 * 60_000L) {
			return;
		}
		try {
			UpdateInfo info = UpdateInfo.parse(stored.substring(bar + 1));
			if (canInstall()) {
				download(info);
			} else {
				Toast.makeText(activity, R.string.update_not_allowed, Toast.LENGTH_LONG).show();
			}
		} catch (IllegalArgumentException ignored) {
			// beschädigter Merker: nichts tun
		}
	}

	private android.content.SharedPreferences prefs() {
		return PreferenceManager.getDefaultSharedPreferences(activity);
	}

	private void download(final UpdateInfo info) {
		final File dir = new File(activity.getCacheDir(), "apk");
		dir.mkdirs();
		File[] old = dir.listFiles();
		if (old != null) {
			for (File f : old) {
				f.delete();
			}
		}
		final File target = new File(dir, "barcodebuddy-" + info.version + ".apk");
		cancelled = false;

		final ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
		bar.setIndeterminate(true);
		bar.setMax(100);
		int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
		bar.setPadding(pad, pad, pad, pad);
		final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.update_downloading)
				.setView(bar)
				.setCancelable(false)
				.setNegativeButton(android.R.string.cancel, (d, w) -> cancelled = true)
				.show();

		new Thread(() -> {
			String error = null;
			try {
				fetch(info, target, percent -> activity.runOnUiThread(() -> {
					bar.setIndeterminate(false);
					bar.setProgress(percent);
				}));
			} catch (IOException e) {
				error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
				target.delete();
			}
			final String failure = error;
			activity.runOnUiThread(() -> {
				if (dialog.isShowing()) {
					dialog.dismiss();
				}
				if (activity.isFinishing() || activity.isDestroyed()) {
					return;
				}
				if (failure == null) {
					install(target);
				} else if (!cancelled) {
					Toast.makeText(activity, activity.getString(R.string.update_download_failed, failure),
							Toast.LENGTH_LONG).show();
				}
			});
		}, "app-update").start();
	}

	private interface Progress {
		void onPercent(int percent);
	}

	/** Lädt die APK und vergleicht Größe und SHA-256 mit der Beschreibung aus dem Release. */
	private void fetch(UpdateInfo info, File target, Progress progress) throws IOException {
		MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IOException(e);
		}
		HttpURLConnection connection = (HttpURLConnection) new URL(info.url).openConnection();
		try {
			connection.setConnectTimeout(10_000);
			connection.setReadTimeout(30_000);
			if (connection.getResponseCode() != 200) {
				throw new IOException("HTTP " + connection.getResponseCode());
			}
			long total = connection.getContentLengthLong() > 0 ? connection.getContentLengthLong() : info.size;
			long done = 0;
			int lastPercent = -1;
			try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(target)) {
				byte[] buffer = new byte[64 * 1024];
				while (true) {
					if (cancelled) {
						throw new IOException("abgebrochen");
					}
					int n = in.read(buffer);
					if (n < 0) {
						break;
					}
					out.write(buffer, 0, n);
					digest.update(buffer, 0, n);
					done += n;
					if (total > 0) {
						int percent = (int) (done * 100 / total);
						if (percent != lastPercent) {
							lastPercent = percent;
							progress.onPercent(percent);
						}
					}
				}
			}
			if (info.size > 0 && done != info.size) {
				throw new IOException(activity.getString(R.string.update_size_mismatch));
			}
		} finally {
			connection.disconnect();
		}
		StringBuilder hex = new StringBuilder();
		for (byte b : digest.digest()) {
			hex.append(String.format("%02x", b));
		}
		if (!hex.toString().equals(info.sha256)) {
			throw new IOException(activity.getString(R.string.update_checksum_mismatch));
		}
	}

	private void install(File file) {
		try {
			Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
			activity.startActivity(new Intent(Intent.ACTION_VIEW)
					.setDataAndType(uri, "application/vnd.android.package-archive")
					.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
		} catch (RuntimeException e) {
			Toast.makeText(activity, R.string.update_install_failed, Toast.LENGTH_LONG).show();
		}
	}
}
