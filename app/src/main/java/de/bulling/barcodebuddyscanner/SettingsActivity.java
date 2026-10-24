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

		private boolean loadingImages;

		/** Lädt die Bilder aller Produkte, die noch keines haben (einzeln und mit Pause, der Fortschritt steht an der Einstellung). */
		private void loadAllImages(final Preference preference) {
			final Context context = requireContext().getApplicationContext();
			if (!new SharedPrefHelper(context).isOffLookupEnabled()) {
				Toast.makeText(context, R.string.off_images_off, Toast.LENGTH_LONG).show();
				return;
			}
			if (loadingImages) {
				return;
			}
			de.bulling.barcodebuddyscanner.Data.ShoppingRepository.get(context).getProductBarcodes(map -> {
				final List<String> todo = new ArrayList<>();
				for (String barcode : map.values()) {
					if (de.bulling.barcodebuddyscanner.Helper.ProductImages.needsImage(context, barcode)) {
						todo.add(barcode);
					}
				}
				if (todo.isEmpty()) {
					Toast.makeText(context, R.string.off_images_nothing, Toast.LENGTH_LONG).show();
					return;
				}
				loadingImages = true;
				final int total = todo.size();
				final int[] done = {0};
				final int[] loaded = {0};
				for (final String barcode : todo) {
					de.bulling.barcodebuddyscanner.Helper.ProductImages.fetch(context, barcode, () -> {
						done[0]++;
						if (de.bulling.barcodebuddyscanner.Helper.ProductImages.cached(context, barcode) != null) {
							loaded[0]++;
						}
						if (done[0] >= total) {
							loadingImages = false;
							preference.setSummary(getString(R.string.off_images_done, loaded[0], map.size()));
						} else {
							preference.setSummary(getString(R.string.off_images_running, done[0], total));
						}
					});
				}
			});
		}

		@Override
		public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
			setPreferencesFromResource(R.xml.root_preferences, rootKey);
			androidx.preference.EditTextPreference updateUrl = findPreference("update_url");
			if (updateUrl != null) {
				updateUrl.setOnBindEditTextListener(edit -> {
					edit.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
					edit.setHint(BuildConfig.UPDATE_URL);
					edit.selectAll();
				});
				// Leer (zurück zur eingebauten Adresse) oder eine Web-Adresse
				updateUrl.setOnPreferenceChangeListener((p, value) -> {
					String text = String.valueOf(value).trim();
					return text.isEmpty() || de.bulling.barcodebuddyscanner.Update.UpdateChecker.isWebAddress(text);
				});
				updateUrl.setSummaryProvider(p ->
						de.bulling.barcodebuddyscanner.Update.UpdateChecker.updateUrl(requireContext()));
			}
			final androidx.preference.ListPreference theme = findPreference("theme_mode");
			if (theme != null) {
				theme.setOnPreferenceChangeListener((p, value) -> {
					// Speichern passiert danach; das Design sofort umschalten
					requireContext().getSharedPreferences(requireContext().getPackageName() + "_preferences", Context.MODE_PRIVATE)
							.edit().putString("theme_mode", String.valueOf(value)).commit();
					SharedPrefHelper.applyTheme(requireContext());
					return true;
				});
			}
			final Preference images = findPreference("off_images_all");
			if (images != null) {
				images.setOnPreferenceClickListener(p -> {
					loadAllImages(images);
					return true;
				});
			}
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
			Preference pairing = findPreference("spp_pairing_code");
			if (pairing != null) {
				pairing.setOnPreferenceClickListener(p -> {
					de.bulling.barcodebuddyscanner.Helper.ScannerCodes.showSppPairing(requireActivity());
					return true;
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
			de.bulling.barcodebuddyscanner.Helper.SppDevicePicker.show(requireContext(), this::updateSummary);
		}
	}
}
