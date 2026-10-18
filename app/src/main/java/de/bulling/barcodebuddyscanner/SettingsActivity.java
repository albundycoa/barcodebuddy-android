package de.bulling.barcodebuddyscanner;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

public class SettingsActivity extends AppCompatActivity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.settings_activity);
		setSupportActionBar(findViewById(R.id.toolbar));
		// Die Leiste unter die Statusleiste schieben (Edge-to-Edge ab SDK 35)
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		getSupportFragmentManager()
				.beginTransaction()
				.replace(R.id.settings, new SettingsFragment())
				.commit();
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		// The preference list has no toolbar of its own (it relies on the
		// window's action bar), but its bottom entries would otherwise end
		// up behind the navigation/gesture bar on edge-to-edge enforced
		// devices, making them hard or impossible to tap.
		android.view.View settingsContainer = findViewById(R.id.settings);
		EdgeToEdgeHelper.applyInsetsAsPadding(settingsContainer, true, false, true, true);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			finish();
		}
		return super.onOptionsItemSelected(item);
	}

	public static class SettingsFragment extends PreferenceFragmentCompat {

		private Preference sppDevice;

		private final ActivityResultLauncher<String> bluetoothPermission = registerForActivityResult(
				new ActivityResultContracts.RequestPermission(), granted -> {
					if (granted) {
						showDevicePicker();
					} else {
						Toast.makeText(requireContext(), R.string.spp_permission_needed, Toast.LENGTH_LONG).show();
					}
				});

		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			setPreferencesFromResource(R.xml.root_preferences, rootKey);
			Preference version = findPreference("app_version");
			if (version != null) {
				version.setSummary(BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
			}
			androidx.preference.EditTextPreference battery = findPreference("battery_interval");
			if (battery != null) {
				battery.setOnBindEditTextListener(edit -> {
					edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
					edit.selectAll();
				});
				// Nur ganze Minuten von 0 bis 1440 (ein Tag) zulassen
				battery.setOnPreferenceChangeListener((p, value) -> {
					try {
						long minutes = Long.parseLong(String.valueOf(value).trim());
						return minutes >= 0 && minutes <= 1440;
					} catch (NumberFormatException e) {
						return false;
					}
				});
				battery.setSummaryProvider(p -> {
					long minutes = SharedPrefHelper.getBatteryIntervalMs(requireContext()) / 60_000L;
					return minutes == 0 ? getString(R.string.battery_interval_summary_off)
							: getString(R.string.battery_interval_summary, (int) minutes);
				});
			}
			sppDevice = findPreference("spp_device");
			if (sppDevice != null) {
				sppDevice.setOnPreferenceClickListener(p -> {
					if (PermissionHelper.hasBluetoothConnectPermission(requireContext())) {
						showDevicePicker();
					} else {
						bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
					}
					return true;
				});
				updateSummary();
			}
		}

		private void updateSummary() {
			String name = SharedPrefHelper.getSppName(requireContext());
			sppDevice.setSummary(name.isEmpty() ? getString(R.string.spp_device_none) : name);
		}

		/** Zeigt die in Android gekoppelten Bluetooth-Geräte zur Auswahl. */
		private void showDevicePicker() {
			Context context = requireContext();
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
						updateSummary();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
		}
	}
}
