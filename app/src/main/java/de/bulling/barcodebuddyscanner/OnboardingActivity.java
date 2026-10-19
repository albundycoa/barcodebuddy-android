package de.bulling.barcodebuddyscanner;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import de.bulling.barcodebuddyscanner.Api.BBApi;
import de.bulling.barcodebuddyscanner.Api.BBApiCallback;
import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.ScannerCodes;
import de.bulling.barcodebuddyscanner.Helper.SetupQr;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;
import de.bulling.barcodebuddyscanner.Helper.SppDevicePicker;

/**
 * Einrichtungsassistent: Barcode Buddy koppeln, Grocy koppeln, Scanner wählen. Jeder Schritt lässt sich überspringen
 * und später nachholen (Einstellungen oder Hinweiszeile im Hauptbildschirm). Nur mit beiden Verbindungen hat die App den
 * vollen Funktionsumfang.
 */
public class OnboardingActivity extends AppCompatActivity {

	/** Schritt, mit dem der Assistent beginnt (Standard: Willkommen) */
	public static final String EXTRA_START_STEP = "start_step";

	private static final int STEP_INTRO   = 0;
	private static final int STEP_BB      = 1;
	private static final int STEP_GROCY   = 2;
	private static final int STEP_SCANNER = 3;
	private static final int STEP_DONE    = 4;

	/** Ab dieser Version von Barcode Buddy gibt es die benötigte API */
	private static final int MIN_BBUDDY_VERSION = 1501;

	private final Handler main = new Handler(Looper.getMainLooper());

	private int startStep;
	private int step;

	private View           stepIntro, stepBb, stepGrocy, stepScanner, stepDone;
	private TextView       stepLabel;
	private LinearProgressIndicator progress;
	private MaterialButton skipButton, nextButton;

	private TextInputEditText bbUrl, bbKey, grocyUrl, grocyKey;
	private CheckBox          bbSsl;
	private MaterialButton    bbConnect, grocyConnect, sppDevice;
	private TextView          bbStatus, grocyStatus, sppStatus;
	private RadioGroup        scanGroup;
	private View              sppBox;

	private final ActivityResultLauncher<String> bluetoothPermission = registerForActivityResult(
			new ActivityResultContracts.RequestPermission(), granted -> {
				if (granted) {
					SppDevicePicker.show(this, this::refreshSpp);
				} else {
					Toast.makeText(this, R.string.spp_permission_needed, Toast.LENGTH_LONG).show();
				}
			});

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_onboarding);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.root), true, true, true, true);

		stepIntro   = findViewById(R.id.step_intro);
		stepBb      = findViewById(R.id.step_bb);
		stepGrocy   = findViewById(R.id.step_grocy);
		stepScanner = findViewById(R.id.step_scanner);
		stepDone    = findViewById(R.id.step_done);
		stepLabel   = findViewById(R.id.step_label);
		progress    = findViewById(R.id.progress);
		skipButton  = findViewById(R.id.button_skip);
		nextButton  = findViewById(R.id.button_next);

		bbUrl        = findViewById(R.id.bb_url);
		bbKey        = findViewById(R.id.bb_key);
		bbSsl        = findViewById(R.id.bb_ssl);
		bbConnect    = findViewById(R.id.bb_connect);
		bbStatus     = findViewById(R.id.bb_status);
		grocyUrl     = findViewById(R.id.grocy_url);
		grocyKey     = findViewById(R.id.grocy_key);
		grocyConnect = findViewById(R.id.grocy_connect);
		grocyStatus  = findViewById(R.id.grocy_status);
		scanGroup    = findViewById(R.id.scan_group);
		sppBox       = findViewById(R.id.spp_box);
		sppDevice    = findViewById(R.id.spp_device);
		sppStatus    = findViewById(R.id.spp_status);

		findViewById(R.id.bb_scan).setOnClickListener(v -> startQrScan(R.string.tut_scan));
		findViewById(R.id.grocy_scan).setOnClickListener(v -> startQrScan(R.string.grocy_scan_prompt));
		bbConnect.setOnClickListener(v -> {
			String url = text(bbUrl);
			String key = text(bbKey);
			if (url.isEmpty() || key.isEmpty()) {
				bbStatus.setText(R.string.onb_fill_both);
				return;
			}
			connectBarcodeBuddy(url, key);
		});
		grocyConnect.setOnClickListener(v -> {
			String url = text(grocyUrl);
			String key = text(grocyKey);
			if (url.isEmpty() || key.isEmpty()) {
				grocyStatus.setText(R.string.onb_fill_both);
				return;
			}
			connectGrocy(url, key);
		});
		findViewById(R.id.spp_code).setOnClickListener(v -> ScannerCodes.showSppPairing(this));
		sppDevice.setOnClickListener(v -> {
			if (PermissionHelper.hasBluetoothConnectPermission(this)) {
				SppDevicePicker.show(this, this::refreshSpp);
			} else {
				bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
			}
		});
		buildScannerChoices();

		skipButton.setOnClickListener(v -> goTo(step + 1));
		nextButton.setOnClickListener(v -> {
			if (step == STEP_DONE) {
				finishWizard();
			} else {
				goTo(step + 1);
			}
		});
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
			@Override
			public void handleOnBackPressed() {
				if (step > startStep) {
					goTo(step - 1);
				} else {
					setResult(RESULT_CANCELED);
					finish();
				}
			}
		});

		startStep = getIntent().getIntExtra(EXTRA_START_STEP, STEP_INTRO);
		goTo(savedInstanceState != null ? savedInstanceState.getInt("step", startStep) : startStep);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt("step", step);
	}

	// ---------------------------------------------------------------- Schritte

	private void goTo(int target) {
		step = Math.max(STEP_INTRO, Math.min(STEP_DONE, target));
		stepIntro.setVisibility(step == STEP_INTRO ? View.VISIBLE : View.GONE);
		stepBb.setVisibility(step == STEP_BB ? View.VISIBLE : View.GONE);
		stepGrocy.setVisibility(step == STEP_GROCY ? View.VISIBLE : View.GONE);
		stepScanner.setVisibility(step == STEP_SCANNER ? View.VISIBLE : View.GONE);
		stepDone.setVisibility(step == STEP_DONE ? View.VISIBLE : View.GONE);
		progress.setProgress(step);
		stepLabel.setText(step >= STEP_BB && step <= STEP_SCANNER ? getString(R.string.onb_step, step, 3) : "");
		updateButtons();
		switch (step) {
			case STEP_BB:
				bbStatus.setText(hasBarcodeBuddy() ? getString(R.string.onb_bb_connected,
						new SharedPrefHelper(this).getBbWebUrl()) : "");
				break;
			case STEP_GROCY:
				grocyStatus.setText(hasGrocy() ? getString(R.string.onb_grocy_connected,
						SharedPrefHelper.getGrocyUrl(this)) : "");
				break;
			case STEP_SCANNER:
				refreshSpp();
				break;
			case STEP_DONE:
				showSummary();
				break;
			default:
				break;
		}
	}

	private void updateButtons() {
		switch (step) {
			case STEP_INTRO:
				skipButton.setVisibility(View.GONE);
				nextButton.setText(R.string.onb_start);
				nextButton.setEnabled(true);
				break;
			case STEP_BB:
				skipButton.setVisibility(View.VISIBLE);
				nextButton.setText(R.string.onb_next);
				nextButton.setEnabled(hasBarcodeBuddy());
				break;
			case STEP_GROCY:
				skipButton.setVisibility(View.VISIBLE);
				nextButton.setText(R.string.onb_next);
				nextButton.setEnabled(hasGrocy());
				break;
			case STEP_SCANNER:
				skipButton.setVisibility(View.GONE);
				nextButton.setText(R.string.onb_next);
				nextButton.setEnabled(true);
				break;
			default:
				skipButton.setVisibility(View.GONE);
				nextButton.setText(R.string.onb_finish);
				nextButton.setEnabled(true);
				break;
		}
	}

	private boolean hasBarcodeBuddy() {
		return !SharedPrefHelper.noApiDetailsSaved(this);
	}

	private boolean hasGrocy() {
		return SharedPrefHelper.hasGrocy(this);
	}

	private static String text(TextInputEditText field) {
		return field.getText() == null ? "" : field.getText().toString().trim();
	}

	// ---------------------------------------------------------------- QR-Codes

	private void startQrScan(int promptRes) {
		IntentIntegrator integrator = new IntentIntegrator(this);
		integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
		integrator.setPrompt(getString(promptRes));
		integrator.setOrientationLocked(false);
		integrator.setBeepEnabled(true);
		integrator.initiateScan();
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
		if (step == STEP_BB) {
			SetupQr.Credentials c = SetupQr.parseBarcodeBuddy(content);
			if (c == null) {
				bbStatus.setText(R.string.e_invalidqr);
				return;
			}
			bbUrl.setText(c.url);
			bbKey.setText(c.key);
			connectBarcodeBuddy(c.url, c.key);
		} else if (step == STEP_GROCY) {
			SetupQr.Credentials c = SetupQr.parseGrocy(content);
			if (c == null) {
				grocyStatus.setText(R.string.grocy_invalid_qr);
				return;
			}
			grocyUrl.setText(c.url);
			grocyKey.setText(c.key);
			connectGrocy(c.url, c.key);
		}
	}

	// ---------------------------------------------------------------- Verbindungen

	private void connectBarcodeBuddy(String rawUrl, final String key) {
		final String url = SetupQr.withTrailingSlash(rawUrl);
		bbConnect.setEnabled(false);
		bbStatus.setText(R.string.grocy_connecting);
		// Ohne die Berechtigung findet die App einen Server im Heimnetz vielleicht nicht; wir versuchen es trotzdem
		PermissionHelper.requestLocalNetworkPermissionIfNeeded(this);
		final BBApi api;
		try {
			api = new BBApi(url, key, bbSsl.isChecked());
		} catch (IllegalArgumentException e) {
			bbFailed(e.getMessage());
			return;
		}
		api.getVersionInfo(new BBApiCallback() {
			@Override
			public void onResult(Object result) {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (result instanceof Integer && (Integer) result >= MIN_BBUDDY_VERSION) {
					SharedPrefHelper.saveApiDetails(OnboardingActivity.this, url, key, bbSsl.isChecked());
					bbConnect.setEnabled(true);
					bbStatus.setText(getString(R.string.onb_bb_connected, new SharedPrefHelper(OnboardingActivity.this).getBbWebUrl()));
					updateButtons();
					// Kurz das Ergebnis zeigen, dann weiter
					main.postDelayed(() -> {
						if (step == STEP_BB && !isFinishing()) {
							goTo(STEP_GROCY);
						}
					}, 900);
				} else if (result instanceof Integer) {
					bbFailed(getString(R.string.error_too_old));
				} else {
					bbFailed(getString(R.string.error_unex));
				}
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				if (!isFinishing() && !isDestroyed()) {
					bbFailed(errorMessage);
				}
			}
		});
	}

	private void bbFailed(String message) {
		bbConnect.setEnabled(true);
		bbSsl.setVisibility(View.VISIBLE);
		bbStatus.setText(getString(R.string.error_cnc) + (message == null ? "" : "\n" + message));
	}

	private void connectGrocy(final String url, final String key) {
		grocyConnect.setEnabled(false);
		grocyStatus.setText(R.string.grocy_connecting);
		GrocySync.verify(new GrocyClient(url, key, SharedPrefHelper.isUnsafeSsl(this)), (ok, message) -> {
			if (isFinishing() || isDestroyed()) {
				return;
			}
			grocyConnect.setEnabled(true);
			if (!ok) {
				grocyStatus.setText(getString(R.string.error_cnc) + "\n" + message);
				return;
			}
			String previous = SharedPrefHelper.getGrocyUrl(this);
			if (!previous.isEmpty() && !previous.equals(GrocyClient.normalizeUrl(url))) {
				// Anderer Grocy-Server: alte Daten und Abhak-Stände nicht übernehmen
				ShoppingRepository.get(this).clearGrocyCache();
			}
			SharedPrefHelper.saveGrocy(this, url, key);
			GrocySync.start(this, null);
			grocyStatus.setText(getString(R.string.onb_grocy_connected, SharedPrefHelper.getGrocyUrl(this)));
			updateButtons();
			main.postDelayed(() -> {
				if (step == STEP_GROCY && !isFinishing()) {
					goTo(STEP_SCANNER);
				}
			}, 900);
		});
	}

	// ---------------------------------------------------------------- Scanner

	private void buildScannerChoices() {
		String[] labels = getResources().getStringArray(R.array.scan_method);
		final String[] values = getResources().getStringArray(R.array.scan_method_val);
		String current = PreferenceManager.getDefaultSharedPreferences(this).getString("scan_method", "scanner");
		for (int i = 0; i < labels.length; i++) {
			RadioButton button = new RadioButton(this);
			button.setId(View.generateViewId());
			button.setText(labels[i]);
			button.setTag(values[i]);
			scanGroup.addView(button);
			if (values[i].equals(current)) {
				button.setChecked(true);
			}
		}
		scanGroup.setOnCheckedChangeListener((group, checkedId) -> {
			RadioButton checked = group.findViewById(checkedId);
			if (checked != null) {
				PreferenceManager.getDefaultSharedPreferences(this).edit()
						.putString("scan_method", String.valueOf(checked.getTag())).apply();
				refreshSpp();
			}
		});
	}

	private void refreshSpp() {
		sppBox.setVisibility(SharedPrefHelper.isSppScannerEnabled(this) ? View.VISIBLE : View.GONE);
		String name = SharedPrefHelper.getSppName(this);
		sppStatus.setText(name.isEmpty() ? getString(R.string.spp_device_none) : getString(R.string.onb_spp_device, name));
	}

	// ---------------------------------------------------------------- Abschluss

	private void showSummary() {
		boolean bb = hasBarcodeBuddy();
		boolean grocy = hasGrocy();
		StringBuilder text = new StringBuilder();
		text.append(bb ? getString(R.string.onb_sum_bb_ok) : getString(R.string.onb_sum_bb_missing)).append('\n');
		text.append(grocy ? getString(R.string.onb_sum_grocy_ok) : getString(R.string.onb_sum_grocy_missing)).append('\n');
		RadioButton checked = findViewById(scanGroup.getCheckedRadioButtonId());
		text.append(getString(R.string.onb_sum_scanner, checked == null ? "-" : checked.getText()));
		TextView title = findViewById(R.id.done_title);
		if (bb && grocy) {
			title.setText(R.string.onb_done_title_ok);
		} else {
			title.setText(R.string.onb_done_title_partial);
			text.append("\n\n").append(getString(R.string.onb_done_later));
		}
		((TextView) findViewById(R.id.done_summary)).setText(text);
	}

	private void finishWizard() {
		SharedPrefHelper.setOnboardingDone(this);
		setResult(RESULT_OK);
		finish();
	}
}
