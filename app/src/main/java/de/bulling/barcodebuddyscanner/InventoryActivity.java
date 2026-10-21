package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyInventory;
import de.bulling.barcodebuddyscanner.Api.GrocyProducts;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.InventoryPlanner;
import de.bulling.barcodebuddyscanner.Helper.InventoryPlanner.Change;
import de.bulling.barcodebuddyscanner.Helper.InventoryPlanner.Plan;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.InventorySession.Entry;
import de.bulling.barcodebuddyscanner.Helper.InventorySession.Status;
import de.bulling.barcodebuddyscanner.Helper.ScanFeedback;
import de.bulling.barcodebuddyscanner.Helper.ScanInput;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Inventur: Man scannt einfach alles durch, die App zählt mit. Am Ende vergleicht sie die Zählung mit dem Bestand in
 * Grocy und überträgt nur, was abweicht. Stimmt ein Produkt, passiert nichts.
 */
public class InventoryActivity extends AppCompatActivity {

	private static final ExecutorService GROCY_IO = Executors.newSingleThreadExecutor();


	private final Handler main = new Handler(Looper.getMainLooper());

	private ShoppingRepository repo;
	private InventorySession   session;
	private ScanFeedback       feedback;
	private GrocyClient        grocy;

	private MaterialCardView     lastCard;
	private TextView             lastName;
	private TextView             lastStatus;
	private LinearProgressIndicator progress;
	private TextView             summary;
	private TextView             emptyView;
	private InventoryAdapter     adapter;

	private final List<Entry> shown = new ArrayList<>();

	private ScanInput scanInput;
	private int     stockedProducts;
	private AlertDialog busy;
	/** Der unbekannte Barcode, der gerade in Grocy angelegt oder zugeordnet wird */
	private String  assignBarcode;
	private Snackbar packHint;
	/** Der zuletzt gescannte Eintrag; die Statuskarte ändert beim Antippen seine Menge */
	private Entry lastEntry;

	// ---------------------------------------------------------------- Lebenszyklus

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		grocy = new SharedPrefHelper(this).initGrocyClient();
		if (grocy == null) {
			Toast.makeText(this, R.string.inventory_no_grocy, Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		setContentView(R.layout.activity_inventory);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		repo     = ShoppingRepository.get(this);
		session  = InventorySession.get(this);
		feedback = new ScanFeedback(this);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.content), true, false, true, true);

		scanInput = new ScanInput(this, findViewById(R.id.barcode_scanner), this::handleBarcode);
		lastCard   = findViewById(R.id.last_card);
		lastCard.setOnClickListener(v -> {
			if (lastEntry != null && lastEntry.known) {
				showEdit(lastEntry);
			}
		});
		lastName   = findViewById(R.id.last_name);
		lastStatus = findViewById(R.id.last_status);
		progress   = findViewById(R.id.progress);
		summary    = findViewById(R.id.summary);
		emptyView  = findViewById(R.id.empty_view);

		RecyclerView list = findViewById(R.id.list);
		adapter = new InventoryAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		findViewById(R.id.button_finish).setOnClickListener(v -> startReview());

		lastName.setText(R.string.inventory_title);
		lastStatus.setText(R.string.inventory_ready);
		if (session.isEmpty()) {
			// Neue Inventur: Barcodes und Bestand frisch aus Grocy holen
			reloadCache(false);
		} else {
			refreshStockedCount();
		}
		render();
	}

	@Override
	protected void onResume() {
		super.onResume();
		if (grocy == null) {
			return;
		}
		scanInput.onResume();
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (grocy == null) {
			return;
		}
		scanInput.onPause();
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		dismissBusy();
	}

	// ---------------------------------------------------------------- Scanner

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

	// ---------------------------------------------------------------- Zählen

	private void handleBarcode(final String code) {
		if (isFinishing() || isDestroyed()) {
			return;
		}
		feedback.beep();
		repo.findInventoryProduct(code, product -> {
			Entry entry = product == null ? session.recordUnknown(code) : session.recordProduct(product);
			showLast(entry);
			render();
			if (product == null && !suggestions.containsKey(code)) {
				repo.suggestionFor(code, name -> {
					if (name != null && !isFinishing() && !isDestroyed()) {
						suggestions.put(code, name);
						if (lastEntry == entry) {
							showLast(entry);
						}
						render();
					}
				});
			}
			if (packHint != null) {
				packHint.dismiss();
				packHint = null;
			}
			// Eine Packung mit mehreren Einheiten ist vielleicht angebrochen: Gelegenheit, die Menge anzupassen
			if (product != null && product.stockPerScan > 1.0001) {
				showPackHint(entry);
			}
		});
	}

	private void showLast(Entry e) {
		lastEntry = e;
		int[] colors = colorsFor(InventorySession.status(e));
		lastCard.setCardBackgroundColor(colors[0]);
		lastName.setTextColor(colors[1]);
		lastStatus.setTextColor(colors[1]);
		lastName.setText(e.known ? e.name : unknownLabel(e, "\n"));
		lastStatus.setText(e.known ? statusText(e) + "\n" + getString(R.string.inventory_tap_to_change) : statusText(e));
	}

	/** Barcode von OpenFoodFacts erkannter Barcodes (nur für die Anzeige) */
	private final java.util.Map<String, String> suggestions = new java.util.HashMap<>();

	private String unknownLabel(Entry e, String separator) {
		String suggestion = suggestions.get(e.barcode);
		if (suggestion != null) {
			return getString(R.string.inventory_unknown_suggestion, suggestion) + separator + e.barcode;
		}
		return getString(R.string.inventory_unknown_name) + separator + e.barcode;
	}

	private String statusText(Entry e) {
		String unit = unitOf(e) + stockPacks(e);
		switch (InventorySession.status(e)) {
			case MATCH:
				return getString(R.string.inventory_status_match, InventorySession.format(e.counted),
						InventorySession.format(e.stock), unit).trim();
			case MORE:
				return getString(R.string.inventory_status_more, InventorySession.format(e.counted),
						InventorySession.format(e.stock), unit).trim();
			case LESS:
				return getString(R.string.inventory_status_less, InventorySession.format(e.counted),
						InventorySession.format(e.stock), unit).trim();
			default:
				return getString(R.string.inventory_status_unknown);
		}
	}

	/** @return {Hintergrund, Text} */
	private int[] colorsFor(Status status) {
		switch (status) {
			case MATCH:
				return new int[]{getColor(R.color.scanner_ok_bg), getColor(R.color.scanner_ok_text)};
			case MORE:
				return new int[]{getColor(R.color.inventory_more_bg), getColor(R.color.inventory_more_text)};
			case LESS:
				return new int[]{getColor(R.color.md_tertiary_container), getColor(R.color.md_on_tertiary_container)};
			case UNKNOWN:
				return new int[]{getColor(R.color.scanner_off_bg), getColor(R.color.scanner_off_text)};
			default:
				return new int[]{getColor(R.color.md_surface_variant), getColor(R.color.md_on_surface_variant)};
		}
	}

	private void render() {
		shown.clear();
		shown.addAll(session.snapshot());
		// Unbekannte Barcodes brauchen Aufmerksamkeit und stehen deshalb ganz oben
		java.util.Collections.sort(shown, (a, b) -> Boolean.compare(a.known, b.known));
		adapter.notifyDataSetChanged();
		emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);

		int counted = 0;
		int matched = 0;
		int differing = 0;
		int unknown = 0;
		for (Entry e : shown) {
			Status status = InventorySession.status(e);
			if (status == Status.UNKNOWN) {
				unknown++;
				continue;
			}
			if (e.stock > 0) {
				counted++;
			}
			if (status == Status.MATCH) {
				matched++;
			} else {
				differing++;
			}
		}
		int total = Math.max(stockedProducts, counted);
		progress.setMax(Math.max(total, 1));
		progress.setProgressCompat(counted, true);
		StringBuilder text = new StringBuilder(getString(R.string.inventory_summary, counted, total, matched, differing));
		if (unknown > 0) {
			text.append(getString(R.string.inventory_summary_unknown, unknown));
		}
		summary.setText(text);
	}

	private void refreshStockedCount() {
		repo.getStockedProductCount(count -> {
			stockedProducts = count;
			render();
		});
	}

	/** Lädt Produkte, Barcodes und Bestand aus Grocy in die lokale Kopie. */
	private void reloadCache(final boolean toastResult) {
		lastStatus.setText(R.string.inventory_loading_stock);
		GrocySync.start(this, (ok, message) -> {
			if (isFinishing() || isDestroyed()) {
				return;
			}
			lastStatus.setText(ok ? R.string.inventory_ready : R.string.inventory_stock_failed);
			if (toastResult && !ok) {
				Toast.makeText(this, message, Toast.LENGTH_LONG).show();
			}
			refreshStockedCount();
		});
	}

	/** Bestand mitten in der Inventur neu holen: Zählung und aktuelle Bestände der gezählten Produkte bleiben stimmig. */
	private void reloadStock() {
		showBusy(R.string.inventory_loading_stock);
		GROCY_IO.execute(() -> {
			Map<Long, Double> live = null;
			try {
				live = GrocyInventory.loadStock(grocy);
			} catch (IOException ignored) {
				// unten gemeldet
			}
			final Map<Long, Double> result = live;
			main.post(() -> {
				dismissBusy();
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (result == null) {
					Toast.makeText(this, R.string.inventory_stock_failed, Toast.LENGTH_LONG).show();
					return;
				}
				for (Entry e : session.snapshot()) {
					if (e.known) {
						Double amount = result.get(e.productId);
						session.updateStock(e.key, amount == null ? 0 : amount);
					}
				}
				reloadCache(false);
				render();
			});
		});
	}

	// ---------------------------------------------------------------- Abschluss

	private static final class Outcome {
		final List<Change> done   = new ArrayList<>();
		final List<Change> failed = new ArrayList<>();
		String error;
		int    increased;
		int    decreased;
	}

	/** Holt den aktuellen Bestand aus Grocy und legt fest, was zu ändern ist. */
	private void startReview() {
		if (session.isEmpty()) {
			Toast.makeText(this, R.string.inventory_empty_finish, Toast.LENGTH_SHORT).show();
			return;
		}
		int unknown = 0;
		for (Entry e : session.snapshot()) {
			if (!e.known) {
				unknown++;
			}
		}
		if (unknown == 0) {
			loadAndShowPlan();
			return;
		}
		// Vielleicht ist es ein Produkt, das in Grocy noch fehlt: Gelegenheit, es jetzt anzulegen
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.inventory_unknown_title)
				.setMessage(getString(R.string.inventory_unknown_text, unknown))
				.setPositiveButton(R.string.inventory_unknown_assign, null)
				.setNegativeButton(R.string.inventory_unknown_ignore, (d, w) -> loadAndShowPlan())
				.show();
	}

	private void loadAndShowPlan() {
		showBusy(R.string.inventory_checking);
		GROCY_IO.execute(() -> {
			Map<Long, Double> live = null;
			try {
				live = GrocyInventory.loadStock(grocy);
			} catch (IOException ignored) {
				// unten gemeldet
			}
			final Map<Long, Double> result = live;
			main.post(() -> {
				dismissBusy();
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (result == null) {
					Toast.makeText(this, R.string.inventory_check_failed, Toast.LENGTH_LONG).show();
					return;
				}
				showPlan(buildPlan(result));
			});
		});
	}

	private Plan buildPlan(Map<Long, Double> live) {
		List<Entry> entries = session.snapshot();
		// Der Bestand aus Grocy ersetzt den zuletzt bekannten Stand in der Zählung
		for (Entry e : entries) {
			if (e.known) {
				Double amount = live.get(e.productId);
				session.updateStock(e.key, amount == null ? 0 : amount);
			}
		}
		return InventoryPlanner.plan(entries, live);
	}

	private void showPlan(final Plan plan) {
		render();
		if (plan.changes.isEmpty()) {
			// Nichts zu tun: alles gezählt, was gezählt werden sollte, passt
			new MaterialAlertDialogBuilder(this)
					.setTitle(R.string.inventory_perfect_title)
					.setMessage(getString(R.string.inventory_perfect_text, plan.matched)
							+ notesFor(plan))
					.setPositiveButton(android.R.string.ok, (d, w) -> {
						session.clear();
						resetLastCard();
						render();
					})
					.setNegativeButton(R.string.inventory_plan_back, null)
					.show();
			return;
		}
		final String[] labels = new String[plan.changes.size()];
		final boolean[] selected = new boolean[labels.length];
		for (int i = 0; i < labels.length; i++) {
			Change c = plan.changes.get(i);
			labels[i] = getString(c.isIncrease() ? R.string.inventory_plan_more : R.string.inventory_plan_less,
					c.entry.name, InventorySession.format(c.before), InventorySession.format(c.after));
			selected[i] = true;
		}
		new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.inventory_plan_title, labels.length))
				.setMultiChoiceItems(labels, selected, (d, which, checked) -> selected[which] = checked)
				.setPositiveButton(R.string.inventory_plan_send, (d, w) -> {
					List<Change> chosen = new ArrayList<>();
					for (int i = 0; i < selected.length; i++) {
						if (selected[i]) {
							chosen.add(plan.changes.get(i));
						}
					}
					send(plan, chosen);
				})
				.setNegativeButton(R.string.inventory_plan_back, null)
				.show();
	}

	private void send(final Plan plan, final List<Change> chosen) {
		showBusy(R.string.inventory_sending);
		final android.content.Context appContext = getApplicationContext();
		GROCY_IO.execute(() -> {
			Outcome outcome = new Outcome();
			for (int i = 0; i < chosen.size(); i++) {
				Change c = chosen.get(i);
				try {
					GrocyInventory.setStock(grocy, c.entry.productId, c.after, c.isIncrease(), c.entry.bestBeforeDays);
					outcome.done.add(c);
					if (c.isIncrease()) {
						outcome.increased++;
					} else {
						outcome.decreased++;
					}
				} catch (IOException e) {
					int status = e instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) e).statusCode : 0;
					if (status == 401 || status == 403) {
						outcome.error = getString(R.string.grocy_err_key);
					} else if (status == 0) {
						outcome.error = getString(R.string.grocy_err_unreachable);
					} else {
						outcome.failed.add(c);
						continue;
					}
					// Grocy nicht erreichbar: die übrigen Buchungen würden ebenfalls scheitern und bleiben offen
					outcome.failed.addAll(chosen.subList(i, chosen.size()));
					break;
				}
			}
			main.post(() -> finishSend(plan, chosen, outcome, appContext));
		});
	}

	private void finishSend(Plan plan, List<Change> chosen, Outcome outcome, android.content.Context appContext) {
		// Erledigtes und Bestätigtes aus der Zählung nehmen; was nicht ging, bleibt zum erneuten Versuch
		if (outcome.failed.isEmpty() && outcome.error == null) {
			session.clear();
			if (!isFinishing() && !isDestroyed()) {
				resetLastCard();
			}
		} else {
			Set<String> keep = new HashSet<>();
			for (Change c : outcome.failed) {
				keep.add(c.entry.key);
			}
			session.keepOnly(keep);
		}
		GrocySync.start(appContext, null);
		dismissBusy();
		if (isFinishing() || isDestroyed()) {
			return;
		}
		refreshStockedCount();
		render();

		int skipped = plan.changes.size() - chosen.size();
		StringBuilder message = new StringBuilder();
		message.append(getString(R.string.inventory_done_matched, plan.matched));
		message.append('\n').append(getString(R.string.inventory_done_changed, outcome.done.size(),
				outcome.increased, outcome.decreased));
		if (skipped > 0) {
			message.append('\n').append(getString(R.string.inventory_done_skipped, skipped));
		}
		if (!outcome.failed.isEmpty()) {
			message.append('\n').append(getString(R.string.inventory_done_failed, outcome.failed.size()));
		}
		if (outcome.error != null) {
			message.append("\n\n").append(getString(R.string.inventory_done_error, outcome.error));
		}
		if (plan.matched + plan.changes.size() > 0) {
			message.append("\n\n").append(getString(R.string.inventory_done_accuracy, plan.accuracyPercent()));
		}
		message.append(notesFor(plan));
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.inventory_done_title)
				.setMessage(message.toString())
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	/** Hinweise zu dem, was die Inventur nicht angefasst hat. */
	private String notesFor(Plan plan) {
		StringBuilder notes = new StringBuilder();
		if (plan.notScanned > 0) {
			notes.append("\n\n").append(getString(R.string.inventory_done_not_scanned, plan.notScanned));
		}
		if (plan.unknown > 0) {
			notes.append("\n\n").append(getString(R.string.inventory_done_unknown, plan.unknown));
		}
		return notes.toString();
	}

	// ---------------------------------------------------------------- Bearbeiten und Menü

	private void showEdit(final Entry e) {
		if (!e.known) {
			showAssign(e);
			return;
		}
		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
		de.bulling.barcodebuddyscanner.Helper.AmountInput.apply(input);
		input.setText(InventorySession.format(e.counted));
		input.setSelectAllOnFocus(true);
		FrameLayout box = new FrameLayout(this);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		box.addView(input);
		new MaterialAlertDialogBuilder(this)
				.setTitle(e.name)
				.setMessage(editMessage(e))
				.setView(box)
				.setPositiveButton(R.string.inventory_edit_save, (d, w) -> {
					Double value = de.bulling.barcodebuddyscanner.Helper.Money.parse(input.getText().toString());
					if (value != null) {
						session.setCounted(e.key, value);
					}
					render();
				})
				.setNeutralButton(R.string.inventory_edit_remove, (d, w) -> {
					session.remove(e.key);
					render();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	// ---------------------------------------------------------------- Unbekannte Barcodes

	private final ActivityResultLauncher<Intent> createProductLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				if (result.getResultCode() == RESULT_OK && assignBarcode != null) {
					resolveUnknown(assignBarcode, 0);
				}
			});

	private final ActivityResultLauncher<Intent> pickProductLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				Intent data = result.getData();
				if (result.getResultCode() == RESULT_OK && data != null && assignBarcode != null) {
					long shop = data.getLongExtra(ProductsActivity.EXTRA_PRODUCT_SHOP, -1);
					linkBarcode(assignBarcode, data.getLongExtra(ProductsActivity.EXTRA_PRODUCT_ID, -1),
							shop < 0 ? null : shop);
				}
			});

	/** Ein Barcode, den Grocy nicht kennt: als neues Produkt anlegen oder einem vorhandenen zuordnen. */
	private void showAssign(final Entry e) {
		String[] choices = {getString(R.string.assign_new), getString(R.string.assign_existing),
				getString(R.string.inventory_edit_remove)};
		new MaterialAlertDialogBuilder(this)
				.setTitle(suggestions.containsKey(e.barcode) ? suggestions.get(e.barcode)
						: getString(R.string.inventory_unknown_name) + " " + e.barcode)
				.setItems(choices, (d, which) -> {
					if (which == 0) {
						assignBarcode = e.barcode;
						createProductLauncher.launch(new Intent(this, ProductCreateActivity.class)
								.putExtra(ProductCreateActivity.EXTRA_BARCODE, e.barcode));
					} else if (which == 1) {
						assignBarcode = e.barcode;
						pickProductLauncher.launch(new Intent(this, ProductsActivity.class)
								.putExtra(ProductsActivity.EXTRA_PICK, true));
					} else {
						session.remove(e.key);
						render();
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Hängt den Barcode an ein vorhandenes Grocy-Produkt. */
	private void linkBarcode(final String barcode, final long productId, final Long shopId) {
		showBusy(R.string.inventory_assigning);
		GROCY_IO.execute(() -> {
			String error = null;
			try {
				GrocyProducts.linkBarcode(grocy, productId, barcode, shopId);
			} catch (IOException e) {
				error = String.valueOf(e.getMessage());
			}
			final String failure = error;
			main.post(() -> {
				dismissBusy();
				if (isFinishing() || isDestroyed()) {
					return;
				}
				if (failure != null) {
					new MaterialAlertDialogBuilder(this)
							.setTitle(R.string.product_create_failed_title)
							.setMessage(getString(R.string.product_create_failed, failure))
							.setPositiveButton(android.R.string.ok, null)
							.show();
					return;
				}
				resolveUnknown(barcode, 0);
			});
		});
	}

	/**
	 * Der Barcode ist jetzt in Grocy bekannt: Stammdaten neu laden und die bisherigen Scans dem Produkt zuschlagen,
	 * damit er in der Zählung nicht verloren geht.
	 */
	private void resolveUnknown(final String barcode, final int attempt) {
		showBusy(R.string.inventory_assigning);
		GrocySync.start(this, (ok, message) -> {
			if (isFinishing() || isDestroyed()) {
				return;
			}
			if (!ok) {
				// Es läuft vielleicht noch die Synchronisierung, die das Anlegen gestartet hat
				if (attempt < 4) {
					main.postDelayed(() -> resolveUnknown(barcode, attempt + 1), 1500);
				} else {
					dismissBusy();
					Toast.makeText(this, message, Toast.LENGTH_LONG).show();
				}
				return;
			}
			repo.findInventoryProduct(barcode, product -> {
				dismissBusy();
				if (product == null) {
					Toast.makeText(this, R.string.inventory_assign_not_found, Toast.LENGTH_LONG).show();
					return;
				}
				int scans = 1;
				for (Entry e : session.snapshot()) {
					if (!e.known && barcode.equals(e.barcode)) {
						scans = Math.max(1, (int) Math.round(e.counted));
						session.remove(e.key);
					}
				}
				Entry entry = null;
				for (int i = 0; i < scans; i++) {
					entry = session.recordProduct(product);
				}
				if (entry != null) {
					showLast(entry);
				}
				refreshStockedCount();
				render();
			});
		});
	}

	// ---------------------------------------------------------------- Packungen mit mehreren Einheiten

	private String unitOf(Entry e) {
		return e.unit == null ? "" : e.unit;
	}

	private String packName(Entry e) {
		// Zählt der Barcode in der Lagereinheit selbst (z. B. 8 Stück), ist "Stück" kein Name für die Packung
		return e.scanUnit == null || e.scanUnit.equals(e.unit) ? getString(R.string.inventory_pack_default) : e.scanUnit;
	}

	/** " (= 4 Pack)": der Bestand in Packungen, wenn der Barcode mehrere Einheiten zählt. */
	private String stockPacks(Entry e) {
		if (!e.known || e.perScan <= 1.0001) {
			return "";
		}
		double packs = e.stock / e.perScan;
		boolean whole = Math.abs(packs - Math.rint(packs)) < 0.0001;
		return " (" + (whole ? "= " : "≈ ") + InventorySession.format(Math.round(packs * 10) / 10.0) + " " + packName(e) + ")";
	}

	/** Meldung unten nach dem Scan einer Packung, die mehr als eine Einheit enthält. */
	private void showPackHint(final Entry e) {
		String text = getString(R.string.inventory_pack_counted, packName(e),
				InventorySession.format(e.lastAmount), unitOf(e)).trim();
		packHint = Snackbar.make(findViewById(android.R.id.content), text, 8000)
				.setAnchorView(R.id.button_finish)
				.setAction(R.string.inventory_pack_adjust, v -> showAdjust(e));
		packHint.show();
	}

	/** Fragt, wie viel in der gerade gescannten Packung tatsächlich noch drin ist. */
	private void showAdjust(final Entry e) {
		final double full = e.perScan;
		if (full != Math.rint(full) || full < 2 || full > 12) {
			showAdjustInput(e);
			return;
		}
		final int n = (int) full;
		final String[] labels = new String[n + 2];
		for (int i = 0; i <= n; i++) {
			int value = n - i;
			labels[i] = value + " " + unitOf(e)
					+ (value == n ? " " + getString(R.string.inventory_pack_full)
					: value == 0 ? " " + getString(R.string.inventory_pack_empty) : "");
		}
		labels[n + 1] = getString(R.string.inventory_pack_other);
		new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.inventory_pack_question, e.name, packName(e)))
				.setItems(labels, (d, which) -> {
					if (which == n + 1) {
						showAdjustInput(e);
					} else {
						applyAdjust(e, n - which);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showAdjustInput(final Entry e) {
		final EditText input = new EditText(this);
		input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
		de.bulling.barcodebuddyscanner.Helper.AmountInput.apply(input);
		input.setText(InventorySession.format(e.lastAmount));
		input.setSelectAllOnFocus(true);
		FrameLayout box = new FrameLayout(this);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		box.addView(input);
		new MaterialAlertDialogBuilder(this)
				.setTitle(getString(R.string.inventory_pack_question, e.name, packName(e)))
				.setMessage(getString(R.string.inventory_edit_message, unitOf(e)))
				.setView(box)
				.setPositiveButton(R.string.inventory_edit_save, (d, w) -> {
					Double value = de.bulling.barcodebuddyscanner.Helper.Money.parse(input.getText().toString());
					if (value != null) {
						applyAdjust(e, value);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyAdjust(Entry e, double actual) {
		session.adjustLastScan(e.key, actual);
		showLast(e);
		render();
	}

	/** Text im Dialog zum Ändern der Menge: Einheit und, falls vorhanden, die Umrechnung der Packung. */
	private String editMessage(Entry e) {
		String text = getString(R.string.inventory_edit_message, unitOf(e)) + "\n"
				+ getString(R.string.inventory_edit_stock, InventorySession.format(e.stock), unitOf(e) + stockPacks(e));
		if (e.perScan > 1.0001) {
			text += "\n" + getString(R.string.inventory_pack_hint, packName(e),
					InventorySession.format(e.perScan), unitOf(e));
		}
		return text;
	}

	private void resetLastCard() {
		lastEntry = null;
		lastName.setText(R.string.inventory_title);
		lastStatus.setText(R.string.inventory_ready);
		lastName.setTextColor(getColor(R.color.md_on_surface));
		lastStatus.setTextColor(getColor(R.color.md_on_surface));
		lastCard.setCardBackgroundColor(getColor(R.color.md_surface_container_highest));
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.inventory, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			finish();
			return true;
		} else if (id == R.id.action_reload_stock) {
			reloadStock();
			return true;
		} else if (id == R.id.action_restart) {
			new MaterialAlertDialogBuilder(this)
					.setTitle(R.string.inventory_restart_title)
					.setMessage(R.string.inventory_restart_text)
					.setPositiveButton(R.string.inventory_restart, (d, w) -> {
						session.clear();
						resetLastCard();
						render();
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	// ---------------------------------------------------------------- Hilfen

	private void showBusy(int messageRes) {
		dismissBusy();
		ProgressBar bar = new ProgressBar(this);
		FrameLayout box = new FrameLayout(this);
		int pad = (int) (24 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad, pad, pad / 2);
		box.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.CENTER));
		busy = new MaterialAlertDialogBuilder(this)
				.setTitle(messageRes)
				.setView(box)
				.setCancelable(false)
				.show();
	}

	private void dismissBusy() {
		if (busy != null) {
			busy.dismiss();
			busy = null;
		}
	}

	// ---------------------------------------------------------------- Liste

	private class InventoryAdapter extends RecyclerView.Adapter<InventoryAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_inventory, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			Entry e = shown.get(position);
			Status status = InventorySession.status(e);
			int[] colors = colorsFor(status);
			holder.name.setText(e.known ? e.name : unknownLabel(e, " "));
			switch (status) {
				case UNKNOWN:
					holder.detail.setText(R.string.inventory_detail_unknown);
					holder.badge.setText(getString(R.string.scan_status, "", (int) Math.round(e.counted)).trim());
					break;
				default:
					String detail = getString(R.string.inventory_detail, InventorySession.format(e.counted),
							InventorySession.format(e.stock), unitOf(e), stockPacks(e)).trim();
					if (e.perScan > 1.0001) {
						detail += "\n" + getString(R.string.inventory_pack_hint, packName(e),
								InventorySession.format(e.perScan), unitOf(e));
					}
					holder.detail.setText(detail);
					holder.badge.setText(InventorySession.format(e.counted) + " / " + InventorySession.format(e.stock));
					break;
			}
			holder.badge.setBackgroundTintList(ColorStateList.valueOf(colors[0]));
			holder.badge.setTextColor(colors[1]);
			holder.itemView.setOnClickListener(v -> showEdit(e));
		}

		@Override
		public int getItemCount() {
			return shown.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView name;
			final TextView detail;
			final TextView badge;

			ViewHolder(View view) {
				super(view);
				name   = view.findViewById(R.id.inv_name);
				detail = view.findViewById(R.id.inv_detail);
				badge  = view.findViewById(R.id.inv_badge);
			}
		}
	}
}
