package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.wear.widget.WearableRecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Bestand aus Grocy: Liste mit Menge und Wert, Antippen öffnet das Produkt zum Verbrauchen oder Öffnen. */
public class StockActivity extends Activity implements State.Listener {

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ITEM   = 1;

	private final List<JsonObject> items = new ArrayList<>();
	private String header = "";
	private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
	private TextView empty;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_list);
		WearableRecyclerView list = findViewById(R.id.list);
		empty = findViewById(R.id.empty);
		list.setEdgeItemsCenteringEnabled(false);
		list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
		adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@Override
			public int getItemViewType(int position) {
				return position == 0 ? TYPE_HEADER : TYPE_ITEM;
			}

			@NonNull
			@Override
			public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				LayoutInflater inflater = LayoutInflater.from(parent.getContext());
				int layout = viewType == TYPE_HEADER ? R.layout.item_header : R.layout.item_stock;
				return new RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {
				};
			}

			@Override
			public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
				if (position == 0) {
					((TextView) holder.itemView).setText(header);
					return;
				}
				final JsonObject item = items.get(position - 1);
				((TextView) holder.itemView.findViewById(R.id.name)).setText(item.get("n").getAsString());
				((TextView) holder.itemView.findViewById(R.id.sub)).setText(
						item.get("a").getAsString() + " · " + item.get("w").getAsString());
				holder.itemView.setOnClickListener(v -> startActivity(new Intent(StockActivity.this, StockProductActivity.class)
						.putExtra(StockProductActivity.EXTRA_ID, item.get("id").getAsLong())));
			}

			@Override
			public int getItemCount() {
				return items.isEmpty() ? 0 : items.size() + 1;
			}
		};
		list.setAdapter(adapter);
	}

	@Override
	protected void onStart() {
		super.onStart();
		State.addListener(this);
		onStateChanged();
		PhoneLink.requestStock(this);
	}

	@Override
	protected void onStop() {
		State.removeListener(this);
		super.onStop();
	}

	@Override
	public void onStateChanged() {
		JsonObject stock = State.loadStock(this);
		items.clear();
		if (stock != null) {
			JsonArray array = State.array(stock, "i");
			for (int i = 0; i < array.size(); i++) {
				items.add(array.get(i).getAsJsonObject());
			}
			header = getString(R.string.stock_header, items.size(), stock.has("v") ? stock.get("v").getAsString() : "");
		}
		adapter.notifyDataSetChanged();
		if (items.isEmpty()) {
			if (stock == null) {
				empty.setText(R.string.stock_loading);
			} else if (stock.has("err")) {
				empty.setText(stock.get("err").getAsString());
			} else {
				empty.setText(R.string.stock_empty);
			}
			empty.setVisibility(View.VISIBLE);
		} else {
			empty.setVisibility(View.GONE);
		}
	}
}
