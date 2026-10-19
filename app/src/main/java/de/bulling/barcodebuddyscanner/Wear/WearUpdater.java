package de.bulling.barcodebuddyscanner.Wear;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import de.bulling.barcodebuddyscanner.BuildConfig;
import de.bulling.barcodebuddyscanner.R;

/**
 * Schickt die in der Handy-App mitgelieferte Version der Uhr-App an die Uhr, die sie dann selbst installiert. So
 * lässt sich die Uhr ohne Kabel und ohne adb aktualisieren. Die Datei geht über einen Kanal der Data Layer: den kann nur
 * eine App mit gleicher Paketkennung und gleicher Signatur öffnen.
 */
public final class WearUpdater {

	static final String PATH_APK           = "/bbs/apk";
	static final String PATH_VERSION_REQ   = "/bbs/version/request";
	static final String PATH_VERSION_REPLY = "/bbs/version/reply";

	private static final String ASSET = "wear/wear.apk";
	private static final long   REPLY_TIMEOUT_MS = 6000;

	private static final Handler MAIN = new Handler(Looper.getMainLooper());

	/** Wartet gerade jemand auf die Antwort der Uhr? Dann kommt sie hier an. */
	private static volatile VersionCallback waiting;

	private interface VersionCallback {
		void onVersion(long versionCode);
	}

	private WearUpdater() {
	}

	/** Antwort der Uhr auf die Versionsfrage (vom WearListenerService aufgerufen). */
	static void onVersionReply(byte[] data) {
		VersionCallback callback = waiting;
		waiting = null;
		if (callback == null) {
			return;
		}
		try {
			final long code = Long.parseLong(new String(data, StandardCharsets.UTF_8).trim());
			MAIN.post(() -> callback.onVersion(code));
		} catch (NumberFormatException ignored) {
			// unlesbare Antwort: wie keine Antwort behandeln
		}
	}

	/** Fragt die Uhr nach ihrer Version und bietet an, die mitgelieferte zu senden. */
	public static void start(final Activity activity) {
		Wearable.getNodeClient(activity).getConnectedNodes()
				.addOnSuccessListener(nodes -> {
					Node node = nearby(nodes);
					if (node == null) {
						Toast.makeText(activity, R.string.wear_update_no_watch, Toast.LENGTH_LONG).show();
						return;
					}
					askVersion(activity, node);
				})
				.addOnFailureListener(e -> Toast.makeText(activity, R.string.wear_update_no_watch, Toast.LENGTH_LONG).show());
	}

	private static Node nearby(List<Node> nodes) {
		for (Node n : nodes) {
			if (n.isNearby()) {
				return n;
			}
		}
		return nodes.isEmpty() ? null : nodes.get(0);
	}

	private static void askVersion(final Activity activity, final Node node) {
		final boolean[] answered = {false};
		waiting = code -> {
			answered[0] = true;
			offer(activity, node, code);
		};
		Wearable.getMessageClient(activity).sendMessage(node.getId(), PATH_VERSION_REQ, new byte[0]);
		MAIN.postDelayed(() -> {
			if (!answered[0]) {
				waiting = null;
				if (!activity.isFinishing() && !activity.isDestroyed()) {
					Toast.makeText(activity, R.string.wear_update_no_answer, Toast.LENGTH_LONG).show();
				}
			}
		}, REPLY_TIMEOUT_MS);
	}

	private static void offer(final Activity activity, final Node node, long watchVersionCode) {
		if (activity.isFinishing() || activity.isDestroyed()) {
			return;
		}
		if (watchVersionCode >= BuildConfig.VERSION_CODE) {
			Toast.makeText(activity, activity.getString(R.string.wear_update_current, BuildConfig.VERSION_NAME),
					Toast.LENGTH_LONG).show();
			return;
		}
		new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.wear_update_title)
				.setMessage(activity.getString(R.string.wear_update_text, BuildConfig.VERSION_NAME))
				.setPositiveButton(R.string.wear_update_send, (d, w) -> send(activity, node))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private static void send(final Activity activity, final Node node) {
		final File file;
		try {
			file = copyAsset(activity);
		} catch (IOException e) {
			Toast.makeText(activity, activity.getString(R.string.wear_update_failed, e.getMessage()), Toast.LENGTH_LONG).show();
			return;
		}
		final ProgressBar bar = new ProgressBar(activity);
		int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
		bar.setPadding(pad, pad, pad, pad);
		final AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.wear_update_sending)
				.setMessage(R.string.wear_update_sending_text)
				.setView(bar)
				.setCancelable(false)
				.show();

		final ChannelClient channels = Wearable.getChannelClient(activity);
		channels.openChannel(node.getId(), PATH_APK)
				.addOnSuccessListener(channel -> channels.sendFile(channel, Uri.fromFile(file))
						.addOnSuccessListener(v -> {
							channels.close(channel);
							dialog.dismiss();
							if (!activity.isFinishing() && !activity.isDestroyed()) {
								new MaterialAlertDialogBuilder(activity)
										.setTitle(R.string.wear_update_sent_title)
										.setMessage(R.string.wear_update_sent_text)
										.setPositiveButton(android.R.string.ok, null)
										.show();
							}
						})
						.addOnFailureListener(e -> failed(activity, dialog, e)))
				.addOnFailureListener(e -> failed(activity, dialog, e));
	}

	private static void failed(Activity activity, AlertDialog dialog, Exception e) {
		dialog.dismiss();
		if (!activity.isFinishing() && !activity.isDestroyed()) {
			Toast.makeText(activity, activity.getString(R.string.wear_update_failed, String.valueOf(e.getMessage())),
					Toast.LENGTH_LONG).show();
		}
	}

	/** Legt die mitgelieferte Uhr-APK in den Cache, denn der Kanal sendet eine Datei. */
	private static File copyAsset(Activity activity) throws IOException {
		File dir = new File(activity.getCacheDir(), "wear");
		dir.mkdirs();
		File out = new File(dir, "wear.apk");
		try (InputStream in = activity.getAssets().open(ASSET); OutputStream os = new FileOutputStream(out)) {
			byte[] buffer = new byte[64 * 1024];
			int n;
			while ((n = in.read(buffer)) > 0) {
				os.write(buffer, 0, n);
			}
		}
		return out;
	}
}
