package de.bulling.barcodebuddyscanner;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyProducts;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.Option;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Legt zu einem unbekannten Barcode ein neues Produkt in Grocy an: Name, Ort, Geschäft, Produktgruppe und
 * die vier Mengeneinheiten, optional mit Einkaufspreis und produktspezifischen Umrechnungen. Der gescannte
 * Barcode wird dem Produkt (mit dessen Geschäft) zugeordnet.
 */
public class ProductCreateActivity extends AppCompatActivity {

	public static final String EXTRA_BARCODE = "barcode";
	public static final String EXTRA_ITEM_ID = "item_id";
	public static final String EXTRA_NAME    = "name";

	/** Ein Auswahlfeld mit den Einträgen aus den Grocy-Stammdaten. */
	private static final class Picker {
		final TextInputLayout            layout;
		final MaterialAutoCompleteTextView view;
		final String                     prefKey;
		Option                           selected;

		Picker(TextInputLayout layout, String prefKey) {
			this.layout  = layout;
			this.view    = (MaterialAutoCompleteTextView) layout.getEditText();
			this.prefKey = prefKey;
		}
	}

	private static final class ConversionRow {
		View            root;
		Picker          from;
		Picker          to;
		TextInputLayout factor;
		TextView        error;
	}

	private ShoppingRepository repo;
	private String             barcode;
	private long               itemId;

	private Picker location, shop, group, stock, purchase, consume, priceUnit;
	private TextInputLayout nameLayout, priceLayout;
	private final List<Option> unitOptions = new ArrayList<>();
	private final List<ConversionRow> conversions = new ArrayList<>();
	private android.view.ViewGroup conversionContainer;
	private View saveButton;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_product_create);
		repo = ShoppingRepository.get(this);
		barcode = getIntent().getStringExtra(EXTRA_BARCODE);
		itemId = getIntent().getLongExtra(EXTRA_ITEM_ID, -1);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.scroll), true, false, true, true);

		((TextView) findViewById(R.id.barcode_info)).setText(getString(R.string.product_create_barcode, barcode));
		nameLayout = findViewById(R.id.name_layout);
		priceLayout = findViewById(R.id.price_layout);
		de.bulling.barcodebuddyscanner.Helper.AmountInput.apply(priceLayout.getEditText());
		String suggestion = getIntent().getStringExtra(EXTRA_NAME);
		if (suggestion != null && !suggestion.equals(barcode)) {
			nameLayout.getEditText().setText(suggestion);
		} else if (barcode != null) {
			// Kein Name bekannt: bei OpenFoodFacts nachsehen und vorausfüllen, solange noch nichts eingetippt ist
			repo.suggestionFor(barcode, name -> {
				if (name != null && !isFinishing() && nameLayout.getEditText().getText().length() == 0) {
					nameLayout.getEditText().setText(name);
					nameLayout.setHelperText(getString(R.string.product_create_suggestion_hint));
				}
			});
		}

		location = picker(R.id.f_location, R.string.product_location, "np_location");
		shop = picker(R.id.f_shop, R.string.product_shop, "np_shop");
		group = picker(R.id.f_group, R.string.product_group, "np_group");
		stock = picker(R.id.f_stock, R.string.product_unit_stock, "np_stock");
		purchase = picker(R.id.f_purchase, R.string.product_unit_purchase, "np_purchase");
		consume = picker(R.id.f_consume, R.string.product_unit_consume, "np_consume");
		priceUnit = picker(R.id.f_price_unit, R.string.product_unit_price, "np_price_unit");

		repo.getOptions("locations", options -> fill(location, options));
		repo.getOptions("shops", options -> fill(shop, options));
		repo.getOptions("groups", options -> fill(group, options));
		repo.getOptions("units", options -> {
			unitOptions.clear();
			unitOptions.addAll(options);
			fill(stock, options);
			fill(purchase, options);
			fill(consume, options);
			fill(priceUnit, options);
		});

		conversionContainer = findViewById(R.id.conversions);
		findViewById(R.id.add_conversion).setOnClickListener(v -> addConversionRow());
		saveButton = findViewById(R.id.save);
		saveButton.setOnClickListener(v -> save());
	}

	private Picker picker(int includeId, int hint, String prefKey) {
		TextInputLayout layout = findViewById(includeId);
		layout.setHint(hint);
		return new Picker(layout, prefKey);
	}

	/** Füllt ein Auswahlfeld und wählt den zuletzt benutzten Eintrag vor. */
	private void fill(final Picker picker, final List<Option> options) {
		ArrayAdapter<Option> adapter = new ArrayAdapter<>(this,
				com.google.android.material.R.layout.m3_auto_complete_simple_item, options);
		picker.view.setAdapter(adapter);
		picker.view.setOnItemClickListener((parent, view, position, id) -> {
			picker.selected = (Option) parent.getItemAtPosition(position);
			picker.layout.setError(null);
		});
		if (picker.selected == null && picker.prefKey != null) {
			long saved = prefs().getLong(picker.prefKey, -1);
			for (Option option : options) {
				if (option.id == saved) {
					picker.selected = option;
					picker.view.setText(option.name, false);
				}
			}
		}
	}

	private SharedPreferences prefs() {
		return PreferenceManager.getDefaultSharedPreferences(this);
	}

	// ---------------------------------------------------------------- Umrechnungen

	private void addConversionRow() {
		final ConversionRow row = new ConversionRow();
		row.root = LayoutInflater.from(this).inflate(R.layout.item_conversion, conversionContainer, false);
		row.from = new Picker(row.root.findViewById(R.id.conv_from), null);
		row.to = new Picker(row.root.findViewById(R.id.conv_to), null);
		row.from.layout.setHint(R.string.conversion_from);
		row.to.layout.setHint(R.string.conversion_to);
		row.factor = row.root.findViewById(R.id.conv_factor);
		de.bulling.barcodebuddyscanner.Helper.AmountInput.apply(row.factor.getEditText());
		row.error = row.root.findViewById(R.id.conv_error);
		fill(row.from, unitOptions);
		fill(row.to, unitOptions);
		row.root.findViewById(R.id.conv_remove).setOnClickListener(v -> {
			conversionContainer.removeView(row.root);
			conversions.remove(row);
		});
		conversions.add(row);
		conversionContainer.addView(row.root);
	}

	// ---------------------------------------------------------------- Speichern

	private static Double parse(String text) {
		return de.bulling.barcodebuddyscanner.Helper.Money.parse(text);
	}

	private boolean require(Picker picker) {
		if (picker.selected == null) {
			picker.layout.setError(getString(R.string.product_required));
			return false;
		}
		return true;
	}

	private void save() {
		String name = nameLayout.getEditText().getText().toString().trim();
		boolean ok = true;
		if (name.isEmpty()) {
			nameLayout.setError(getString(R.string.product_required));
			ok = false;
		} else {
			nameLayout.setError(null);
		}
		for (Picker p : new Picker[]{location, shop, group, stock, purchase, consume, priceUnit}) {
			ok &= require(p);
		}

		String priceText = priceLayout.getEditText().getText().toString().trim();
		Double price = null;
		if (!priceText.isEmpty()) {
			price = parse(priceText);
			if (price == null || price < 0) {
				priceLayout.setError(getString(R.string.product_price_invalid));
				ok = false;
			} else {
				priceLayout.setError(null);
			}
		}

		final GrocyProducts.Spec spec = new GrocyProducts.Spec();
		for (ConversionRow row : conversions) {
			String factorText = row.factor.getEditText().getText().toString().trim();
			if (row.from.selected == null && row.to.selected == null && factorText.isEmpty()) {
				continue; // leere Zeile ignorieren
			}
			Double factor = parse(factorText);
			if (row.from.selected == null || row.to.selected == null || factor == null || factor <= 0
					|| row.from.selected.id == row.to.selected.id) {
				row.error.setVisibility(View.VISIBLE);
				ok = false;
			} else {
				row.error.setVisibility(View.GONE);
				spec.conversions.add(new GrocyProducts.Conversion(row.from.selected.id, row.to.selected.id, factor));
			}
		}
		if (!ok) {
			return;
		}

		GrocyClient client = new SharedPrefHelper(this).initGrocyClient();
		if (client == null) {
			Toast.makeText(this, R.string.product_no_grocy, Toast.LENGTH_LONG).show();
			return;
		}
		spec.name = name;
		spec.locationId = location.selected.id;
		spec.shopId = shop.selected.id;
		spec.groupId = group.selected.id;
		spec.quStock = stock.selected.id;
		spec.quPurchase = purchase.selected.id;
		spec.quConsume = consume.selected.id;
		spec.quPrice = priceUnit.selected.id;
		spec.barcode = barcode;

		saveButton.setEnabled(false);
		final Double finalPrice = price;
		new Thread(() -> {
			try {
				final GrocyProducts.Result created = GrocyProducts.createProduct(client, spec);
				runOnUiThread(() -> created(finalPrice, created.warning));
			} catch (java.io.IOException e) {
				runOnUiThread(() -> {
					saveButton.setEnabled(true);
					new MaterialAlertDialogBuilder(this)
							.setTitle(R.string.product_create_failed_title)
							.setMessage(getString(R.string.product_create_failed, String.valueOf(e.getMessage())))
							.setPositiveButton(android.R.string.ok, null)
							.show();
				});
			}
		}, "product-create").start();
	}

	private void created(Double price, String warning) {
		SharedPreferences.Editor edit = prefs().edit();
		for (Picker p : new Picker[]{location, shop, group, stock, purchase, consume, priceUnit}) {
			edit.putLong(p.prefKey, p.selected.id);
		}
		edit.apply();
		if (price != null && itemId >= 0) {
			// Preis je Preiseinheit, wie er auch in der Liste der Scans bearbeitet wird
			repo.setPrice(itemId, price);
		}
		// Neue Stammdaten holen: danach ist der Barcode in der Scan-Liste bekannt
		GrocySync.start(getApplicationContext(), null);
		Toast.makeText(this, warning != null ? warning : getString(R.string.product_created), Toast.LENGTH_LONG).show();
		setResult(RESULT_OK);
		finish();
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
