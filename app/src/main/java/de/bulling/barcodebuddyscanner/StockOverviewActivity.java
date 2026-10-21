package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyStock;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Api.StockOverviewLoader;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.AmountInput;
import de.bulling.barcodebuddyscanner.Helper.DueDates;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;
import de.bulling.barcodebuddyscanner.Helper.StockOverview;
import de.bulling.barcodebuddyscanner.Helper.StockOverview.Entry;

/**
 * Bestandsübersicht wie Grocys "Stock overview": alle Produkte im Bestand mit Menge, Wert und nächstem Ablaufdatum.
 * Je Produkt lässt sich die Schnellverbrauchsmenge verbrauchen, alles verbrauchen und etwas öffnen; jede Buchung kann über
 * die Meldung unten sofort zurückgenommen werden. Die Daten kommen live aus Grocy (Ziehen zum Aktualisieren).
 */
public class StockOverviewActivity extends AppCompatActivity {

	private static final ExecutorService GROCY_IO = Executors.newSingleThreadExecutor();

	/** Eine Buchung in Grocy; liefert die Nummer zum Zurücknehmen (null = nicht möglich). */
	private interface Booking {
		String run(GrocyClient client) throws IOException;
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final List<Entry> all   = new ArrayList<>();
	private final List<Entry> shown = new ArrayList<>();
	/** Einheitennamen aus Grocy (Nummer -> Name), für Maske und Anzeige */
	private final Map<Long, String> unitNames = new HashMap<>();

	private GrocyClient           grocy;
	private ShoppingRepository    repo;
	private RowAdapter            adapter;
	private SwipeRefreshLayout    refresh;
	private BottomNavigationView  nav;
	private TextView              header;
	private TextView              emptyView;
	private StockOverview.Sort    sort  = StockOverview.Sort.NAME;
	private String                query = "";
	private boolean               loading;
	private boolean               busy;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		grocy = new SharedPrefHelper(this).initGrocyClient();
		if (grocy == null) {
			Toast.makeText(this, R.string.inventory_no_grocy, Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		setContentView(R.layout.activity_stock_overview);
		repo = ShoppingRepository.get(this);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);

		header    = findViewById(R.id.header);
		emptyView = findViewById(R.id.empty_view);
		refresh   = findViewById(R.id.refresh);
		RecyclerView list = findViewById(R.id.list);
		adapter = new RowAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		refresh.setOnRefreshListener(this::load);

		nav = findViewById(R.id.bottom_nav);
		EdgeToEdgeHelper.applyInsetsAsPadding(nav, true, false, true, true);
		nav.setSelectedItemId(R.id.nav_stock);
		nav.setOnItemSelectedListener(item -> {
			if (item.getItemId() == R.id.nav_shopping) {
				// Zurück zur Einkaufsseite; sie liegt schon darunter und wird nur nach vorn geholt
				startActivity(new Intent(this, MainActivity.class)
						.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
				overridePendingTransition(0, 0);
				return false;
			}
			return true;
		});

		TextInputEditText search = findViewById(R.id.search);
		search.addTextChangedListener(new SimpleTextWatcher() {
			@Override
			public void onChanged(String text) {
				query = text;
				applyFilter();
			}
		});
		load();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (nav != null) {
			nav.getMenu().findItem(R.id.nav_stock).setChecked(true);
		}
	}

	// ---------------------------------------------------------------- Menü

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.stock_overview, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		menu.findItem(R.id.sort_name).setChecked(sort == StockOverview.Sort.NAME);
		menu.findItem(R.id.sort_value).setChecked(sort == StockOverview.Sort.VALUE);
		menu.findItem(R.id.sort_due).setChecked(sort == StockOverview.Sort.DUE);
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_reload) {
			load();
			return true;
		}
		StockOverview.Sort chosen = id == R.id.sort_name ? StockOverview.Sort.NAME
				: id == R.id.sort_value ? StockOverview.Sort.VALUE
				: id == R.id.sort_due ? StockOverview.Sort.DUE : null;
		if (chosen != null) {
			sort = chosen;
			applyFilter();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// ---------------------------------------------------------------- Laden

	private void load() {
		if (loading) {
			refresh.setRefreshing(false);
			return;
		}
		loading = true;
		refresh.setRefreshing(true);
		if (all.isEmpty()) {
			header.setText(R.string.stock_loading);
		}
		repo.getOptions("units", units -> repo.getProducts("", cached -> {
			final Map<Long, String> names = new HashMap<>();
			for (ShoppingRepository.Option option : units) {
				names.put(option.id, option.name);
			}
			unitNames.clear();
			unitNames.putAll(names);
			GROCY_IO.execute(() -> {
			List<Entry> entries = null;
			try {
				entries = StockOverviewLoader.load(grocy, cached, names);
			} catch (IOException ignored) {
				// unten gemeldet
			}
			final List<Entry> result = entries;
			main.post(() -> {
				loading = false;
				refresh.setRefreshing(false);
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (result == null) {
					header.setText(R.string.grocy_err_unreachable);
					return;
				}
				all.clear();
				all.addAll(result);
				applyFilter();
			});
			});
		}));
	}

	private void applyFilter() {
		shown.clear();
		shown.addAll(StockOverview.filter(all, query));
		StockOverview.sort(shown, sort);
		adapter.notifyDataSetChanged();
		header.setText(getString(R.string.stock_header,
				getResources().getQuantityString(R.plurals.stock_products, shown.size(), shown.size()),
				Money.format(StockOverview.totalValue(shown))));
		emptyView.setText(all.isEmpty() ? R.string.stock_overview_empty : R.string.stock_no_match);
		emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
	}

	// ---------------------------------------------------------------- Buchen

	private void consumeQuick(final Entry entry) {
		final double amount = entry.product.consumeAmount;
		book(entry, client -> GrocyStock.consumeBooking(client, entry.product.id, amount),
				getString(R.string.stock_booked_consume, entry.product.name, amountText(entry, amount)));
	}

	private void confirmConsumeAll(final Entry entry) {
		final double amount = entry.amount;
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.stock_consume_all_title)
				.setMessage(getString(R.string.stock_consume_all_message, entry.product.name, amountText(entry, amount)))
				.setPositiveButton(R.string.stock_consume_all, (d, w) -> book(entry,
						client -> GrocyStock.consumeBooking(client, entry.product.id, amount),
						getString(R.string.stock_booked_consume, entry.product.name, amountText(entry, amount))))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void open(final Entry entry) {
		final double amount = entry.product.openAmount;
		book(entry, client -> GrocyStock.open(client, entry.product.id, amount),
				getString(R.string.stock_booked_open, entry.product.name, amountText(entry, amount)));
	}

	/** Eine Einheit zur Auswahl in der Maske; id 0 = unbekannt (dann gilt Grocys Standard). */
	private static final class UnitChoice {
		final long   id;
		final String name;

		UnitChoice(long id, String name) {
			this.id   = id;
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/**
	 * Maske von unten: Produkt mit Menge und Einheit auf die Einkaufsliste setzen. Steht es schon darauf, zeigt sie die
	 * bisherige Menge zum Ändern (und zum Entfernen); beim Wechsel der Einheit erscheint die Menge dieser Einheit.
	 */
	private void showShoppingSheet(final Entry entry) {
		final List<UnitChoice> choices = new ArrayList<>();
		// Zuerst die Einkaufseinheit (z. B. Pack), danach die Lagereinheit (z. B. Stück), dazu Einheiten vorhandener Einträge
		addChoice(choices, entry.product.quPurchase);
		addChoice(choices, entry.product.quStock);
		for (StockOverview.ListRow row : entry.list) {
			addChoice(choices, row.quId);
		}
		if (choices.isEmpty()) {
			choices.add(new UnitChoice(0, entry.product.unit == null ? "" : entry.product.unit));
		}

		final BottomSheetDialog sheet = new BottomSheetDialog(this);
		View view = getLayoutInflater().inflate(R.layout.sheet_shopping_add, null);
		sheet.setContentView(view);
		sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
		sheet.getBehavior().setSkipCollapsed(true);
		((TextView) view.findViewById(R.id.sheet_product)).setText(entry.product.name);
		final TextInputEditText amountInput = view.findViewById(R.id.sheet_amount);
		final TextInputLayout amountLayout = view.findViewById(R.id.sheet_amount_layout);
		final MaterialButton addButton = view.findViewById(R.id.sheet_add);
		final View removeButton = view.findViewById(R.id.sheet_remove);
		AmountInput.apply(amountInput);
		final MaterialAutoCompleteTextView unitInput = view.findViewById(R.id.sheet_unit);
		unitInput.setAdapter(new android.widget.ArrayAdapter<>(this,
				com.google.android.material.R.layout.m3_auto_complete_simple_item, choices));

		// Vorausgewählt: die Einheit des vorhandenen Eintrags, sonst die Einkaufseinheit
		UnitChoice first = choices.get(0);
		if (entry.onList()) {
			for (UnitChoice choice : choices) {
				if (choice.id == entry.list.get(0).quId) {
					first = choice;
				}
			}
		}
		final UnitChoice[] selected = {first};
		final Runnable fill = () -> {
			StockOverview.ListRow row = entry.listRow(selected[0].id);
			amountInput.setText(row == null ? "1" : Money.formatPlain(row.amount).replaceAll(",?0+$", ""));
			amountInput.selectAll();
			amountLayout.setError(null);
			addButton.setText(row == null ? R.string.stock_list_add : R.string.stock_list_save);
			removeButton.setVisibility(row == null ? View.GONE : View.VISIBLE);
		};
		unitInput.setText(first.name, false);
		fill.run();
		unitInput.setOnItemClickListener((parent, v, position, id) -> {
			selected[0] = choices.get(position);
			fill.run();
		});
		view.findViewById(R.id.sheet_cancel).setOnClickListener(v -> sheet.dismiss());
		removeButton.setOnClickListener(v -> {
			StockOverview.ListRow row = entry.listRow(selected[0].id);
			sheet.dismiss();
			if (row != null) {
				removeFromShoppingList(entry, row);
			}
		});
		addButton.setOnClickListener(v -> {
			Double amount = Money.parse(String.valueOf(amountInput.getText()));
			if (amount == null || amount <= 0) {
				amountLayout.setError(getString(R.string.stock_list_err_amount));
				return;
			}
			sheet.dismiss();
			addToShoppingList(entry, amount, selected[0]);
		});
		sheet.show();
		amountInput.requestFocus();
	}

	private void addChoice(List<UnitChoice> choices, long quId) {
		String name = unitNames.get(quId);
		if (quId <= 0 || name == null) {
			return;
		}
		for (UnitChoice choice : choices) {
			if (choice.id == quId) {
				return;
			}
		}
		choices.add(new UnitChoice(quId, name));
	}

	private void addToShoppingList(Entry entry, final double amount, final UnitChoice unit) {
		final long productId = entry.product.id;
		final String done = getString(R.string.stock_list_done, entry.product.name,
				(InventorySession.format(amount) + " " + unit.name).trim());
		GROCY_IO.execute(() -> {
			try {
				if (unit.id > 0) {
					GrocyStock.addToShoppingList(grocy, productId, amount, unit.id);
				} else {
					GrocyStock.addToShoppingList(grocy, productId, amount);
				}
				main.post(() -> {
					Snackbar.make(findViewById(R.id.list), done, Snackbar.LENGTH_LONG).setAnchorView(nav).show();
					afterListChange();
				});
			} catch (IOException e) {
				main.post(() -> showError(e));
			}
		});
	}

	private void removeFromShoppingList(Entry entry, final StockOverview.ListRow row) {
		final String done = getString(R.string.stock_list_removed, entry.product.name);
		GROCY_IO.execute(() -> {
			try {
				GrocyStock.removeFromShoppingList(grocy, row.id);
				main.post(() -> {
					Snackbar.make(findViewById(R.id.list), done, Snackbar.LENGTH_LONG).setAnchorView(nav).show();
					afterListChange();
				});
			} catch (IOException e) {
				main.post(() -> showError(e));
			}
		});
	}

	/** Markierungen in der Übersicht neu laden und die Einkaufsliste auf dem Handy aktualisieren. */
	private void afterListChange() {
		load();
		GrocySync.start(getApplicationContext(), null);
	}

	private void book(Entry entry, final Booking booking, final String doneText) {
		if (busy) {
			return;
		}
		busy = true;
		adapter.notifyDataSetChanged();
		GROCY_IO.execute(() -> {
			try {
				final String transaction = booking.run(grocy);
				main.post(() -> {
					busy = false;
					showBooked(doneText, transaction);
					afterBooking();
				});
			} catch (IOException e) {
				main.post(() -> {
					busy = false;
					adapter.notifyDataSetChanged();
					showError(e);
				});
			}
		});
	}

	private void showBooked(String text, final String transaction) {
		Snackbar snackbar = Snackbar.make(findViewById(R.id.list), text, Snackbar.LENGTH_LONG).setAnchorView(nav);
		if (transaction != null) {
			snackbar.setAction(R.string.stock_undo, v -> undo(transaction));
		}
		snackbar.show();
	}

	private void undo(final String transaction) {
		if (busy) {
			return;
		}
		busy = true;
		GROCY_IO.execute(() -> {
			try {
				GrocyStock.undo(grocy, transaction);
				main.post(() -> {
					busy = false;
					Toast.makeText(this, R.string.stock_undone, Toast.LENGTH_SHORT).show();
					afterBooking();
				});
			} catch (IOException e) {
				main.post(() -> {
					busy = false;
					adapter.notifyDataSetChanged();
					showError(e);
				});
			}
		});
	}

	/** Liste neu laden und die lokale Kopie der Grocy-Daten (Bestand der Einkaufslisten) auffrischen. */
	private void afterBooking() {
		load();
		GrocySync.start(getApplicationContext(), null);
	}

	private void showError(IOException e) {
		int status = e instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) e).statusCode : 0;
		String message = status == 400 ? getString(R.string.stock_err_rejected)
				: status == 0 ? getString(R.string.grocy_err_unreachable)
				: getString(R.string.grocy_err_rejected, status);
		Snackbar.make(findViewById(R.id.list), getString(R.string.stock_err, message), Snackbar.LENGTH_LONG)
				.setAnchorView(nav).show();
	}

	// ---------------------------------------------------------------- Anzeige

	private static String amountText(Entry entry, double amount) {
		String unit = entry.product.unit == null ? "" : entry.product.unit;
		return (InventorySession.format(amount) + " " + unit).trim();
	}

	/** "2026-10-25" -> "25.10.2026" */
	private static String germanDate(String iso) {
		String[] parts = iso.split("-");
		return parts.length == 3 ? parts[2] + "." + parts[1] + "." + parts[0] : iso;
	}

	private String detailText(Entry entry) {
		StringBuilder text = new StringBuilder();
		if (entry.product.group != null) {
			text.append(entry.product.group);
		}
		if (entry.nextDue != null) {
			Integer days = DueDates.daysUntil(entry.nextDue);
			String relative = days == null ? "" : days < 0 ? getString(R.string.expiry_past, -days)
					: days == 0 ? getString(R.string.expiry_today) : getString(R.string.expiry_future, days);
			if (text.length() > 0) {
				text.append(" · ");
			}
			text.append(getString(R.string.stock_overview_due, germanDate(entry.nextDue), relative));
		}
		return text.toString();
	}

	private class RowAdapter extends RecyclerView.Adapter<RowAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_stock_row, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			final Entry entry = shown.get(position);
			holder.name.setText(entry.product.name);
			holder.value.setText(Money.format(entry.value));
			String amount = amountText(entry, entry.amount);
			if (entry.opened > 0) {
				amount += " · " + getString(R.string.stock_opened, InventorySession.format(entry.opened));
			}
			holder.amount.setText(amount);
			String detail = detailText(entry);
			holder.detail.setText(detail);
			holder.detail.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);

			holder.consume.setText(InventorySession.format(entry.product.consumeAmount));
			holder.open.setText(InventorySession.format(entry.product.openAmount));
			holder.consume.setEnabled(!busy && entry.canConsumeQuick());
			holder.all.setEnabled(!busy && entry.canConsumeAll());
			holder.open.setEnabled(!busy && entry.canOpenNow());
			holder.consume.setOnClickListener(v -> consumeQuick(entry));
			holder.all.setOnClickListener(v -> confirmConsumeAll(entry));
			holder.open.setOnClickListener(v -> open(entry));
			holder.listed.setVisibility(entry.onList() ? View.VISIBLE : View.GONE);
			holder.listed.setText(getString(R.string.stock_overview_on_list, entry.listText()));
			// Steht das Produkt schon auf der Liste, zeigt der Knopf die Menge
			holder.list.setText(entry.onList() ? InventorySession.format(entry.list.get(0).amount) : "");
			holder.list.setEnabled(!busy);
			holder.list.setOnClickListener(v -> showShoppingSheet(entry));
		}

		@Override
		public int getItemCount() {
			return shown.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView       name;
			final TextView       value;
			final TextView       amount;
			final TextView       detail;
			final MaterialButton consume;
			final MaterialButton all;
			final MaterialButton open;
			final MaterialButton list;
			final TextView       listed;

			ViewHolder(View view) {
				super(view);
				name    = view.findViewById(R.id.stock_name);
				value   = view.findViewById(R.id.stock_value);
				amount  = view.findViewById(R.id.stock_amount);
				detail  = view.findViewById(R.id.stock_detail);
				consume = view.findViewById(R.id.btn_consume);
				all     = view.findViewById(R.id.btn_all);
				open    = view.findViewById(R.id.btn_open);
				list    = view.findViewById(R.id.btn_list);
				listed  = view.findViewById(R.id.stock_listed);
			}
		}
	}
}
