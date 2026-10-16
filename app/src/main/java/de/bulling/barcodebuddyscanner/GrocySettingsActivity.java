package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.text.DateFormat;
import java.util.Date;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Verbindung zu Grocy: Adresse und API-Schlüssel kommen per QR-Code von der Grocy-Seite
 * "API-Schlüssel verwalten" (Inhalt: "Adresse|Schlüssel").
 */
public class GrocySettingsActivity extends AppCompatActivity {

	private TextView       statusView;
	private TextView       detailView;
	private MaterialButton syncButton;
	private MaterialButton disconnectButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_grocy_settings);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.content), true, false, true, true);

		statusView = findViewById(R.id.grocy_status);
		detailView = findViewById(R.id.grocy_detail);
		syncButton = findViewById(R.id.button_sync);
		disconnectButton = findViewById(R.id.button_disconnect);

		findViewById(R.id.button_scan_grocy).setOnClickListener(v -> {
			IntentIntegrator integrator = new IntentIntegrator(this);
			integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
			integrator.setPrompt(getString(R.string.grocy_scan_prompt));
			integrator.setOrientationLocked(false);
			integrator.setBeepEnabled(false);
			integrator.initiateScan();
		});
		syncButton.setOnClickListener(v -> sync());
		disconnectButton.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.grocy_disconnect_title)
				.setMessage(R.string.grocy_disconnect_message)
				.setPositiveButton(R.string.grocy_disconnect, (d, w) -> {
					SharedPrefHelper.clearGrocy(this);
					updateStatus();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
		updateStatus();
	}

	private void updateStatus() {
		boolean connected = SharedPrefHelper.hasGrocy(this);
		syncButton.setEnabled(connected);
		disconnectButton.setEnabled(connected);
		if (!connected) {
			statusView.setText(R.string.grocy_not_connected);
			detailView.setText(R.string.grocy_how_to);
			return;
		}
		statusView.setText(getString(R.string.grocy_connected, SharedPrefHelper.getGrocyUrl(this)));
		long last = SharedPrefHelper.getGrocyLastSync(this);
		if (last == 0) {
			detailView.setText(R.string.grocy_never_synced);
		} else {
			detailView.setText(getString(R.string.grocy_last_sync,
					DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(last)),
					SharedPrefHelper.getGrocySummary(this)));
		}
	}

	private void sync() {
		detailView.setText(R.string.grocy_syncing);
		GrocySync.start(this, (ok, message) -> {
			if (isFinishing() || isDestroyed()) {
				return;
			}
			updateStatus();
			if (!ok) {
				Toast.makeText(this, message, Toast.LENGTH_LONG).show();
			}
		});
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
		if (result == null) {
			super.onActivityResult(requestCode, resultCode, data);
			return;
		}
		if (result.getContents() != null) {
			connect(result.getContents());
		}
	}

	private void connect(String qrContent) {
		int separator = qrContent.lastIndexOf('|');
		if (separator <= 0 || separator == qrContent.length() - 1) {
			Toast.makeText(this, R.string.grocy_invalid_qr, Toast.LENGTH_LONG).show();
			return;
		}
		final String url = qrContent.substring(0, separator).trim();
		final String key = qrContent.substring(separator + 1).trim();
		detailView.setText(R.string.grocy_connecting);
		GrocySync.verify(new GrocyClient(url, key, SharedPrefHelper.isUnsafeSsl(this)), (ok, message) -> {
			if (isFinishing() || isDestroyed()) {
				return;
			}
			if (!ok) {
				updateStatus();
				Toast.makeText(this, message, Toast.LENGTH_LONG).show();
				return;
			}
			String previous = SharedPrefHelper.getGrocyUrl(this);
			if (!previous.isEmpty() && !previous.equals(GrocyClient.normalizeUrl(url))) {
				// Anderer Grocy-Server (z. B. Test -> Live): alte Daten und Abhak-Stände nicht übernehmen
				de.bulling.barcodebuddyscanner.Data.ShoppingRepository.get(this).clearGrocyCache();
			}
			SharedPrefHelper.saveGrocy(this, url, key);
			sync();
		});
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
