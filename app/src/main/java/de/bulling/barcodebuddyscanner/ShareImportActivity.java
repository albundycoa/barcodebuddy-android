package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ProductRow;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.MarktguruShare;
import de.bulling.barcodebuddyscanner.Helper.ProductMatch;
import de.bulling.barcodebuddyscanner.Helper.ProductSearch;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;
import de.bulling.barcodebuddyscanner.Helper.ShoppingEdit;

/**
 * Teilen-Ziel "Zur Einkaufsliste": Übernimmt einen geteilten Text (zum Beispiel die Einkaufsliste der Marktguru-App) auf die
 * Einkaufsliste in Grocy. Zu jedem erkannten Eintrag schlägt die Maske ein Grocy-Produkt vor; was nicht passt, wird als freier
 * Eintrag mit einer gewählten Produktgruppe angelegt. Vor dem Hinzufügen lässt sich alles ändern.
 */
public class ShareImportActivity extends AppCompatActivity {

	private static final int MAX_RESULTS = 40;

	/** Ein erkannter Eintrag mit Auswahl und (vorgeschlagenem) Produkt. */
	private static final class Row {
		final MarktguruShare.Item item;
		boolean    take = true;
		ProductRow product;

		Row(MarktguruShare.Item item, ProductRow product) {
			this.item    = item;
			this.product = product;
		}
	}

	private final List<Row> rows = new ArrayList<>();
	private final List<ProductRow> products = new ArrayList<>();
	private final List<ProductSearch.Item> searchItems = new ArrayList<>();
	private final List<String> groupNames = new ArrayList<>();
	private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
	private MaterialAutoCompleteTextView group;
	private MaterialButton addButton;
	private TextView hint;
	private String noGroup;
	private boolean adding;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (new SharedPrefHelper(this).initGrocyClient() == null) {
			Toast.makeText(this, R.string.product_no_grocy, Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		setContentView(R.layout.activity_share_import);
		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.content), false, false, false, true);

		hint      = findViewById(R.id.hint);
		group     = findViewById(R.id.group);
		addButton = findViewById(R.id.add);
		noGroup   = getString(R.string.free_group_none);
		groupNames.add(noGroup);
		group.setText(noGroup, false);

		RecyclerView list = findViewById(R.id.list);
		list.setLayoutManager(new LinearLayoutManager(this));
		adapter = new RowAdapter();
		list.setAdapter(adapter);
		addButton.setOnClickListener(v -> addAll());
		addButton.setEnabled(false);

		final CharSequence shared = getIntent() == null ? null : getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
		final List<MarktguruShare.Item> parsed = MarktguruShare.parse(shared == null ? "" : shared.toString());
		if (parsed.isEmpty()) {
			hint.setText(R.string.share_import_empty);
			return;
		}

		final ShoppingRepository repo = ShoppingRepository.get(this);
		repo.getProducts("", all -> repo.getGroups(names -> {
			products.clear();
			products.addAll(all);
			searchItems.clear();
			for (ProductRow p : all) {
				searchItems.add(new ProductSearch.Item(p.id, p.name, p.group));
			}
			groupNames.addAll(names);
			group.setAdapter(new ArrayAdapter<>(this,
					com.google.android.material.R.layout.m3_auto_complete_simple_item, groupNames));
			Map<Long, ProductRow> byId = new HashMap<>();
			for (ProductRow p : all) {
				byId.put(p.id, p);
			}
			rows.clear();
			for (MarktguruShare.Item item : parsed) {
				ProductSearch.Item hit = ProductMatch.best(item.name, searchItems);
				rows.add(new Row(item, hit == null ? null : byId.get(hit.id)));
			}
			hint.setText(getString(R.string.share_import_hint, rows.size()));
			adapter.notifyDataSetChanged();
			updateButton();
		}));
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void updateButton() {
		int count = 0;
		for (Row row : rows) {
			if (row.take) {
				count++;
			}
		}
		addButton.setText(count == 0 ? getString(R.string.share_import_add) : getString(R.string.share_import_add_count, count));
		addButton.setEnabled(count > 0 && !adding);
	}

	// ---------------------------------------------------------------- Zuordnung ändern

	/** Auswahl für eine Zeile: vorgeschlagenes Produkt, freier Eintrag oder ein anderes Produkt suchen. */
	private void chooseTarget(final Row row) {
		final List<String> labels = new ArrayList<>();
		final ProductSearch.Item suggestion = ProductMatch.best(row.item.name, searchItems);
		ProductRow suggested = null;
		if (suggestion != null) {
			for (ProductRow p : products) {
				if (p.id == suggestion.id) {
					suggested = p;
					break;
				}
			}
		}
		final ProductRow suggestedProduct = suggested;
		if (suggestedProduct != null) {
			labels.add(getString(R.string.share_import_product, describe(suggestedProduct)));
		}
		labels.add(getString(R.string.share_import_free));
		labels.add(getString(R.string.share_import_pick));
		new MaterialAlertDialogBuilder(this)
				.setTitle(row.item.name)
				.setItems(labels.toArray(new String[0]), (d, which) -> {
					int offset = suggestedProduct != null ? 1 : 0;
					if (suggestedProduct != null && which == 0) {
						row.product = suggestedProduct;
						adapter.notifyDataSetChanged();
					} else if (which == offset) {
						row.product = null;
						adapter.notifyDataSetChanged();
					} else {
						pickProduct(row);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	/** Produktsuche in einer kleinen Maske. */
	private void pickProduct(final Row row) {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (20 * getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		final TextInputEditText search = new TextInputEditText(this);
		search.setHint(R.string.share_import_search_hint);
		search.setSingleLine(true);
		box.addView(search);
		final ListView results = new ListView(this);
		box.addView(results, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				(int) (300 * getResources().getDisplayMetrics().density)));

		final List<ProductSearch.Item> hits = new ArrayList<>();
		final List<String> labels = new ArrayList<>();
		final ArrayAdapter<String> listAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
		results.setAdapter(listAdapter);
		final Runnable refresh = () -> {
			hits.clear();
			hits.addAll(ProductSearch.search(searchItems, String.valueOf(search.getText()), MAX_RESULTS));
			labels.clear();
			for (ProductSearch.Item hit : hits) {
				labels.add(hit.group == null || hit.group.isEmpty() ? hit.name : hit.name + " · " + hit.group);
			}
			listAdapter.notifyDataSetChanged();
		};
		search.addTextChangedListener(new SimpleTextWatcher() {
			@Override
			public void onChanged(String text) {
				refresh.run();
			}
		});
		refresh.run();

		final androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.share_import_pick_title)
				.setView(box)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		results.setOnItemClickListener((parent, view, position, id) -> {
			ProductSearch.Item hit = hits.get(position);
			for (ProductRow p : products) {
				if (p.id == hit.id) {
					row.product = p;
					break;
				}
			}
			adapter.notifyDataSetChanged();
			dialog.dismiss();
		});
		dialog.show();
	}

	private static String describe(ProductRow product) {
		return product.group == null || product.group.isEmpty() ? product.name : product.name + " (" + product.group + ")";
	}

	// ---------------------------------------------------------------- Hinzufügen

	private void addAll() {
		final List<Row> todo = new ArrayList<>();
		for (Row row : rows) {
			if (row.take) {
				todo.add(row);
			}
		}
		if (todo.isEmpty()) {
			Toast.makeText(this, R.string.share_import_none_selected, Toast.LENGTH_SHORT).show();
			return;
		}
		String chosen = String.valueOf(group.getText());
		final String groupName = chosen.isEmpty() || chosen.equals(noGroup) ? null : chosen;
		adding = true;
		updateButton();
		runNext(todo, 0, groupName, new ArrayList<>());
	}

	private void runNext(final List<Row> todo, final int index, final String groupName, final List<String> failed) {
		if (index >= todo.size()) {
			finishAdding(todo.size(), failed);
			return;
		}
		final Row row = todo.get(index);
		ShoppingEdit.Result next = error -> {
			if (error != null) {
				failed.add(row.item.name + " (" + error + ")");
			}
			runNext(todo, index + 1, groupName, failed);
		};
		Double price = row.item.priceValue();
		ShoppingEdit.Offer offer = price == null ? null
				: new ShoppingEdit.Offer(price, row.item.validUntil(), row.item.shop.isEmpty() ? null : row.item.shop);
		if (row.product != null) {
			ShoppingEdit.addMore(this, row.product.id, row.item.quantity, offer, next);
		} else {
			ShoppingEdit.addNote(this, row.item.name, row.item.quantity, groupName, offer, next);
		}
	}

	private void finishAdding(int total, List<String> failed) {
		if (failed.isEmpty()) {
			Toast.makeText(getApplicationContext(), getString(R.string.share_import_done, total), Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		StringBuilder names = new StringBuilder();
		for (String f : failed) {
			names.append(names.length() == 0 ? "" : ", ").append(f);
		}
		adding = false;
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.product_create_failed_title)
				.setMessage(getString(R.string.share_import_partly, total - failed.size(), total, names))
				.setPositiveButton(android.R.string.ok, (d, w) -> finish())
				.setCancelable(false)
				.show();
	}

	// ---------------------------------------------------------------- Liste

	private final class RowAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

		@NonNull
		@Override
		public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new RecyclerView.ViewHolder(LayoutInflater.from(parent.getContext())
					.inflate(R.layout.item_share_import, parent, false)) {
			};
		}

		@Override
		public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
			final Row row = rows.get(position);
			View view = holder.itemView;
			CheckBox take = view.findViewById(R.id.take);
			TextView name = view.findViewById(R.id.name);
			TextView detail = view.findViewById(R.id.detail);
			TextView target = view.findViewById(R.id.target);
			take.setOnCheckedChangeListener(null);
			take.setChecked(row.take);
			take.setOnCheckedChangeListener((b, checked) -> {
				row.take = checked;
				updateButton();
			});
			name.setText(row.item.name);
			StringBuilder info = new StringBuilder(getString(R.string.share_import_detail, row.item.quantity,
					row.item.shop.isEmpty() ? "–" : row.item.shop));
			if (!row.item.validity.isEmpty()) {
				info.append(" · ").append(row.item.validity);
			}
			detail.setText(info);
			target.setText(row.product != null ? getString(R.string.share_import_product, describe(row.product))
					: getString(R.string.share_import_free));
			target.setOnClickListener(v -> chooseTarget(row));
			view.setOnClickListener(v -> take.performClick());
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}
	}
}
