package de.bulling.barcodebuddyscanner;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyProductEdit;
import de.bulling.barcodebuddyscanner.Api.GrocyProductEdit.Barcode;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.Option;
import de.bulling.barcodebuddyscanner.Helper.AmountInput;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Ein vorhandenes Grocy-Produkt ändern (Name, Gruppe, Lagerort, Geschäft, Einkaufseinheit, Mindestbestand, Haltbarkeit) und seine Barcodes
 * verwalten, wie in Grocy mit Barcode, Geschäft, Mengeneinheit und Menge.
 */
public class ProductEditActivity extends AppCompatActivity {

	public static final String EXTRA_PRODUCT_ID = "product_id";

	private static final ExecutorService IO   = Executors.newSingleThreadExecutor();
	private static final Handler         MAIN = new Handler(Looper.getMainLooper());

	private final List<Option>  groups    = new ArrayList<>();
	private final List<Option>  locations = new ArrayList<>();
	private final List<Option>  shops     = new ArrayList<>();
	private final List<Option>  units     = new ArrayList<>();
	private final List<Barcode> barcodes  = new ArrayList<>();
	private GrocyClient grocy;
	private ShoppingRepository repo;
	private GrocyProductEdit.Product product;
	private long productId;
	private TextInputLayout nameLayout;
	private TextInputEditText name;
	private TextInputEditText min;
	private TextInputEditText days;
	private MaterialButton save;
	private MaterialAutoCompleteTextView groupField;
	private MaterialAutoCompleteTextView locationField;
	private MaterialAutoCompleteTextView shopField;
	private MaterialAutoCompleteTextView purchaseField;
	private LinearLayout barcodeList;
	private boolean optionsLoaded;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		grocy = new SharedPrefHelper(this).initGrocyClient();
		productId = getIntent().getLongExtra(EXTRA_PRODUCT_ID, 0);
		if (grocy == null || productId <= 0) {
			Toast.makeText(this, R.string.inventory_no_grocy, Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		repo = ShoppingRepository.get(this);
		setContentView(R.layout.activity_product_edit);
		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.scroll), true, false, true, true);

		nameLayout = findViewById(R.id.name_layout);
		name = findViewById(R.id.name_input);
		min = findViewById(R.id.min_input);
		days = findViewById(R.id.days_input);
		AmountInput.apply(min);
		save = findViewById(R.id.save);
		barcodeList = findViewById(R.id.barcodes);
		groupField = dropdown(R.id.f_group, R.string.product_group);
		locationField = dropdown(R.id.f_location, R.string.product_location);
		shopField = dropdown(R.id.f_shop, R.string.product_shop);
		purchaseField = dropdown(R.id.f_purchase, R.string.product_unit_purchase);
		save.setOnClickListener(v -> saveProduct());
		findViewById(R.id.add_barcode).setOnClickListener(v -> editBarcode(null));

		repo.getOptions("groups", g -> {
			groups.clear();
			groups.addAll(g);
			repo.getOptions("locations", l -> {
				locations.clear();
				locations.addAll(l);
				repo.getOptions("shops", s -> {
					shops.clear();
					shops.addAll(s);
					repo.getOptions("units", u -> {
						units.clear();
						units.addAll(u);
						optionsLoaded = true;
						load();
					});
				});
			});
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

	private MaterialAutoCompleteTextView dropdown(int includeId, int hint) {
		TextInputLayout layout = findViewById(includeId);
		layout.setHint(hint);
		return (MaterialAutoCompleteTextView) layout.getEditText();
	}

	// ---------------------------------------------------------------- Laden

	private void load() {
		IO.execute(() -> {
			GrocyProductEdit.Product loaded = null;
			List<Barcode> codes = null;
			IOException failure = null;
			try {
				loaded = GrocyProductEdit.loadProduct(grocy, productId);
				codes = GrocyProductEdit.loadBarcodes(grocy, productId);
			} catch (IOException e) {
				failure = e;
			}
			final GrocyProductEdit.Product p = loaded;
			final List<Barcode> list = codes;
			final IOException error = failure;
			MAIN.post(() -> {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (error != null) {
					showError(error);
					return;
				}
				product = p;
				barcodes.clear();
				barcodes.addAll(list);
				showProduct();
				showBarcodes();
			});
		});
	}

	private void showProduct() {
		name.setText(product.name);
		setOptions(groupField, groups, product.groupId, true);
		setOptions(locationField, locations, product.locationId, false);
		setOptions(shopField, shops, product.shopId, true);
		setOptions(purchaseField, units, product.quPurchase, false);
		min.setText(product.minStock == 0 ? "" : InventorySession.format(product.minStock));
		days.setText(String.valueOf(product.bestBeforeDays));
		String stock = nameOf(units, product.quStock);
		((TextView) findViewById(R.id.stock_unit_info)).setText(stock == null ? "" : getString(R.string.product_edit_stock_unit, stock));
	}

	private void setOptions(MaterialAutoCompleteTextView field, List<Option> options, long selected, boolean allowNone) {
		List<String> names = new ArrayList<>();
		String none = getString(R.string.stock_entry_none);
		if (allowNone) {
			names.add(none);
		}
		String current = allowNone ? none : "";
		for (Option o : options) {
			names.add(o.name);
			if (o.id == selected) {
				current = o.name;
			}
		}
		field.setAdapter(new ArrayAdapter<>(this, com.google.android.material.R.layout.m3_auto_complete_simple_item, names));
		field.setText(current, false);
	}

	private static String nameOf(List<Option> options, long id) {
		for (Option o : options) {
			if (o.id == id) {
				return o.name;
			}
		}
		return null;
	}

	private static long idOf(List<Option> options, String name) {
		for (Option o : options) {
			if (o.name.equals(name)) {
				return o.id;
			}
		}
		return 0;
	}

	// ---------------------------------------------------------------- Speichern

	private void saveProduct() {
		String newName = String.valueOf(name.getText()).trim();
		if (newName.isEmpty()) {
			nameLayout.setError(getString(R.string.product_required));
			return;
		}
		nameLayout.setError(null);
		Double newMin = 0.0;
		String minText = String.valueOf(min.getText()).trim();
		if (!minText.isEmpty()) {
			newMin = Money.parse(minText);
			if (newMin == null || newMin < 0) {
				((TextInputLayout) findViewById(R.id.min_layout)).setError(getString(R.string.stock_list_err_amount));
				return;
			}
		}
		((TextInputLayout) findViewById(R.id.min_layout)).setError(null);
		int newDays;
		try {
			String d = String.valueOf(days.getText()).trim();
			newDays = d.isEmpty() ? 0 : Integer.parseInt(d);
		} catch (NumberFormatException e) {
			((TextInputLayout) findViewById(R.id.days_layout)).setError(getString(R.string.stock_list_err_amount));
			return;
		}
		((TextInputLayout) findViewById(R.id.days_layout)).setError(null);

		final GrocyProductEdit.Product edited = new GrocyProductEdit.Product();
		edited.id             = product.id;
		edited.name           = newName;
		edited.groupId        = idOf(groups, String.valueOf(groupField.getText()));
		edited.locationId     = idOf(locations, String.valueOf(locationField.getText()));
		edited.shopId         = idOf(shops, String.valueOf(shopField.getText()));
		edited.quPurchase     = idOf(units, String.valueOf(purchaseField.getText()));
		edited.minStock       = newMin;
		edited.bestBeforeDays = newDays;
		save.setEnabled(false);
		IO.execute(() -> {
			IOException failure = null;
			try {
				GrocyProductEdit.saveProduct(grocy, edited);
			} catch (IOException e) {
				failure = e;
			}
			final IOException error = failure;
			MAIN.post(() -> {
				save.setEnabled(true);
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (error != null) {
					showError(error);
					return;
				}
				product = edited;
				GrocySync.start(this, null);
				Snackbar.make(findViewById(R.id.scroll), R.string.stock_entry_saved, Snackbar.LENGTH_SHORT).show();
			});
		});
	}

	// ---------------------------------------------------------------- Barcodes

	private void showBarcodes() {
		barcodeList.removeAllViews();
		for (final Barcode b : barcodes) {
			View row = getLayoutInflater().inflate(R.layout.item_product_barcode, barcodeList, false);
			((TextView) row.findViewById(R.id.bc_code)).setText(b.barcode);
			StringBuilder detail = new StringBuilder();
			append(detail, nameOf(shops, b.shopId));
			append(detail, nameOf(units, b.quId));
			append(detail, b.amount == null ? null : InventorySession.format(b.amount));
			TextView d = row.findViewById(R.id.bc_detail);
			d.setText(detail);
			d.setVisibility(detail.length() == 0 ? View.GONE : View.VISIBLE);
			row.findViewById(R.id.bc_edit).setOnClickListener(v -> editBarcode(b));
			row.findViewById(R.id.bc_delete).setOnClickListener(v -> confirmDeleteBarcode(b));
			barcodeList.addView(row);
		}
		findViewById(R.id.barcodes_hint).setVisibility(barcodes.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private static void append(StringBuilder text, String part) {
		if (part == null || part.isEmpty()) {
			return;
		}
		if (text.length() > 0) {
			text.append(" · ");
		}
		text.append(part);
	}

	private void editBarcode(final Barcode source) {
		final View view = getLayoutInflater().inflate(R.layout.dialog_product_barcode, null);
		final TextInputLayout codeLayout = view.findViewById(R.id.b_code_layout);
		final TextInputEditText code = view.findViewById(R.id.b_code);
		final MaterialAutoCompleteTextView shop = view.findViewById(R.id.b_shop);
		final MaterialAutoCompleteTextView unit = view.findViewById(R.id.b_unit);
		final TextInputLayout amountLayout = view.findViewById(R.id.b_amount_layout);
		final TextInputEditText amount = view.findViewById(R.id.b_amount);
		AmountInput.apply(amount);
		if (source != null) {
			code.setText(source.barcode);
			amount.setText(source.amount == null ? "" : InventorySession.format(source.amount));
		}
		String none = getString(R.string.stock_entry_none);
		fillOptional(shop, shops, source == null ? 0 : source.shopId, none);
		fillOptional(unit, units, source == null ? 0 : source.quId, none);

		final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(source == null ? R.string.product_edit_add_barcode : R.string.product_edit_barcode_title)
				.setView(view)
				.setPositiveButton(R.string.stock_entry_save, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String text = String.valueOf(code.getText()).trim();
			if (text.isEmpty()) {
				codeLayout.setError(getString(R.string.product_required));
				return;
			}
			Double amountValue = null;
			String amountText = String.valueOf(amount.getText()).trim();
			if (!amountText.isEmpty()) {
				amountValue = Money.parse(amountText);
				if (amountValue == null || amountValue <= 0) {
					amountLayout.setError(getString(R.string.stock_list_err_amount));
					return;
				}
			}
			Barcode edited = new Barcode();
			edited.id        = source == null ? 0 : source.id;
			edited.productId = productId;
			edited.barcode   = text;
			edited.shopId    = idOf(shops, String.valueOf(shop.getText()));
			edited.quId      = idOf(units, String.valueOf(unit.getText()));
			edited.amount    = amountValue;
			dialog.dismiss();
			runBarcode(() -> GrocyProductEdit.saveBarcode(grocy, edited), R.string.stock_entry_saved);
		}));
		dialog.show();
	}

	private void fillOptional(MaterialAutoCompleteTextView field, List<Option> options, long selected, String none) {
		List<String> names = new ArrayList<>();
		names.add(none);
		String current = none;
		for (Option o : options) {
			names.add(o.name);
			if (o.id == selected) {
				current = o.name;
			}
		}
		field.setAdapter(new ArrayAdapter<>(this, com.google.android.material.R.layout.m3_auto_complete_simple_item, names));
		field.setText(current, false);
	}

	private void confirmDeleteBarcode(final Barcode b) {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.product_edit_barcode_delete_title)
				.setMessage(getString(R.string.product_edit_barcode_delete_message, b.barcode))
				.setPositiveButton(R.string.stock_entry_delete, (d, w) ->
						runBarcode(() -> GrocyProductEdit.deleteBarcode(grocy, b.id), R.string.product_edit_barcode_deleted))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private interface Work {
		void run() throws IOException;
	}

	private void runBarcode(final Work work, final int doneText) {
		IO.execute(() -> {
			IOException failure = null;
			try {
				work.run();
			} catch (IOException e) {
				failure = e;
			}
			final IOException error = failure;
			MAIN.post(() -> {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (error != null) {
					showError(error);
					return;
				}
				load();
				// Neue Barcodes gelten danach auch in der Scan-Liste
				GrocySync.start(this, null);
				Snackbar.make(findViewById(R.id.scroll), doneText, Snackbar.LENGTH_SHORT).show();
			});
		});
	}

	private void showError(IOException e) {
		int status = e instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) e).statusCode : 0;
		String text = status == 0 ? getString(R.string.grocy_err_unreachable)
				: status == 400 ? getString(R.string.product_edit_err_rejected) : getString(R.string.grocy_err_rejected, status);
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.product_create_failed_title)
				.setMessage(getString(R.string.product_create_failed, text))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}
}
