package de.bulling.barcodebuddyscanner;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;

/**
 * Reihenfolge, in der die Geschäfte in der Einkaufsliste erscheinen. Per Ziehen am
 * Griff sortieren; neue Geschäfte aus Grocy erscheinen automatisch am Ende.
 */
public class ShopOrderActivity extends AppCompatActivity {

	private ShoppingRepository          repo;
	private final List<ShoppingRepository.Shop> shops = new ArrayList<>();
	private ShopAdapter                 adapter;
	private ItemTouchHelper             touchHelper;
	private TextView                    emptyView;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_shop_order);
		repo = ShoppingRepository.get(this);

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);

		RecyclerView list = findViewById(R.id.list);
		EdgeToEdgeHelper.applyInsetsAsPadding(list, true, false, true, true);
		emptyView = findViewById(R.id.empty_view);
		adapter = new ShopAdapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);

		touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
				ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
			@Override
			public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
								  @NonNull RecyclerView.ViewHolder target) {
				int from = viewHolder.getBindingAdapterPosition();
				int to = target.getBindingAdapterPosition();
				Collections.swap(shops, from, to);
				adapter.notifyItemMoved(from, to);
				return true;
			}

			@Override
			public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
			}

			@Override
			public boolean isLongPressDragEnabled() {
				return false;
			}

			@Override
			public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
				super.clearView(recyclerView, viewHolder);
				saveOrder();
			}
		});
		touchHelper.attachToRecyclerView(list);

		repo.getShops(result -> {
			shops.clear();
			shops.addAll(result);
			adapter.notifyDataSetChanged();
			emptyView.setVisibility(shops.isEmpty() ? View.VISIBLE : View.GONE);
		});
	}

	private void saveOrder() {
		List<Long> ids = new ArrayList<>();
		for (ShoppingRepository.Shop shop : shops) {
			ids.add(shop.id);
		}
		repo.saveShopOrder(ids);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private class ShopAdapter extends RecyclerView.Adapter<ShopAdapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_shop, parent, false));
		}

		@SuppressLint("ClickableViewAccessibility")
		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			holder.name.setText(shops.get(position).name);
			holder.handle.setOnTouchListener((v, event) -> {
				if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
					touchHelper.startDrag(holder);
				}
				return false;
			});
		}

		@Override
		public int getItemCount() {
			return shops.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView name;
			final View     handle;

			ViewHolder(View view) {
				super(view);
				name   = view.findViewById(R.id.shop_name);
				handle = view.findViewById(R.id.shop_handle);
			}
		}
	}
}
