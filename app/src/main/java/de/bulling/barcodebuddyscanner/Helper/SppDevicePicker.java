package de.bulling.barcodebuddyscanner.Helper;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.R;

/** Auswahl des gekoppelten Bluetooth-Scanners für den SPP-Modus (Einstellungen und Einrichtungsassistent). */
public final class SppDevicePicker {

	private SppDevicePicker() {
	}

	/** Zeigt die in Android gekoppelten Bluetooth-Geräte zur Auswahl; die Berechtigung muss schon erteilt sein. */
	public static void show(final Context context, final Runnable afterChoice) {
		BluetoothAdapter adapter = ((BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE)).getAdapter();
		final List<BluetoothDevice> devices = new ArrayList<>();
		try {
			if (adapter != null && adapter.isEnabled()) {
				devices.addAll(adapter.getBondedDevices());
			}
		} catch (SecurityException e) {
			Toast.makeText(context, R.string.spp_permission_needed, Toast.LENGTH_LONG).show();
			return;
		}
		if (devices.isEmpty()) {
			Toast.makeText(context, R.string.spp_no_devices, Toast.LENGTH_LONG).show();
			return;
		}
		String[] labels = new String[devices.size()];
		for (int i = 0; i < labels.length; i++) {
			BluetoothDevice d = devices.get(i);
			labels[i] = d.getName() + "\n" + d.getAddress();
		}
		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.spp_picker_title)
				.setItems(labels, (dialog, which) -> {
					BluetoothDevice d = devices.get(which);
					SharedPrefHelper.saveSppDevice(context, d.getAddress(), d.getName());
					if (afterChoice != null) {
						afterChoice.run();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}
}
