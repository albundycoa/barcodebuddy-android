package de.bulling.barcodebuddyscanner;

import android.os.Bundle;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;

/**
 * Stellt den verbundenen Scanner per App ein. Die Befehle sind der Inhalt der Einstellungs-QR-Codes aus
 * dem Handbuch des Eyoyo EY-015P (Kapitel 14 und 15); die App schickt sie über die Bluetooth-Verbindung,
 * der Scanner verhält sich dann, als hätte er den Code gescannt.
 */
public class ScannerControlActivity extends AppCompatActivity {

	private static final String CMD_BATTERY = "^&037&^";
	private static final String CMD_SLEEP_NOW = "^&041&^";

	/** Je Eintrag: Textressource der Beschriftung und der Befehl */
	private static final Object[][] AUTO_OFF = {
			{R.string.sc_30_seconds, "^&043&^"}, {R.string.sc_2_minutes, "^&045&^"}, {R.string.sc_5_minutes, "^&046&^"},
			{R.string.sc_10_minutes, "^&047&^"}, {R.string.sc_30_minutes, "^&048&^"}, {R.string.sc_never, "^&040&^"}};
	private static final Object[][] VOLUME = {
			{R.string.sc_off, "^&03A&^"}, {R.string.sc_low, "^&03D&^"}, {R.string.sc_medium, "^&03C&^"}, {R.string.sc_high, "^&03B&^"}};
	private static final Object[][] VIBRATION = {
			{R.string.sc_on, "^&039&^"}, {R.string.sc_off, "^&038&^"}};

	private TextView status;

	private final ScannerService.StatusListener statusListener = (text, connected) -> showStatus(text);

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_scanner_control);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.scroll), true, false, true, true);

		status = findViewById(R.id.status);
		LinearLayout container = findViewById(R.id.container);
		addSection(container, R.string.scanner_control_auto_off, AUTO_OFF);
		addSection(container, R.string.scanner_control_volume, VOLUME);
		addSection(container, R.string.scanner_control_vibration, VIBRATION);

		addTitle(container, R.string.scanner_control_actions);
		ChipGroup actions = new ChipGroup(this);
		actions.addView(chip(getString(R.string.scanner_control_battery), CMD_BATTERY));
		Chip sleep = new Chip(this);
		sleep.setText(R.string.scanner_control_sleep_now);
		sleep.setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.scanner_control_sleep_now)
				.setMessage(R.string.scanner_control_sleep_confirm)
				.setPositiveButton(R.string.scanner_control_sleep_yes, (d, w) -> send(getString(R.string.scanner_control_sleep_now), CMD_SLEEP_NOW))
				.setNegativeButton(android.R.string.cancel, null)
				.show());
		actions.addView(sleep);
		container.addView(actions);
	}

	private void addTitle(LinearLayout container, int title) {
		TextView t = new TextView(this);
		t.setText(title);
		t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
		int top = (int) (20 * getResources().getDisplayMetrics().density);
		t.setPadding(0, top, 0, (int) (4 * getResources().getDisplayMetrics().density));
		container.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
	}

	private void addSection(LinearLayout container, int title, Object[][] options) {
		addTitle(container, title);
		ChipGroup group = new ChipGroup(this);
		for (Object[] option : options) {
			group.addView(chip(getString((Integer) option[0]), (String) option[1]));
		}
		container.addView(group);
	}

	private Chip chip(String label, String command) {
		Chip chip = new Chip(this);
		chip.setText(label);
		chip.setOnClickListener(v -> send(label, command));
		return chip;
	}

	private void send(String label, String command) {
		if (ScannerService.sendCommand(command)) {
			Toast.makeText(this, getString(R.string.scanner_control_sent, label), Toast.LENGTH_SHORT).show();
		} else {
			Toast.makeText(this, R.string.scanner_control_not_connected, Toast.LENGTH_LONG).show();
		}
	}

	private void showStatus(String text) {
		status.setText(text.isEmpty() ? getString(R.string.scanner_standby_idle) : text);
	}

	@Override
	protected void onStart() {
		super.onStart();
		ScannerService.addStatusListener(statusListener);
		showStatus(ScannerService.getStatusText());
	}

	@Override
	protected void onStop() {
		ScannerService.removeStatusListener(statusListener);
		super.onStop();
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
