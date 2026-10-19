package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.wear.widget.WearableRecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** Einkaufsliste aus Grocy nach Geschäften: Antippen hakt einen Eintrag ab oder öffnet ihn wieder. */
public class ShoppingActivity extends Activity implements State.Listener {

	private static final int TYPE_HEADER = 0;
	private static final int TYPE_ENTRY  = 1;

	/** Zeile der Liste: entweder eine Überschrift (Geschäft) oder ein Eintrag. */
	private static final class Row {
		String     header;
		boolean    sub;
		JsonObject entry;
	}

	private static Row header(String text, boolean sub) {
		Row h = new Row();
		h.header = text;
		h.sub = sub;
		return h;
	}

	private final List<Row> rows = new ArrayList<>();
	private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
	private TextView empty;
	private WearableRecyclerView list;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_list);
		list = findViewById(R.id.list);
		empty = findViewById(R.id.empty);
		list.setEdgeItemsCenteringEnabled(false);
		list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
		adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@Override
			public int getItemViewType(int position) {
				return rows.get(position).header != null ? TYPE_HEADER : TYPE_ENTRY;
			}

			@NonNull
			@Override
			public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				LayoutInflater inflater = LayoutInflater.from(parent.getContext());
				int layout = viewType == TYPE_HEADER ? R.layout.item_header : R.layout.item_shop;
				return new RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {
				};
			}

			@Override
			public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
				Row row = rows.get(position);
				if (row.header != null) {
					TextView t = (TextView) holder.itemView;
					t.setText(row.header);
					t.setTextSize(row.sub ? 14 : 16);
					t.setTextColor(row.sub ? 0xFFE0E0E0 : 0xFF80CBC4);
					return;
				}
				bindEntry(holder.itemView, row.entry);
			}

			@Override
			public int getItemCount() {
				return rows.size();
			}
		};
		list.setAdapter(adapter);
	}

	private void bindEntry(View view, JsonObject entry) {
		boolean done = entry.get("d").getAsBoolean();
		boolean covered = entry.get("c").getAsBoolean();
		TextView name = view.findViewById(R.id.name);
		TextView sub = view.findViewById(R.id.sub);
		TextView check = view.findViewById(R.id.check);
		name.setText(entry.get("n").getAsString());
		name.setPaintFlags(done ? name.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
				: name.getPaintFlags() & ~android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
		name.setAlpha(done ? 0.7f : 1f);
		sub.setText(covered ? getString(R.string.done_by_scan) : entry.get("a").getAsString());
		check.setText(done ? "☑" : "☐");
		check.setTextColor(done ? 0xFF80CBC4 : 0xFFFFFFFF);
		view.setOnClickListener(v -> toggle(entry));
	}

	private void toggle(JsonObject entry) {
		if (entry.get("c").getAsBoolean()) {
			Toast.makeText(this, R.string.done_by_scan, Toast.LENGTH_SHORT).show();
			return;
		}
		boolean done = !entry.get("d").getAsBoolean();
		long id = entry.get("id").getAsLong();
		// Sofort anzeigen; das Handy bestätigt mit dem neuen Stand
		JsonObject state = State.load(this);
		JsonArray shop = State.array(state, "shop");
		for (int i = 0; i < shop.size(); i++) {
			JsonObject e = shop.get(i).getAsJsonObject();
			if (e.get("id").getAsLong() == id) {
				e.addProperty("d", done);
			}
		}
		state.add("shop", openFirst(shop));
		State.save(this, state);
		PhoneLink.setDone(this, id, done);
	}

	/** Offene Einträge zuerst, erledigte ganz unten; die Reihenfolge innerhalb beider Gruppen bleibt. */
	private static JsonArray openFirst(JsonArray shop) {
		JsonArray open = new JsonArray();
		JsonArray done = new JsonArray();
		for (int i = 0; i < shop.size(); i++) {
			JsonObject e = shop.get(i).getAsJsonObject();
			(e.get("d").getAsBoolean() ? done : open).add(e);
		}
		open.addAll(done);
		return open;
	}

	@Override
	protected void onStart() {
		super.onStart();
		State.addListener(this);
		onStateChanged();
		PhoneLink.requestSync(this);
	}

	@Override
	protected void onStop() {
		State.removeListener(this);
		super.onStop();
	}

	@Override
	public void onStateChanged() {
		JsonObject state = State.load(this);
		rows.clear();
		JsonArray shop = State.array(state, "shop");
		if (state != null && state.has("stotal") && shop.size() > 0) {
			rows.add(header(getString(R.string.shopping_value, state.get("stotal").getAsString()), false));
		}
		String lastShop = null;
		String lastGroup = null;
		boolean inDone = false;
		shop = openFirst(shop);
		for (int i = 0; i < shop.size(); i++) {
			JsonObject e = shop.get(i).getAsJsonObject();
			if (e.get("d").getAsBoolean()) {
				if (!inDone) {
					inDone = true;
					rows.add(header("Erledigt", false));
				}
			} else {
				String s = e.has("s") ? e.get("s").getAsString() : "";
				String g = e.has("g") ? e.get("g").getAsString() : "";
				boolean newShop = !s.equals(lastShop);
				if (newShop && !s.isEmpty()) {
					rows.add(header(s, false));
				}
				if ((newShop || !g.equals(lastGroup)) && !g.isEmpty()) {
					rows.add(header(g, true));
				}
				lastShop = s;
				lastGroup = g;
			}
			Row r = new Row();
			r.entry = e;
			rows.add(r);
		}
		adapter.notifyDataSetChanged();
		if (rows.isEmpty()) {
			empty.setText(state == null ? R.string.no_data : R.string.shopping_empty);
			empty.setVisibility(View.VISIBLE);
		} else {
			empty.setVisibility(View.GONE);
		}
	}
}
