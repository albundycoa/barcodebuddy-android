package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Menge wählen und an das Handy schicken: Ein Produkt wird in der Einkaufseinheit dazugezählt, ein freier Eintrag mit seiner
 * Produktgruppe neu angelegt. Das Handy führt die Änderung in Grocy aus und schickt danach die neue Liste.
 */
public class AmountActivity extends Activity {

	static final String EXTRA_NAME    = "name";
	/** Nummer des Produkts; fehlt bei einem freien Eintrag */
	static final String EXTRA_PRODUCT = "product";
	/** Produktgruppe eines freien Eintrags ("" = keine) */
	static final String EXTRA_GROUP   = "group";

	private int amount = 1;
	private TextView value;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_amount);
		final String name = getIntent().getStringExtra(EXTRA_NAME);
		final long product = getIntent().getLongExtra(EXTRA_PRODUCT, -1);
		final String group = getIntent().getStringExtra(EXTRA_GROUP);
		((TextView) findViewById(R.id.amount_name)).setText(name);
		TextView sub = (TextView) findViewById(R.id.amount_sub);
		if (product < 0) {
			sub.setText(group == null || group.isEmpty() ? getString(R.string.free_entry) : getString(R.string.free_entry_in, group));
		} else {
			sub.setText(R.string.add_more);
		}
		value = findViewById(R.id.amount_value);
		render();
		findViewById(R.id.amount_minus).setOnClickListener(v -> {
			if (amount > 1) {
				amount--;
				render();
			}
		});
		findViewById(R.id.amount_plus).setOnClickListener(v -> {
			if (amount < 99) {
				amount++;
				render();
			}
		});
		findViewById(R.id.amount_add).setOnClickListener(v -> {
			if (product >= 0) {
				PhoneLink.addProduct(this, product, amount);
			} else {
				PhoneLink.addNote(this, name, group == null ? "" : group, amount);
			}
			Toast.makeText(this, getString(R.string.add_sent, name), Toast.LENGTH_SHORT).show();
			// Zurück zur Einkaufsliste: Der Stapel darüber (Suche, Gruppe, Menge) wird beendet
			startActivity(new Intent(this, ShoppingActivity.class)
					.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
			finish();
		});
	}

	private void render() {
		value.setText(String.valueOf(amount));
	}
}
