package de.bulling.barcodebuddyscanner.wear;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Installiert die vom Handy gesendete Uhr-App über den PackageInstaller. Android prüft dabei selbst, dass die Signatur
 * zur installierten App passt. Beim ersten Mal fragt die Uhr nach; spätere Updates laufen auf Android 12 und neuer
 * ohne Rückfrage, wenn diese App die installierende ist.
 */
final class UpdateInstaller {

	private static final String TAG = "UpdateInstaller";
	static final String ACTION_RESULT = "de.bulling.barcodebuddyscanner.wear.INSTALL_RESULT";

	/** Rückfrage von Android, die der Nutzer noch bestätigen muss (wird beim nächsten Öffnen der App gezeigt). */
	private static volatile Intent pendingConfirmation;

	private UpdateInstaller() {
	}

	static Intent takePendingConfirmation() {
		Intent i = pendingConfirmation;
		pendingConfirmation = null;
		return i;
	}

	static void install(Context context, File apk) {
		PackageManager pm = context.getPackageManager();
		try {
			PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), 0);
			PackageInfo installed = pm.getPackageInfo(context.getPackageName(), 0);
			if (archive == null || !context.getPackageName().equals(archive.packageName)) {
				fail(context, apk, "Datei ist keine Version dieser App");
				return;
			}
			if (archive.getLongVersionCode() <= installed.getLongVersionCode()) {
				fail(context, apk, "Version " + archive.versionName + " ist nicht neuer");
				return;
			}
			if (!pm.canRequestPackageInstalls()) {
				fail(context, apk, "Installieren ist für diese App nicht erlaubt");
				return;
			}
			PackageInstaller installer = pm.getPackageInstaller();
			PackageInstaller.SessionParams params =
					new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
			params.setAppPackageName(context.getPackageName());
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
				params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
			}
			int sessionId = installer.createSession(params);
			try (PackageInstaller.Session session = installer.openSession(sessionId)) {
				try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("update", 0, apk.length())) {
					byte[] buffer = new byte[64 * 1024];
					int n;
					while ((n = in.read(buffer)) > 0) {
						out.write(buffer, 0, n);
					}
					session.fsync(out);
				}
				Intent result = new Intent(context, InstallResultReceiver.class).setAction(ACTION_RESULT);
				PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, result,
						PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
				Toast.makeText(context, context.getString(R.string.update_installing, archive.versionName),
						Toast.LENGTH_LONG).show();
				session.commit(pending.getIntentSender());
			}
		} catch (IOException | RuntimeException | PackageManager.NameNotFoundException e) {
			Log.w(TAG, "Installation nicht möglich", e);
			fail(context, apk, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
		}
	}

	private static void fail(Context context, File apk, String reason) {
		Log.w(TAG, "Update abgelehnt: " + reason);
		apk.delete();
		Toast.makeText(context, context.getString(R.string.update_failed_reason, reason), Toast.LENGTH_LONG).show();
	}

	/** Das Ergebnis der Installation: Rückfrage anzeigen, Erfolg oder Fehler melden. */
	public static final class InstallResultReceiver extends BroadcastReceiver {
		@Override
		public void onReceive(Context context, Intent intent) {
			int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
			String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
			Log.i(TAG, "Installationsergebnis " + status + " " + message);
			if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
				Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
				if (confirm != null) {
					confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					pendingConfirmation = confirm;
					try {
						context.startActivity(confirm);
					} catch (RuntimeException e) {
						// Im Hintergrund nicht erlaubt: die App zeigt die Rückfrage beim nächsten Öffnen
						Toast.makeText(context, R.string.update_open_app, Toast.LENGTH_LONG).show();
					}
				}
			} else if (status != PackageInstaller.STATUS_SUCCESS) {
				Toast.makeText(context, context.getString(R.string.update_failed_reason,
						message == null ? String.valueOf(status) : message), Toast.LENGTH_LONG).show();
			}
		}
	}
}
