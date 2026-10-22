package de.bulling.barcodebuddyscanner;

import android.app.Activity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
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
import de.bulling.barcodebuddyscanner.Api.GrocyShopping;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.Option;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ProductRow;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ShoppingEntry;
import de.bulling.barcodebuddyscanner.Helper.AmountInput;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.ProductSearch;
import de.bulling.barcodebuddyscanner.Helper.ShoppingEdit;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;
import de.bulling.barcodebuddyscanner.Helper.StockOverview;

/**
 * "Zur Einkaufsliste hinzufügen": Maske von unten mit Produktsuche. Ein Treffer öffnet die Mengenmaske (Menge und Einheit), und wer
 * etwas findet, das es in Grocy noch nicht gibt, legt einen freien Eintrag an, dem er eine Produktgruppe geben kann, damit er in
 * der gewählten Reihenfolge einsortiert wird.
 */
final class AddToList {

	private static final ExecutorService IO = Executors.newSingleThreadExecutor();
	private static final int MAX_RESULTS = 40;

	private AddToList() {
	}

	/** Zeile der Trefferliste: ein Produkt oder der Eintrag "Freier Eintrag". */
	private static final class Row {
		final ProductRow product;
		final boolean    free;

		Row(ProductRow product, boolean free) {
			this.product = product;
			this.free    = free;
		}
	}

	static void show(final Activity activity, final ShoppingRepository repo, final List<ShoppingEntry> onList) {
		if (new SharedPrefHelper(activity).initGrocyClient() == null) {
			Toast.makeText(activity, R.string.product_no_grocy, Toast.LENGTH_LONG).show();
			return;
		}
		repo.getProducts("", products -> repo.getOptions("units", units -> {
			final Map<Long, String> unitNames = new HashMap<>();
			for (Option option : units) {
				unitNames.put(option.id, option.name);
			}
			open(activity, repo, products, unitNames, onList);
		}));
	}

	private static void open(final Activity activity, final ShoppingRepository repo, final List<ProductRow> products,
							 final Map<Long, String> unitNames, final List<ShoppingEntry> onList) {
		final Map<Long, ProductRow> byId = new HashMap<>();
		final List<ProductSearch.Item> items = new ArrayList<>();
		for (ProductRow p : products) {
			byId.put(p.id, p);
			items.add(new ProductSearch.Item(p.id, p.name, p.group));
		}
		final Map<Long, List<ShoppingEntry>> listed = new HashMap<>();
		for (ShoppingEntry e : onList) {
			if (e.productId != null) {
				List<ShoppingEntry> forProduct = listed.get(e.productId);
				if (forProduct == null) {
					forProduct = new ArrayList<>();
					listed.put(e.productId, forProduct);
				}
				forProduct.add(e);
			}
		}

		final BottomSheetDialog sheet = new BottomSheetDialog(activity);
		View view = activity.getLayoutInflater().inflate(R.layout.sheet_add_to_list, null);
		sheet.setContentView(view);
		sheet.getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);
		sheet.getBehavior().setSkipCollapsed(true);
		final TextInputEditText search = view.findViewById(R.id.pick_search);
		RecyclerView list = view.findViewById(R.id.pick_list);
		list.setLayoutManager(new LinearLayoutManager(activity));

		final List<Row> rows = new ArrayList<>();
		final String[] query = {""};
		final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@NonNull
			@Override
			public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pick, parent, false)) {
				};
			}

			@Override
			public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
				final Row row = rows.get(position);
				TextView name = holder.itemView.findViewById(R.id.pick_name);
				TextView sub = holder.itemView.findViewById(R.id.pick_sub);
				if (row.free) {
					String text = query[0].trim();
					name.setText(text.isEmpty() ? activity.getString(R.string.add_list_free_empty)
							: activity.getString(R.string.add_list_free, text));
					sub.setText(R.string.add_list_free_sub);
					holder.itemView.setOnClickListener(v -> {
						sheet.dismiss();
						showFreeEntry(activity, repo, query[0].trim());
					});
					return;
				}
				name.setText(row.product.name);
				StringBuilder detail = new StringBuilder();
				if (row.product.group != null) {
					detail.append(row.product.group);
				}
				List<ShoppingEntry> existing = listed.get(row.product.id);
				if (existing != null && !existing.isEmpty()) {
					if (detail.length() > 0) {
						detail.append(" · ");
					}
					StringBuilder amounts = new StringBuilder();
					for (ShoppingEntry e : existing) {
						if (amounts.length() > 0) {
							amounts.append(", ");
						}
						amounts.append((InventorySession.format(e.amount) + " " + (e.unit == null ? "" : e.unit)).trim());
					}
					detail.append(activity.getString(R.string.stock_overview_on_list, amounts));
				}
				sub.setText(detail);
				sub.setVisibility(detail.length() == 0 ? View.GONE : View.VISIBLE);
				holder.itemView.setOnClickListener(v -> {
					sheet.dismiss();
					chooseAmount(activity, repo, row.product, unitNames, existingRows(listed.get(row.product.id)));
				});
			}

			@Override
			public int getItemCount() {
				return rows.size();
			}
		};
		list.setAdapter(adapter);

		final Runnable refresh = () -> {
			rows.clear();
			for (ProductSearch.Item hit : ProductSearch.search(items, query[0], MAX_RESULTS)) {
				ProductRow p = byId.get(hit.id);
				if (p != null) {
					rows.add(new Row(p, false));
				}
			}
			// Was Grocy nicht kennt, lässt sich immer als freier Eintrag anlegen
			rows.add(new Row(null, true));
			adapter.notifyDataSetChanged();
		};
		search.addTextChangedListener(new SimpleTextWatcher() {
			@Override
			public void onChanged(String text) {
				query[0] = text;
				refresh.run();
			}
		});
		refresh.run();
		sheet.show();
		search.requestFocus();
	}

	private static List<StockOverview.ListRow> existingRows(List<ShoppingEntry> entries) {
		List<StockOverview.ListRow> rows = new ArrayList<>();
		if (entries != null) {
			for (ShoppingEntry e : entries) {
				rows.add(new StockOverview.ListRow(e.id, e.amount, e.quId, e.unit));
			}
		}
		return rows;
	}

	/** Lädt Einheiten des Produkts aus Grocy und zeigt dann die Mengenmaske. */
	private static void chooseAmount(final Activity activity, final ShoppingRepository repo, final ProductRow product,
									 final Map<Long, String> unitNames, final List<StockOverview.ListRow> existing) {
		final GrocyClient grocy = new SharedPrefHelper(activity).initGrocyClient();
		if (grocy == null) {
			return;
		}
		Toast.makeText(activity, R.string.add_list_loading, Toast.LENGTH_SHORT).show();
		IO.execute(() -> {
			StockOverview.Product setup = null;
			try {
				setup = GrocyShopping.loadProduct(grocy, product.id);
			} catch (IOException ignored) {
				// ohne Einheiten gilt Grocys Standard
			}
			final StockOverview.Product found = setup;
			activity.runOnUiThread(() -> {
				if (activity.isFinishing() || activity.isDestroyed()) {
					return;
				}
				ShoppingAmountSheet.show(activity, product.name, found == null ? 0 : found.quPurchase,
						found == null ? 0 : found.quStock, product.stockUnit, unitNames, existing,
						new ShoppingAmountSheet.Listener() {
							@Override
							public void onSave(double amount, long quId, String unitName) {
								final String done = activity.getString(R.string.stock_list_done, product.name,
										(InventorySession.format(amount) + " " + unitName).trim());
								ShoppingEdit.addProduct(activity, product.id, amount, quId, error -> report(activity, error, done));
							}

							@Override
							public void onRemove(StockOverview.ListRow row) {
								final String done = activity.getString(R.string.stock_list_removed, product.name);
								ShoppingEdit.delete(activity, row.id, error -> report(activity, error, done));
							}
						});
			});
		});
	}

	/** Dialog für einen freien Eintrag: Name, Menge und (zum Sortieren) die Produktgruppe. */
	static void showFreeEntry(final Activity activity, final ShoppingRepository repo, String presetName) {
		final View view = activity.getLayoutInflater().inflate(R.layout.dialog_free_entry, null);
		final TextInputLayout nameLayout = view.findViewById(R.id.free_name_layout);
		final TextInputEditText name = view.findViewById(R.id.free_name);
		final TextInputLayout amountLayout = view.findViewById(R.id.free_amount_layout);
		final TextInputEditText amount = view.findViewById(R.id.free_amount);
		final MaterialAutoCompleteTextView group = view.findViewById(R.id.free_group);
		AmountInput.apply(amount);
		name.setText(presetName);
		amount.setText("1");

		final List<String> groupNames = new ArrayList<>();
		final String none = activity.getString(R.string.free_group_none);
		groupNames.add(none);
		repo.getGroups(names -> {
			groupNames.addAll(names);
			group.setAdapter(new ArrayAdapter<>(activity,
					com.google.android.material.R.layout.m3_auto_complete_simple_item, groupNames));
		});
		group.setText(none, false);

		final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(R.string.free_title)
				.setView(view)
				.setPositiveButton(R.string.free_add, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			String text = String.valueOf(name.getText()).trim();
			if (text.isEmpty()) {
				nameLayout.setError(activity.getString(R.string.free_err_name));
				return;
			}
			Double count = Money.parse(String.valueOf(amount.getText()));
			if (count == null || count <= 0) {
				amountLayout.setError(activity.getString(R.string.stock_list_err_amount));
				return;
			}
			String chosen = String.valueOf(group.getText());
			final String groupName = chosen.equals(none) || chosen.isEmpty() ? null : chosen;
			dialog.dismiss();
			final String done = activity.getString(R.string.free_done, text);
			ShoppingEdit.addNote(activity, text, count, groupName, error -> report(activity, error, done));
		}));
		dialog.show();
		name.requestFocus();
	}

	private static void report(Activity activity, String error, String done) {
		if (activity.isFinishing() || activity.isDestroyed()) {
			return;
		}
		if (error != null) {
			new MaterialAlertDialogBuilder(activity)
					.setTitle(R.string.product_create_failed_title)
					.setMessage(activity.getString(R.string.product_create_failed, error))
					.setPositiveButton(android.R.string.ok, null)
					.show();
		} else {
			View anchor = activity.findViewById(android.R.id.content);
			Snackbar.make(anchor, done, Snackbar.LENGTH_LONG).show();
		}
	}
}
