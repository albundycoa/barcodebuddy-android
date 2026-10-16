package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import org.json.JSONException;
import org.json.JSONObject;

import de.bulling.barcodebuddyscanner.Api.BBApi;
import de.bulling.barcodebuddyscanner.Api.BBApiCallback;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Verbindung zu Barcode Buddy: ansehen, prüfen, neu koppeln (QR-Code aus Barcode Buddy, Menü "API")
 * oder abmelden.
 */
public class BbSettingsActivity extends AppCompatActivity {

	private static final int MIN_BBUDDY_VERSION = 1501;

	private TextView     statusView;
	private TextView     detailView;
	private MaterialSwitch sslSwitch;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_bb_settings);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.content), true, false, true, true);

		statusView = findViewById(R.id.bb_status);
		detailView = findViewById(R.id.bb_detail);
		sslSwitch = findViewById(R.id.bb_ssl);

		sslSwitch.setChecked(SharedPrefHelper.isUnsafeSsl(this));
		sslSwitch.setOnCheckedChangeListener((button, checked) -> SharedPrefHelper.setUnsafeSsl(this, checked));

		findViewById(R.id.button_bb_scan).setOnClickListener(v -> {
			IntentIntegrator integrator = new IntentIntegrator(this);
			integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
			integrator.setPrompt(getString(R.string.tut_scan));
			integrator.setOrientationLocked(false);
			integrator.setBeepEnabled(false);
			integrator.initiateScan();
		});
		findViewById(R.id.button_bb_test).setOnClickListener(v -> testConnection());
		findViewById(R.id.button_bb_logout).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.bb_logout_title)
				.setMessage(R.string.bb_logout_message)
				.setPositiveButton(R.string.log_out, (d, w) -> {
					SharedPrefHelper.clearSettings(this);
					setResult(RESULT_OK);
					finish();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show());
		updateStatus();
	}

	private void updateStatus() {
		if (SharedPrefHelper.noApiDetailsSaved(this)) {
			statusView.setText(R.string.bb_not_connected);
			detailView.setText(R.string.tut_text);
		} else {
			statusView.setText(getString(R.string.bb_connected, new SharedPrefHelper(this).getBbWebUrl()));
			detailView.setText(R.string.bb_detail_hint);
		}
	}

	/** Fragt den Server nach seiner Version, das prüft Adresse und Schlüssel zugleich. */
	private void testConnection() {
		if (SharedPrefHelper.noApiDetailsSaved(this)) {
			Toast.makeText(this, R.string.bb_not_connected, Toast.LENGTH_SHORT).show();
			return;
		}
		detailView.setText(R.string.bb_testing);
		new SharedPrefHelper(this).initBBApi().getVersionInfo(new BBApiCallback() {
			@Override
			public void onResult(Object result) {
				detailView.setText(result instanceof Integer
						? getString(R.string.bb_test_ok, (Integer) result)
						: getString(R.string.error_unex));
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				detailView.setText(getString(R.string.bb_test_failed, errorMessage == null ? "" : errorMessage));
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
		String content = result.getContents();
		if (content == null) {
			return;
		}
		if (!content.contains("issetup")) {
			Toast.makeText(this, R.string.e_invalidqr, Toast.LENGTH_LONG).show();
			return;
		}
		try {
			JSONObject setup = new JSONObject(content);
			connect(setup.getString("url"), setup.getString("key"));
		} catch (JSONException e) {
			Toast.makeText(this, R.string.e_invalidqr, Toast.LENGTH_LONG).show();
		}
	}

	private void connect(final String url, final String key) {
		detailView.setText(R.string.bb_testing);
		final boolean unsafe = SharedPrefHelper.isUnsafeSsl(this);
		new BBApi(url, key, unsafe).getVersionInfo(new BBApiCallback() {
			@Override
			public void onResult(Object result) {
				if (result instanceof Integer && (Integer) result >= MIN_BBUDDY_VERSION) {
					SharedPrefHelper.saveApiDetails(BbSettingsActivity.this, url, key, unsafe);
					Toast.makeText(BbSettingsActivity.this, R.string.bb_saved, Toast.LENGTH_LONG).show();
					setResult(RESULT_OK);
					updateStatus();
				} else if (result instanceof Integer) {
					detailView.setText(R.string.error_too_old);
				} else {
					detailView.setText(R.string.error_unex);
				}
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				detailView.setText(getString(R.string.bb_test_failed, errorMessage == null ? "" : errorMessage));
			}
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
