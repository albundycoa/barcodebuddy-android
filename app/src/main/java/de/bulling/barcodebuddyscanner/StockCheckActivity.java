package de.bulling.barcodebuddyscanner;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyStock;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.StockInfo;
import de.bulling.barcodebuddyscanner.Helper.DueDates;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.ScanFeedback;
import de.bulling.barcodebuddyscanner.Helper.ScanInput;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Bestand prüfen: Barcode scannen und sofort sehen, wie viel davon zu Hause ist und wann es abläuft. Dazu gleich
 * verbrauchen oder auf die Einkaufsliste setzen. Nichts davon landet in der Liste der Einkaufs-Scans.
 */
public class StockCheckActivity extends AppCompatActivity {

	private static final ExecutorService GROCY_IO = Executors.newSingleThreadExecutor();

	/** Eine Karte pro Produkt, die zuletzt gescannte oben. */
	private static final class Card {
		StockInfo info;
		String    barcode;
		/** Aktueller Bestand aus Grocy, null = noch nicht geladen */
		Double    live;
		String    due;
		boolean   loaded;
		String    message;
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final List<Card> cards = new ArrayList<>();

	private ShoppingRepository repo;
	private ScanFeedback       feedback;
	private ScanInput          scanInput;
	private GrocyClient        grocy;
	private CardAdapter        adapter;
	private TextView           emptyView;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		grocy = new SharedPrefHelper(this).initGrocyClient();
		if (grocy == null) {
			Toast.makeText(this, R.string.inventory_no_grocy, Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		setContentView(R.layout.activity_stock_check);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		repo     = ShoppingRepository.get(this);
		feedback = new ScanFeedback(this);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.content), true, false, true, true);

		scanInput = new ScanInput(this, findViewById(R.id.barcode_scanner), this::handleBarcode);
		emptyView = findViewById(R.id.empty_view);
		RecyclerView list = findViewById(R.id.list);
		adapter = new CardAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		// Die lokale Kopie vom Stand der Dinge: dem Barcode das Produkt zuordnen können
		GrocySync.startIfChanged(this);
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (grocy != null) {
			scanInput.onResume();
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (grocy != null) {
			scanInput.onPause();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		scanInput.onPermissionResult(requestCode, grantResults);
	}

	@Override
	public boolean dispatchKeyEvent(KeyEvent event) {
		if (grocy != null && scanInput.onKey(event)) {
			return true;
		}
		return super.dispatchKeyEvent(event);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// ---------------------------------------------------------------- Scannen

	private void handleBarcode(final String code) {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		feedback.beep();
		repo.findStockInfo(code, info -> {
			Card card = null;
			for (Card c : cards) {
				if (info != null ? c.info != null && c.info.id == info.id : c.info == null && code.equals(c.barcode)) {
					card = c;
					break;
				}
			}
			if (card == null) {
				card = new Card();
			} else {
				cards.remove(card);
			}
			card.info    = info;
			card.barcode = code;
			card.message = null;
			cards.add(0, card);
			render();
			if (info != null) {
				scanInput.setStatusText(info.name);
				loadLive(card);
			}
		});
	}

	/** Holt den aktuellen Bestand und das nächste Haltbarkeitsdatum direkt aus Grocy. */
	private void loadLive(final Card card) {
		final long productId = card.info.id;
		GROCY_IO.execute(() -> {
			GrocyStock.Live live = null;
			try {
				live = GrocyStock.loadProduct(grocy, productId);
			} catch (IOException ignored) {
				// Ohne Verbindung bleibt der Stand aus der lokalen Kopie
			}
			final GrocyStock.Live result = live;
			main.post(() -> {
				if (isFinishing() || isDestroyed()) {
					return;
				}
				card.loaded = true;
				if (result != null) {
					card.live = result.amount;
					card.due  = result.nextDue;
				}
				render();
			});
		});
	}

	private void render() {
		adapter.notifyDataSetChanged();
		emptyView.setVisibility(cards.isEmpty() ? View.VISIBLE : View.GONE);
	}

	// ---------------------------------------------------------------- Aktionen

	private double consumeAmount(StockInfo info) {
		return info.quickConsume != null ? info.quickConsume : 1.0;
	}

	private void confirmConsume(final Card card) {
		final StockInfo info = card.info;
		final double amount = consumeAmount(info);
		new MaterialAlertDialogBuilder(this)
				.setTitle(info.name)
				.setMessage(getString(R.string.stock_consume_confirm, InventorySession.format(amount), unit(info)))
				.setPositiveButton(R.string.stock_consume, (d, w) -> run(card, getString(R.string.stock_consumed,
						InventorySession.format(amount), unit(info)), () -> GrocyStock.consume(grocy, info.id, amount)))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void confirmList(final Card card) {
		final StockInfo info = card.info;
		final double amount = Math.max(1.0, info.stockPerScan);
		new MaterialAlertDialogBuilder(this)
				.setTitle(info.name)
				.setMessage(getString(R.string.stock_list_confirm, InventorySession.format(amount), unit(info)))
				.setPositiveButton(R.string.stock_to_list, (d, w) -> run(card, getString(R.string.stock_listed),
						() -> GrocyStock.addToShoppingList(grocy, info.id, amount)))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private interface Booking {
		void run() throws IOException;
	}

	/** Führt die Buchung aus, meldet das Ergebnis auf der Karte und lädt Bestand und lokale Kopie neu. */
	private void run(final Card card, final String success, final Booking booking) {
		final android.content.Context appContext = getApplicationContext();
		card.message = getString(R.string.stock_working);
		render();
		GROCY_IO.execute(() -> {
			String error = null;
			try {
				booking.run();
			} catch (IOException e) {
				int status = e instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) e).statusCode : 0;
				error = status == 400 ? getString(R.string.stock_err_rejected)
						: status == 0 ? getString(R.string.grocy_err_unreachable)
						: getString(R.string.grocy_err_rejected, status);
			}
			final String failure = error;
			if (failure == null) {
				GrocySync.start(appContext, null);
			}
			main.post(() -> {
				card.message = failure == null ? success : failure;
				if (!isFinishing() && !isDestroyed()) {
					render();
					if (failure == null) {
						loadLive(card);
					}
				}
			});
		});
	}

	private String unit(StockInfo info) {
		return info.stockUnit == null ? "" : info.stockUnit;
	}

	// ---------------------------------------------------------------- Liste

	private class CardAdapter extends RecyclerView.Adapter<CardAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_stock_card, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder h, int position) {
			final Card card = cards.get(position);
			final StockInfo info = card.info;
			if (info == null) {
				h.name.setText(getString(R.string.inventory_unknown_name) + " " + card.barcode);
				h.group.setVisibility(View.GONE);
				h.stock.setText(R.string.stock_unknown);
				h.detail.setVisibility(View.GONE);
				h.actions.setVisibility(View.GONE);
				bindMessage(h, card);
				return;
			}
			h.name.setText(info.name);
			StringBuilder group = new StringBuilder(info.group == null ? "" : info.group);
			if (info.lastPrice != null) {
				if (group.length() > 0) {
					group.append(" · ");
				}
				group.append(getString(R.string.products_detail_price, Money.format(info.lastPrice),
						info.priceUnit == null ? "" : info.priceUnit));
			}
			h.group.setText(group);
			h.group.setVisibility(group.length() > 0 ? View.VISIBLE : View.GONE);

			double amount = card.live != null ? card.live : info.stock;
			h.stock.setText(getString(R.string.stock_amount, InventorySession.format(amount), unit(info) + packs(info, amount)));

			StringBuilder detail = new StringBuilder();
			if (card.due != null) {
				Integer days = DueDates.daysUntil(card.due);
				detail.append(getString(R.string.stock_due, formatDate(card.due), days == null ? "" : daysText(days)));
			} else if (card.loaded && card.live != null && amount > 0) {
				detail.append(getString(R.string.stock_due_none));
			}
			if (info.onShoppingList > 0) {
				if (detail.length() > 0) {
					detail.append('\n');
				}
				detail.append(getString(R.string.stock_on_list, InventorySession.format(info.onShoppingList), unit(info)));
			}
			h.detail.setText(detail);
			h.detail.setVisibility(detail.length() > 0 ? View.VISIBLE : View.GONE);

			h.actions.setVisibility(View.VISIBLE);
			h.consume.setText(getString(R.string.stock_consume_button, InventorySession.format(consumeAmount(info))));
			h.consume.setEnabled(amount > 0);
			h.consume.setOnClickListener(v -> confirmConsume(card));
			h.list.setOnClickListener(v -> confirmList(card));
			bindMessage(h, card);
		}

		private void bindMessage(ViewHolder h, Card card) {
			h.message.setText(card.message);
			h.message.setVisibility(card.message == null ? View.GONE : View.VISIBLE);
		}

		@Override
		public int getItemCount() {
			return cards.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView       name;
			final TextView       group;
			final TextView       stock;
			final TextView       detail;
			final TextView       message;
			final View           actions;
			final MaterialButton consume;
			final MaterialButton list;

			ViewHolder(View view) {
				super(view);
				name    = view.findViewById(R.id.sc_name);
				group   = view.findViewById(R.id.sc_group);
				stock   = view.findViewById(R.id.sc_stock);
				detail  = view.findViewById(R.id.sc_detail);
				message = view.findViewById(R.id.sc_message);
				actions = view.findViewById(R.id.sc_actions);
				consume = view.findViewById(R.id.sc_consume);
				list    = view.findViewById(R.id.sc_list);
			}
		}
	}

	/** " (= 4 Pack)": der Bestand in Packungen, wenn der Barcode mehrere Einheiten zählt. */
	private String packs(StockInfo info, double amount) {
		if (info.stockPerScan <= 1.0001) {
			return "";
		}
		double packs = amount / info.stockPerScan;
		boolean whole = Math.abs(packs - Math.rint(packs)) < 0.0001;
		String name = info.scanUnit == null || info.scanUnit.equals(info.stockUnit)
				? getString(R.string.inventory_pack_default) : info.scanUnit;
		return " (" + (whole ? "= " : "≈ ") + InventorySession.format(Math.round(packs * 10) / 10.0) + " " + name + ")";
	}

	private String formatDate(String iso) {
		try {
			return DateFormat.getDateInstance(DateFormat.MEDIUM).format(
					new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso));
		} catch (java.text.ParseException e) {
			return iso;
		}
	}

	private String daysText(int days) {
		if (days < 0) {
			return getString(R.string.expiry_past, -days);
		}
		return days == 0 ? getString(R.string.expiry_today) : getString(R.string.expiry_future, days);
	}
}
