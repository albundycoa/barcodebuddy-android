package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** Startseite der Uhr: Einkaufsliste oder gescannte Artikel. */
public class MainActivity extends Activity implements State.Listener {

	private TextView textShopping;
	private TextView textScans;
	private TextView textHint;
	private TextView textBattery;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		textShopping = findViewById(R.id.text_shopping);
		textScans = findViewById(R.id.text_scans);
		textHint = findViewById(R.id.text_hint);
		textBattery = findViewById(R.id.text_battery);
		findViewById(R.id.button_shopping).setOnClickListener(v -> startActivity(new Intent(this, ShoppingActivity.class)));
		findViewById(R.id.button_scans).setOnClickListener(v -> startActivity(new Intent(this, ScansActivity.class)));
	}

	@Override
	protected void onStart() {
		super.onStart();
		State.addListener(this);
		onStateChanged();
		PhoneLink.requestSync(this);
		// Eine noch offene Rückfrage zur Installation eines Updates jetzt anzeigen
		Intent confirm = UpdateInstaller.takePendingConfirmation();
		if (confirm != null) {
			startActivity(confirm);
		}
	}

	@Override
	protected void onStop() {
		State.removeListener(this);
		super.onStop();
	}

	@Override
	public void onStateChanged() {
		JsonObject state = State.load(this);
		if (state == null) {
			textShopping.setText("");
			textScans.setText("");
			textHint.setText(R.string.no_data);
			textBattery.setText("");
			return;
		}
		textHint.setText("");
		int open = 0;
		JsonArray shop = State.array(state, "shop");
		for (int i = 0; i < shop.size(); i++) {
			if (!shop.get(i).getAsJsonObject().get("d").getAsBoolean()) {
				open++;
			}
		}
		textShopping.setText(getString(R.string.open_count, open)
				+ (state.has("stotal") ? " · " + state.get("stotal").getAsString() : ""));
		if (state.has("bat")) {
			int bat = state.get("bat").getAsInt();
			textBattery.setText(getString(bat <= 20 ? R.string.scanner_battery_low : R.string.scanner_battery, bat));
			textBattery.setTextColor(bat <= 20 ? 0xFFFF8A80 : 0xFF80CBC4);
		} else {
			textBattery.setText("");
		}
		JsonArray scans = State.array(state, "scans");
		String total = state.has("total") ? state.get("total").getAsString() : "";
		textScans.setText(getString(R.string.scan_count, scans.size()) + (scans.size() > 0 ? " · " + total : ""));
	}
}
