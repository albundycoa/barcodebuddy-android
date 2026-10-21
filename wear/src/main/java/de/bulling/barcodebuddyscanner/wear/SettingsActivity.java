package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Switch;

/** Einstellungen der Uhr: Display bei Scanliste und Einkaufsliste anlassen (gedimmt) oder wie gewohnt ausgehen lassen. */
public class SettingsActivity extends Activity {

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_settings);
		Switch keep = findViewById(R.id.switch_keep_scans);
		keep.setChecked(Settings.keepScansVisible(this));
		keep.setOnCheckedChangeListener((button, checked) -> Settings.setKeepScansVisible(this, checked));
		Switch keepShopping = findViewById(R.id.switch_keep_shopping);
		keepShopping.setChecked(Settings.keepShoppingVisible(this));
		keepShopping.setOnCheckedChangeListener((button, checked) -> Settings.setKeepShoppingVisible(this, checked));
	}
}
