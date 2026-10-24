package de.bulling.barcodebuddyscanner;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyStock;
import de.bulling.barcodebuddyscanner.Api.GrocyStockEntries;
import de.bulling.barcodebuddyscanner.Api.GrocyStockEntries.Entry;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.Option;
import de.bulling.barcodebuddyscanner.Helper.AmountInput;
import de.bulling.barcodebuddyscanner.Helper.DueDates;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Die Bestandseinträge (Chargen) eines Produkts, wie in Grocy: je Eintrag Menge, Ablauf, Kaufdatum, Preis, Lagerort und Geschäft.
 * Einträge lassen sich ändern oder entfernen (das Entfernen bucht in Grocy einen Verbrauch dieser Charge, mit „Rückgängig“).
 */
public class StockEntriesActivity extends AppCompatActivity {

	public static final String EXTRA_PRODUCT_ID   = "product_id";
	public static final String EXTRA_PRODUCT_NAME = "product_name";
	public static final String EXTRA_UNIT         = "unit";

	private static final ExecutorService IO   = Executors.newSingleThreadExecutor();
	private static final Handler         MAIN = new Handler(Looper.getMainLooper());

	private final List<Entry>  entries   = new ArrayList<>();
	private final List<Option> locations = new ArrayList<>();
	private final List<Option> shops     = new ArrayList<>();
	private GrocyClient grocy;
	private ShoppingRepository repo;
	private long productId;
	private String unit = "";
	private TextView header;
	private TextView empty;
	private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;

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
		String unitName = getIntent().getStringExtra(EXTRA_UNIT);
		unit = unitName == null ? "" : unitName;
		repo = ShoppingRepository.get(this);
		setContentView(R.layout.activity_stock_entries);
		Toolbar toolbar = findViewById(R.id.toolbar);
		toolbar.setTitle(getIntent().getStringExtra(EXTRA_PRODUCT_NAME));
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.content), false, false, false, true);
		header = findViewById(R.id.header);
		empty = findViewById(R.id.empty);

		RecyclerView list = findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(this));
		adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@NonNull
			@Override
			public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
						.inflate(R.layout.item_stock_entry, parent, false)) {
				};
			}

			@Override
			public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
				bind(holder.itemView, entries.get(position));
			}

			@Override
			public int getItemCount() {
				return entries.size();
			}
		};
		list.setAdapter(adapter);
		repo.getOptions("locations", l -> {
			locations.clear();
			locations.addAll(l);
			adapter.notifyDataSetChanged();
		});
		repo.getOptions("shops", s -> {
			shops.clear();
			shops.addAll(s);
			adapter.notifyDataSetChanged();
		});
		load();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void load() {
		header.setText(R.string.stock_loading);
		IO.execute(() -> {
			List<Entry> loaded = null;
			try {
				loaded = GrocyStockEntries.load(grocy, productId);
			} catch (IOException ignored) {
				// unten gemeldet
			}
			final List<Entry> result = loaded;
			MAIN.post(() -> {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (result == null) {
					header.setText(R.string.grocy_err_unreachable);
					return;
				}
				entries.clear();
				entries.addAll(result);
				adapter.notifyDataSetChanged();
				double total = 0;
				for (Entry e : result) {
					total += e.amount;
				}
				header.setText(getString(R.string.stock_entries_header, result.size(), (InventorySession.format(total) + " " + unit).trim()));
				empty.setText(R.string.stock_entries_empty);
				empty.setVisibility(result.isEmpty() ? View.VISIBLE : View.GONE);
			});
		});
	}

	// ---------------------------------------------------------------- Anzeige

	private void bind(View view, final Entry entry) {
		((TextView) view.findViewById(R.id.entry_amount)).setText(
				(InventorySession.format(entry.amount) + " " + unit + (entry.open ? " · " + getString(R.string.stock_entry_is_open) : "")).trim());
		String due;
		if (entry.hasNoDueDate()) {
			due = getString(R.string.stock_entry_no_due_text);
		} else {
			Integer days = DueDates.daysUntil(entry.due);
			String relative = days == null ? "" : days < 0 ? getString(R.string.expiry_past, -days)
					: days == 0 ? getString(R.string.expiry_today) : getString(R.string.expiry_future, days);
			due = getString(R.string.stock_overview_due, germanDate(entry.due), relative);
		}
		((TextView) view.findViewById(R.id.entry_due)).setText(due);
		StringBuilder detail = new StringBuilder();
		if (entry.purchased != null) {
			detail.append(getString(R.string.stock_entry_purchased, germanDate(entry.purchased)));
		}
		if (entry.price != null) {
			append(detail, Money.format(entry.price) + " / " + (unit.isEmpty() ? getString(R.string.stock_entry_unit) : unit));
		}
		append(detail, name(locations, entry.locationId));
		append(detail, name(shops, entry.shopId));
		if (entry.note != null && !entry.note.isEmpty()) {
			append(detail, entry.note);
		}
		TextView d = view.findViewById(R.id.entry_detail);
		d.setText(detail);
		d.setVisibility(detail.length() == 0 ? View.GONE : View.VISIBLE);
		view.findViewById(R.id.entry_edit).setOnClickListener(v -> edit(entry));
		view.findViewById(R.id.entry_delete).setOnClickListener(v -> confirmDelete(entry));
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

	private static String name(List<Option> options, long id) {
		for (Option o : options) {
			if (o.id == id) {
				return o.name;
			}
		}
		return null;
	}

	private static String germanDate(String iso) {
		if (iso == null) {
			return "";
		}
		String day = iso.length() >= 10 ? iso.substring(0, 10) : iso;
		String[] parts = day.split("-");
		return parts.length == 3 ? parts[2] + "." + parts[1] + "." + parts[0] : day;
	}

	// ---------------------------------------------------------------- Löschen

	private void confirmDelete(final Entry entry) {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.stock_entry_delete_title)
				.setMessage(getString(R.string.stock_entry_delete_message,
						(InventorySession.format(entry.amount) + " " + unit).trim()))
				.setPositiveButton(R.string.stock_entry_delete, (d, w) -> delete(entry))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void delete(final Entry entry) {
		IO.execute(() -> {
			String transaction = null;
			IOException failure = null;
			try {
				transaction = GrocyStockEntries.remove(grocy, entry);
			} catch (IOException e) {
				failure = e;
			}
			final String tx = transaction;
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
				GrocySync.start(this, null);
				Snackbar snackbar = Snackbar.make(findViewById(R.id.content), R.string.stock_entry_deleted, Snackbar.LENGTH_LONG);
				if (tx != null) {
					snackbar.setAction(R.string.stock_undo_entry, v -> undo(tx));
				}
				snackbar.show();
			});
		});
	}

	private void undo(final String transaction) {
		IO.execute(() -> {
			IOException failure = null;
			try {
				GrocyStock.undo(grocy, transaction);
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
				} else {
					load();
					GrocySync.start(this, null);
				}
			});
		});
	}

	private void showError(IOException e) {
		int status = e instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) e).statusCode : 0;
		String text = status == 0 ? getString(R.string.grocy_err_unreachable) : getString(R.string.grocy_err_rejected, status);
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.product_create_failed_title)
				.setMessage(getString(R.string.product_create_failed, text))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	// ---------------------------------------------------------------- Ändern

	private void edit(final Entry source) {
		final View view = getLayoutInflater().inflate(R.layout.dialog_stock_entry, null);
		final TextInputLayout amountLayout = view.findViewById(R.id.e_amount_layout);
		final TextInputEditText amount = view.findViewById(R.id.e_amount);
		final TextInputEditText due = view.findViewById(R.id.e_due);
		final MaterialCheckBox noDue = view.findViewById(R.id.e_no_due);
		final TextInputEditText purchased = view.findViewById(R.id.e_purchased);
		final TextInputLayout priceLayout = view.findViewById(R.id.e_price_layout);
		final TextInputEditText price = view.findViewById(R.id.e_price);
		final MaterialAutoCompleteTextView location = view.findViewById(R.id.e_location);
		final MaterialAutoCompleteTextView shop = view.findViewById(R.id.e_shop);
		final MaterialCheckBox open = view.findViewById(R.id.e_open);
		AmountInput.apply(amount);
		AmountInput.apply(price);

		amount.setText(InventorySession.format(source.amount));
		final String[] dueIso = {source.hasNoDueDate() ? null : source.due};
		final String[] purchasedIso = {source.purchased};
		noDue.setChecked(source.hasNoDueDate());
		due.setEnabled(!source.hasNoDueDate());
		due.setText(dueIso[0] == null ? "" : germanDate(dueIso[0]));
		purchased.setText(germanDate(source.purchased));
		price.setText(source.price == null ? "" : priceField(source.price));
		open.setChecked(source.open);
		noDue.setOnCheckedChangeListener((b, checked) -> due.setEnabled(!checked));
		due.setOnClickListener(v -> pickDate(dueIso, due));
		purchased.setOnClickListener(v -> pickDate(purchasedIso, purchased));

		final String none = getString(R.string.stock_entry_none);
		fill(location, locations, source.locationId, none);
		fill(shop, shops, source.shopId, none);

		final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.stock_entry_edit)
				.setView(view)
				.setPositiveButton(R.string.stock_entry_save, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			Double newAmount = Money.parse(String.valueOf(amount.getText()));
			if (newAmount == null || newAmount <= 0) {
				amountLayout.setError(getString(R.string.stock_list_err_amount));
				return;
			}
			Double newPrice = null;
			String priceText = String.valueOf(price.getText()).trim();
			if (!priceText.isEmpty()) {
				newPrice = Money.parse(priceText);
				if (newPrice == null || newPrice < 0) {
					priceLayout.setError(getString(R.string.stock_entry_err_price));
					return;
				}
			}
			Entry edited = new Entry();
			edited.id         = source.id;
			edited.stockId    = source.stockId;
			edited.productId  = source.productId;
			edited.amount     = newAmount;
			edited.due        = noDue.isChecked() || dueIso[0] == null ? GrocyStockEntries.NO_DUE_DATE : dueIso[0];
			edited.purchased  = purchasedIso[0];
			edited.price      = newPrice;
			edited.open       = open.isChecked();
			edited.locationId = idOf(locations, String.valueOf(location.getText()), source.locationId);
			edited.shopId     = idOf(shops, String.valueOf(shop.getText()), 0);
			dialog.dismiss();
			save(edited);
		}));
		dialog.show();
	}

	/** Preis mit allen Stellen, die Grocy speichert (bis zu fünf), damit Speichern ihn nicht verändert. */
	private static String priceField(double price) {
		java.text.NumberFormat format = java.text.NumberFormat.getNumberInstance(Locale.GERMANY);
		format.setMinimumFractionDigits(2);
		format.setMaximumFractionDigits(5);
		format.setGroupingUsed(false);
		return format.format(price);
	}

	private void fill(MaterialAutoCompleteTextView field, List<Option> options, long selected, String none) {
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

	private static long idOf(List<Option> options, String name, long fallback) {
		for (Option o : options) {
			if (o.name.equals(name)) {
				return o.id;
			}
		}
		return name.isEmpty() ? fallback : 0;
	}

	private void pickDate(final String[] iso, final TextInputEditText field) {
		Calendar c = Calendar.getInstance();
		if (iso[0] != null && iso[0].length() >= 10) {
			try {
				c.set(Integer.parseInt(iso[0].substring(0, 4)), Integer.parseInt(iso[0].substring(5, 7)) - 1,
						Integer.parseInt(iso[0].substring(8, 10)));
			} catch (NumberFormatException ignored) {
				// heute
			}
		}
		new DatePickerDialog(this, (picker, year, month, day) -> {
			iso[0] = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, day);
			field.setText(germanDate(iso[0]));
		}, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
	}

	private void save(final Entry edited) {
		IO.execute(() -> {
			IOException failure = null;
			try {
				GrocyStockEntries.update(grocy, edited);
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
				GrocySync.start(this, null);
				Snackbar.make(findViewById(R.id.content), R.string.stock_entry_saved, Snackbar.LENGTH_SHORT).show();
			});
		});
	}
}
