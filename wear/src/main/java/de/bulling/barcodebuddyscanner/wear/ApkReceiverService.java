package de.bulling.barcodebuddyscanner.wear;

import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * Nimmt eine neue Version der Uhr-App vom Handy entgegen und startet die Installation. Die Datei kommt über einen
 * Kanal der Data Layer, den nur die Handy-App mit gleicher Paketkennung und Signatur öffnen kann.
 */
public class ApkReceiverService extends WearableListenerService {

	private static final String TAG = "ApkReceiver";

	static final String PATH_APK           = "/bbs/apk";
	static final String PATH_VERSION_REQ   = "/bbs/version/request";
	static final String PATH_VERSION_REPLY = "/bbs/version/reply";

	/** Wohin die Datei gespeichert wird, solange sie unterwegs ist. */
	private static File target(android.content.Context context) {
		File dir = new File(context.getCacheDir(), "update");
		dir.mkdirs();
		return new File(dir, "wear.apk");
	}

	/** Das Handy fragt, welche Version die Uhr hat. */
	@Override
	public void onMessageReceived(MessageEvent event) {
		if (!PATH_VERSION_REQ.equals(event.getPath())) {
			return;
		}
		try {
			long code = getPackageManager().getPackageInfo(getPackageName(), 0).getLongVersionCode();
			Wearable.getMessageClient(this).sendMessage(event.getSourceNodeId(), PATH_VERSION_REPLY,
					String.valueOf(code).getBytes(StandardCharsets.UTF_8));
		} catch (PackageManager.NameNotFoundException e) {
			Log.w(TAG, "Eigene Version nicht lesbar", e);
		}
	}

	@Override
	public void onChannelOpened(ChannelClient.Channel channel) {
		if (!PATH_APK.equals(channel.getPath())) {
			return;
		}
		File file = target(this);
		if (file.exists()) {
			file.delete();
		}
		Log.i(TAG, "Update vom Handy wird empfangen");
		Wearable.getChannelClient(this).receiveFile(channel, Uri.fromFile(file), false)
				.addOnFailureListener(e -> Log.w(TAG, "Empfang nicht möglich", e));
	}

	/** Die Datei ist komplett da (oder abgebrochen). */
	@Override
	public void onInputClosed(ChannelClient.Channel channel, int closeReason, int appSpecificErrorCode) {
		if (!PATH_APK.equals(channel.getPath())) {
			return;
		}
		File file = target(this);
		if (closeReason != ChannelClient.ChannelCallback.CLOSE_REASON_NORMAL) {
			Log.w(TAG, "Übertragung abgebrochen, Grund " + closeReason);
			file.delete();
			Toast.makeText(this, R.string.update_failed, Toast.LENGTH_LONG).show();
			return;
		}
		Log.i(TAG, "Update empfangen: " + file.length() + " Byte");
		UpdateInstaller.install(this, file);
	}
}
