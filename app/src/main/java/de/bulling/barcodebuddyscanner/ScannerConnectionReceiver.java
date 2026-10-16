package de.bulling.barcodebuddyscanner;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Macht die App "scharf", sobald sich der gewählte Scanner mit dem Handy verbindet, auch wenn
 * die App gar nicht läuft. Verbindet sich der Scanner nicht von selbst, genügt es, die App zu
 * öffnen oder auf die Statuszeile zu tippen.
 */
public class ScannerConnectionReceiver extends BroadcastReceiver {

	private static final String TAG = "ScannerReceiver";

	@Override
	@SuppressWarnings("deprecation")
	public void onReceive(Context context, Intent intent) {
		if (!BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
			return;
		}
		BluetoothDevice device = Build.VERSION.SDK_INT >= 33
				? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
				: (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
		if (device == null || !SharedPrefHelper.isSppScannerEnabled(context)
				|| SharedPrefHelper.isScannerAlwaysOn(context)
				|| !PermissionHelper.hasBluetoothConnectPermission(context)
				|| ScannerService.isRunning()) {
			return;
		}
		String selected = SharedPrefHelper.getSppAddress(context);
		if (selected.isEmpty() || !selected.equalsIgnoreCase(device.getAddress())) {
			return;
		}
		try {
			ScannerService.start(context);
		} catch (RuntimeException e) {
			// Start aus dem Hintergrund nicht erlaubt: per Benachrichtigung zum Öffnen der App auffordern
			Log.w(TAG, "Dienst konnte nicht gestartet werden", e);
			ScannerService.showWakeNotification(context);
		}
	}
}
