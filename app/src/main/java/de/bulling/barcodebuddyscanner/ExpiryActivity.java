package de.bulling.barcodebuddyscanner;

import android.content.res.ColorStateList;
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

import java.io.IOException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyStock;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.DueDates;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Ablauf-Übersicht: Was im Vorrat bald abläuft oder schon abgelaufen ist, nach Datum sortiert.
 */
public class ExpiryActivity extends AppCompatActivity {

	private static final ExecutorService GROCY_IO = Executors.newSingleThreadExecutor();

	/** Anzeigebereich in Tagen; ALL zeigt alles, EXPIRED nur Abgelaufenes */
	private static final int ALL = Integer.MAX_VALUE;
	private static final int EXPIRED = -1;

	private static final class Row {
		String name;
		String unit;
		double amount;
		String due;
		int    days;
	}

	private final Handler main = new Handler(Looper.getMainLooper());
	private final List<Row> all = new ArrayList<>();
	private final List<Row> shown = new ArrayList<>();

	private GrocyClient grocy;
	private ShoppingRepository repo;
	private RowAdapter adapter;
	private TextView header;
	private TextView emptyView;
	private int range = 14;
	private boolean loaded;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		grocy = new SharedPrefHelper(this).initGrocyClient();
		if (grocy == null) {
			Toast.makeText(this, R.string.inventory_no_grocy, Toast.LENGTH_LONG).show();
			finish();
			return;
		}
		setContentView(R.layout.activity_expiry);
		repo = ShoppingRepository.get(this);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		header = findViewById(R.id.header);
		emptyView = findViewById(R.id.empty_view);
		RecyclerView list = findViewById(R.id.list);
		EdgeToEdgeHelper.applyInsetsAsPadding(list, true, false, true, true);
		adapter = new RowAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		load();
	}

	private void load() {
		header.setText(R.string.expiry_loading);
		repo.getProducts("", products -> {
			final Map<Long, ShoppingRepository.ProductRow> byId = new HashMap<>();
			for (ShoppingRepository.ProductRow p : products) {
				byId.put(p.id, p);
			}
			GROCY_IO.execute(() -> {
				List<GrocyStock.Batch> batches = null;
				try {
					batches = GrocyStock.loadBatches(grocy);
				} catch (IOException ignored) {
					// unten gemeldet
				}
				final List<GrocyStock.Batch> result = batches;
				main.post(() -> {
					if (isFinishing() || isDestroyed()) {
						return;
					}
					if (result == null) {
						header.setText(R.string.grocy_err_unreachable);
						return;
					}
					all.clear();
					for (GrocyStock.Batch b : result) {
						Integer days = DueDates.daysUntil(b.due);
						ShoppingRepository.ProductRow p = byId.get(b.productId);
						if (days == null || b.amount <= 0) {
							continue;
						}
						Row row = new Row();
						row.name   = p == null ? "#" + b.productId : p.name;
						row.unit   = p == null || p.stockUnit == null ? "" : p.stockUnit;
						row.amount = b.amount;
						row.due    = b.due;
						row.days   = days;
						all.add(row);
					}
					Collections.sort(all, (a, c) -> a.days != c.days ? Integer.compare(a.days, c.days)
							: a.name.compareToIgnoreCase(c.name));
					loaded = true;
					applyRange();
				});
			});
		});
	}

	private void applyRange() {
		shown.clear();
		for (Row r : all) {
			if (range == ALL || (range == EXPIRED ? r.days < 0 : r.days <= range)) {
				shown.add(r);
			}
		}
		adapter.notifyDataSetChanged();
		int expired = 0;
		for (Row r : shown) {
			if (r.days < 0) {
				expired++;
			}
		}
		String label = range == ALL ? getString(R.string.expiry_range_all)
				: range == EXPIRED ? getString(R.string.expiry_range_expired)
				: getString(R.string.expiry_range_days, range);
		header.setText(getString(R.string.expiry_header, shown.size(), label, expired));
		emptyView.setText(R.string.expiry_empty);
		emptyView.setVisibility(loaded && shown.isEmpty() ? View.VISIBLE : View.GONE);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.expiry, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			finish();
		} else if (id == R.id.range_expired) {
			range = EXPIRED;
			applyRange();
		} else if (id == R.id.range_7) {
			range = 7;
			applyRange();
		} else if (id == R.id.range_14) {
			range = 14;
			applyRange();
		} else if (id == R.id.range_30) {
			range = 30;
			applyRange();
		} else if (id == R.id.range_all) {
			range = ALL;
			applyRange();
		} else if (id == R.id.action_reload) {
			load();
		} else {
			return super.onOptionsItemSelected(item);
		}
		return true;
	}

	private int[] colorsFor(int days) {
		if (days < 0) {
			return new int[]{getColor(R.color.scanner_off_bg), getColor(R.color.scanner_off_text)};
		} else if (days <= 3) {
			return new int[]{getColor(R.color.inventory_more_bg), getColor(R.color.inventory_more_text)};
		} else if (days <= 7) {
			return new int[]{getColor(R.color.md_tertiary_container), getColor(R.color.md_on_tertiary_container)};
		}
		return new int[]{getColor(R.color.md_surface_variant), getColor(R.color.md_on_surface_variant)};
	}

	private class RowAdapter extends RecyclerView.Adapter<RowAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_inventory, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder h, int position) {
			Row r = shown.get(position);
			h.name.setText(r.name);
			String date;
			try {
				date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(
						new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(r.due));
			} catch (ParseException e) {
				date = r.due;
			}
			h.detail.setText(getString(R.string.expiry_detail, InventorySession.format(r.amount), r.unit, date).trim());
			h.badge.setText(r.days < 0 ? getString(R.string.expiry_past, -r.days)
					: r.days == 0 ? getString(R.string.expiry_today) : getString(R.string.expiry_future, r.days));
			int[] colors = colorsFor(r.days);
			h.badge.setBackgroundTintList(ColorStateList.valueOf(colors[0]));
			h.badge.setTextColor(colors[1]);
			h.itemView.setClickable(false);
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
