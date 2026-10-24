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
	/** Produkte unter Mindestbestand, von denen nichts mehr da ist (nur mit diesem Filter sichtbar) */
	private final List<Entry> missingOnly = new ArrayList<>();
	private StockOverview.State stateFilter;
	private Map<Long, String> barcodes = new HashMap<>();
	private StockOverviewLoader.Result counts;
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
		try {
			sort = StockOverview.Sort.valueOf(SharedPrefHelper.getStockSort(this));
		} catch (IllegalArgumentException e) {
			sort = StockOverview.Sort.NAME;
		}

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
		repo.getProductBarcodes(map -> {
			barcodes = map;
			adapter.notifyDataSetChanged();
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
		// Bei einer Einfachauswahl macht jedes setChecked(), auch mit false, den Eintrag zur Auswahl: nur den gewählten setzen
		menu.findItem(sort == StockOverview.Sort.VALUE ? R.id.sort_value
				: sort == StockOverview.Sort.DUE ? R.id.sort_due : R.id.sort_name).setChecked(true);
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
			SharedPrefHelper.setStockSort(this, chosen.name());
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
			StockOverviewLoader.Result loaded = null;
			try {
				loaded = StockOverviewLoader.loadFull(grocy, cached, names, SharedPrefHelper.getShoppingMainList(this));
			} catch (IOException ignored) {
				// unten gemeldet
			}
			final StockOverviewLoader.Result full = loaded;
			final List<Entry> result = loaded == null ? null : loaded.entries;
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
				missingOnly.clear();
				missingOnly.addAll(full.missingOnly);
				counts = full;
				updatePills();
				applyFilter();
			});
			});
		}));
	}

	/** Pillen mit der Anzahl je Zustand; nur Zustände mit mindestens einem Produkt sind sichtbar. Antippen filtert, nochmal antippen hebt auf. */
	private void updatePills() {
		com.google.android.material.chip.Chip[] chips = {findViewById(R.id.pill_expired), findViewById(R.id.pill_overdue),
				findViewById(R.id.pill_due), findViewById(R.id.pill_min)};
		int[] count = {counts.expired, counts.overdue, counts.dueSoon, counts.belowMin};
		int[] label = {R.string.stock_pill_expired, R.string.stock_pill_overdue, R.string.stock_pill_due, R.string.stock_pill_min};
		int[] bg = {R.color.stock_expired_pill, R.color.stock_overdue_pill, R.color.stock_due_pill, R.color.stock_min_pill};
		int[] on = {R.color.stock_expired_on, R.color.stock_overdue_on, R.color.stock_due_on, R.color.stock_min_on};
		StockOverview.State[] states = StockOverview.State.values();
		boolean any = false;
		for (int i = 0; i < chips.length; i++) {
			final com.google.android.material.chip.Chip chip = chips[i];
			final StockOverview.State state = states[i];
			chip.setText(getString(label[i], count[i]));
			chip.setChipBackgroundColor(android.content.res.ColorStateList.valueOf(getColor(bg[i])));
			chip.setTextColor(getColor(on[i]));
			chip.setCheckedIconTint(android.content.res.ColorStateList.valueOf(getColor(on[i])));
			chip.setChipStrokeColor(android.content.res.ColorStateList.valueOf(getColor(on[i])));
			chip.setChipStrokeWidth(stateFilter == state ? 3f : 0f);
			chip.setChecked(stateFilter == state);
			chip.setVisibility(count[i] > 0 ? View.VISIBLE : View.GONE);
			chip.setOnClickListener(v -> {
				stateFilter = stateFilter == state ? null : state;
				updatePills();
				applyFilter();
			});
			any |= count[i] > 0;
		}
		// Ein Filter, dessen Zustand es nicht mehr gibt, gilt nicht weiter
		if (stateFilter != null && count[stateFilter.ordinal()] == 0) {
			stateFilter = null;
		}
		findViewById(R.id.pills_scroll).setVisibility(any ? View.VISIBLE : View.GONE);
	}

	private void applyFilter() {
		shown.clear();
		List<Entry> base = new ArrayList<>(all);
		if (stateFilter == StockOverview.State.BELOW_MIN) {
			// Auch Produkte, von denen nichts mehr da ist: wie in Grocy
			base.addAll(missingOnly);
		}
		shown.addAll(StockOverview.withState(StockOverview.filter(base, query), stateFilter));
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

	/** Maske von unten: Produkt mit Menge und Einheit auf die Einkaufsliste setzen oder die vorhandene Menge ändern. */
	private void showShoppingSheet(final Entry entry) {
		ShoppingAmountSheet.show(this, entry.product.name, entry.product.quPurchase, entry.product.quStock, entry.product.unit,
				unitNames, entry.list, new ShoppingAmountSheet.Listener() {
					@Override
					public void onSave(double amount, long quId, String unitName) {
						addToShoppingList(entry, amount, quId, unitName);
					}

					@Override
					public void onRemove(StockOverview.ListRow row) {
						removeFromShoppingList(entry, row);
					}
				});
	}

	private void addToShoppingList(Entry entry, final double amount, final long quId, String unitName) {
		final long productId = entry.product.id;
		final String done = getString(R.string.stock_list_done, entry.product.name,
				(InventorySession.format(amount) + " " + unitName).trim());
		GROCY_IO.execute(() -> {
			try {
				long listId = SharedPrefHelper.getShoppingMainList(this);
				if (quId > 0) {
					GrocyStock.addToShoppingList(grocy, productId, amount, quId, listId);
				} else {
					GrocyStock.addProductToList(grocy, productId, amount, listId);
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
			// Dezente Farbe je nach Zustand, wie in Grocy; ohne Zustand die normale Kartenfarbe
			StockOverview.State state = StockOverview.mainState(entry);
			int[] rows = {R.color.stock_expired_row, R.color.stock_overdue_row, R.color.stock_due_row, R.color.stock_min_row};
			int[] edges = {R.color.stock_expired_on, R.color.stock_overdue_on, R.color.stock_due_on, R.color.stock_min_on};
			if (state == null) {
				holder.card.setCardBackgroundColor(holder.cardDefault);
				holder.card.setStrokeWidth(0);
			} else {
				holder.card.setCardBackgroundColor(getColor(rows[state.ordinal()]));
				// Dünner Rand in der Farbe des Zustands, damit auch das Grau von „überfällig“ auffällt
				holder.card.setStrokeColor(android.content.res.ColorStateList.valueOf(
						androidx.core.graphics.ColorUtils.setAlphaComponent(getColor(edges[state.ordinal()]), 110)));
				holder.card.setStrokeWidth((int) (1.5f * getResources().getDisplayMetrics().density));
			}
			if (holder.image != null) {
				de.bulling.barcodebuddyscanner.Helper.ProductImages.bind(holder.image, barcodes.get(entry.product.id));
			}
			holder.name.setText(entry.product.name);
			// Antippen der Karte zeigt die einzelnen Bestandseinträge des Produkts (ändern, entfernen)
			holder.card.setOnClickListener(v -> startActivity(new Intent(StockOverviewActivity.this, StockEntriesActivity.class)
					.putExtra(StockEntriesActivity.EXTRA_PRODUCT_ID, entry.product.id)
					.putExtra(StockEntriesActivity.EXTRA_PRODUCT_NAME, entry.product.name)
					.putExtra(StockEntriesActivity.EXTRA_UNIT, entry.product.unit)));
			holder.menu.setOnClickListener(v -> {
				android.widget.PopupMenu popup = new android.widget.PopupMenu(StockOverviewActivity.this, v);
				popup.getMenu().add(0, 1, 0, R.string.product_menu_stock);
				popup.getMenu().add(0, 2, 1, R.string.product_menu_edit);
				popup.setOnMenuItemClickListener(item -> {
					if (item.getItemId() == 1) {
						holder.card.performClick();
					} else {
						startActivity(new Intent(StockOverviewActivity.this, ProductEditActivity.class)
								.putExtra(ProductEditActivity.EXTRA_PRODUCT_ID, entry.product.id));
					}
					return true;
				});
				popup.show();
			});
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
			final com.google.android.material.card.MaterialCardView card;
			final android.content.res.ColorStateList cardDefault;
			final android.widget.ImageView image;
			final TextView       name;
			final TextView       value;
			final TextView       amount;
			final TextView       detail;
			final MaterialButton consume;
			final MaterialButton all;
			final MaterialButton open;
			final MaterialButton list;
			final TextView       listed;
			final View           menu;

			ViewHolder(View view) {
				super(view);
				card    = (com.google.android.material.card.MaterialCardView) view;
				cardDefault = card.getCardBackgroundColor();
				image   = view.findViewById(R.id.stock_image);
				name    = view.findViewById(R.id.stock_name);
				value   = view.findViewById(R.id.stock_value);
				amount  = view.findViewById(R.id.stock_amount);
				detail  = view.findViewById(R.id.stock_detail);
				consume = view.findViewById(R.id.btn_consume);
				all     = view.findViewById(R.id.btn_all);
				open    = view.findViewById(R.id.btn_open);
				list    = view.findViewById(R.id.btn_list);
				listed  = view.findViewById(R.id.stock_listed);
				menu    = view.findViewById(R.id.stock_menu);
			}
		}
	}
}
