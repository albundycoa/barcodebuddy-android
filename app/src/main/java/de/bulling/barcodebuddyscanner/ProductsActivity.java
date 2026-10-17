package de.bulling.barcodebuddyscanner;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Die lokale Produktdatenbank aus Grocy: durchsuchen und ansehen.
 */
public class ProductsActivity extends AppCompatActivity implements ShoppingRepository.Listener {

	/** Auswahlmodus: ein Antippen gibt das Produkt zurück, statt seine Barcodes zu zeigen. */
	public static final String EXTRA_PICK         = "pick";
	public static final String EXTRA_PRODUCT_ID   = "product_id";
	public static final String EXTRA_PRODUCT_NAME = "product_name";
	public static final String EXTRA_PRODUCT_SHOP = "product_shop";

	private boolean pickMode;

	private ShoppingRepository                       repo;
	private final List<ShoppingRepository.ProductRow> products = new ArrayList<>();
	private ProductAdapter                           adapter;
	private TextView                                 header;
	private TextView                                 emptyView;
	private EditText                                 search;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_products);
		repo = ShoppingRepository.get(this);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);

		pickMode = getIntent().getBooleanExtra(EXTRA_PICK, false);
		if (pickMode) {
			setTitle(R.string.product_pick_title);
			toolbar.setTitle(R.string.product_pick_title);
		}
		header = findViewById(R.id.products_header);
		emptyView = findViewById(R.id.empty_view);
		search = findViewById(R.id.search);
		RecyclerView list = findViewById(R.id.list);
		EdgeToEdgeHelper.applyInsetsAsPadding(list, true, false, true, true);
		adapter = new ProductAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);

		search.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before, int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				load();
			}
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		repo.addListener(this);
		load();
	}

	@Override
	protected void onStop() {
		super.onStop();
		repo.removeListener(this);
	}

	@Override
	public void onDataChanged() {
		load();
	}

	private void load() {
		repo.getProducts(search.getText().toString(), rows -> {
			products.clear();
			products.addAll(rows);
			adapter.notifyDataSetChanged();
			emptyView.setVisibility(products.isEmpty() ? View.VISIBLE : View.GONE);
			long last = SharedPrefHelper.getGrocyLastSync(this);
			String when = last == 0 ? getString(R.string.products_never_synced)
					: getString(R.string.products_synced_at,
					DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(last)));
			header.setText(getString(R.string.products_header, products.size(), when));
		});
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.products, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == android.R.id.home) {
			finish();
			return true;
		} else if (id == R.id.action_sync) {
			header.setText(R.string.grocy_syncing);
			GrocySync.start(this, (ok, message) -> {
				if (!ok && !isFinishing() && !isDestroyed()) {
					Toast.makeText(this, message, Toast.LENGTH_LONG).show();
					load();
				}
			});
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void showBarcodes(final ShoppingRepository.ProductRow product) {
		repo.getProductBarcodes(product.id, lines -> {
			StringBuilder message = new StringBuilder();
			for (String line : lines) {
				message.append(line).append('\n');
			}
			new MaterialAlertDialogBuilder(this)
					.setTitle(product.name)
					.setMessage(message.toString().trim())
					.setPositiveButton(android.R.string.ok, null)
					.show();
		});
	}

	private class ProductAdapter extends RecyclerView.Adapter<ProductAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_product, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			ShoppingRepository.ProductRow p = products.get(position);
			holder.name.setText(p.name);

			StringBuilder line1 = new StringBuilder();
			if (p.group != null) {
				line1.append(p.group);
			}
			if (p.shop != null) {
				if (line1.length() > 0) {
					line1.append(" · ");
				}
				line1.append(p.shop);
			}
			holder.group.setText(line1);
			holder.group.setVisibility(line1.length() > 0 ? View.VISIBLE : View.GONE);

			StringBuilder line2 = new StringBuilder();
			if (p.stock != null) {
				line2.append(getString(R.string.products_detail_stock, trim(p.stock),
						p.stockUnit == null ? "" : p.stockUnit).trim());
			}
			if (p.lastPrice != null) {
				if (line2.length() > 0) {
					line2.append(" · ");
				}
				line2.append(getString(R.string.products_detail_price, Money.format(p.lastPrice),
						p.priceUnit == null ? "" : p.priceUnit));
			}
			if (line2.length() > 0) {
				line2.append(" · ");
			}
			line2.append(getResources().getQuantityString(R.plurals.products_detail_barcodes, p.barcodes, p.barcodes));
			holder.detail.setText(line2);
			holder.itemView.setOnClickListener(v -> {
				if (pickMode) {
					// Auswahlmodus: das gewählte Produkt an die aufrufende Seite zurückgeben
					android.content.Intent result = new android.content.Intent()
							.putExtra(EXTRA_PRODUCT_ID, p.id)
							.putExtra(EXTRA_PRODUCT_NAME, p.name)
							.putExtra(EXTRA_PRODUCT_SHOP, p.shopId == null ? -1L : p.shopId);
					setResult(RESULT_OK, result);
					finish();
				} else {
					showBarcodes(p);
				}
			});
		}

		@Override
		public int getItemCount() {
			return products.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView name;
			final TextView group;
			final TextView detail;

			ViewHolder(View view) {
				super(view);
				name   = view.findViewById(R.id.product_name);
				group  = view.findViewById(R.id.product_group);
				detail = view.findViewById(R.id.product_detail);
			}
		}
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', ',');
	}
}
