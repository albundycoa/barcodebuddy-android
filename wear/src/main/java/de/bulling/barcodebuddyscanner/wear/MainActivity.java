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

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);
		textShopping = findViewById(R.id.text_shopping);
		textScans = findViewById(R.id.text_scans);
		textHint = findViewById(R.id.text_hint);
		findViewById(R.id.button_shopping).setOnClickListener(v -> startActivity(new Intent(this, ShoppingActivity.class)));
		findViewById(R.id.button_scans).setOnClickListener(v -> startActivity(new Intent(this, ScansActivity.class)));
	}

	@Override
	protected void onStart() {
		super.onStart();
		State.addListener(this);
		onStateChanged();
		PhoneLink.requestSync(this);
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
		JsonArray scans = State.array(state, "scans");
		String total = state.has("total") ? state.get("total").getAsString() : "";
		textScans.setText(getString(R.string.scan_count, scans.size()) + (scans.size() > 0 ? " · " + total : ""));
	}
}
