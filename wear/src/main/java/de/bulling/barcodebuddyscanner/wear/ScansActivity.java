package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
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

/**
 * Gescannte Artikel des laufenden Einkaufs: Stückzahl ändern oder den Artikel entfernen. Auf Wunsch (Einstellung der Uhr)
 * bleibt das Display an: gedimmt, wenn die Uhr unten ist, und hell beim Anheben (siehe {@link ScreenKeeper}).
 */
public class ScansActivity extends Activity implements State.Listener {

	private static final int TYPE_TOTAL = 0;
	private static final int TYPE_SCAN  = 1;

	private final List<JsonObject> scans = new ArrayList<>();
	private ScreenKeeper screenKeeper;
	private String total = "";
	private int battery = -1;
	private RecyclerView.Adapter<RecyclerView.ViewHolder> adapter;
	private TextView empty;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_list);
		screenKeeper = new ScreenKeeper(this);
		WearableRecyclerView list = findViewById(R.id.list);
		empty = findViewById(R.id.empty);
		list.setEdgeItemsCenteringEnabled(false);
		list.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this));
		adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
			@Override
			public int getItemViewType(int position) {
				return position == 0 ? TYPE_TOTAL : TYPE_SCAN;
			}

			@NonNull
			@Override
			public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
				LayoutInflater inflater = LayoutInflater.from(parent.getContext());
				int layout = viewType == TYPE_TOTAL ? R.layout.item_header : R.layout.item_scan;
				return new RecyclerView.ViewHolder(inflater.inflate(layout, parent, false)) {
				};
			}

			@Override
			public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
				if (position == 0) {
					TextView header = (TextView) holder.itemView;
					header.setText(getString(R.string.total, total));
					if (battery >= 0) {
						header.append(" · " + getString(battery <= 20 ? R.string.scanner_battery_low : R.string.scanner_battery, battery));
					}
					return;
				}
				bindScan(holder.itemView, scans.get(position - 1));
			}

			@Override
			public int getItemCount() {
				return scans.isEmpty() ? 0 : scans.size() + 1;
			}
		};
		list.setAdapter(adapter);
	}

	private void bindScan(View view, JsonObject scan) {
		final long id = scan.get("id").getAsLong();
		final int quantity = scan.get("q").getAsInt();
		((TextView) view.findViewById(R.id.name)).setText(scan.get("n").getAsString());
		String price = scan.has("p") ? " · " + scan.get("p").getAsString() : "";
		((TextView) view.findViewById(R.id.sub)).setText("×" + quantity + price);
		view.findViewById(R.id.minus).setOnClickListener(v -> {
			if (quantity > 1) {
				change(id, quantity - 1);
			} else {
				remove(id);
			}
		});
		view.findViewById(R.id.plus).setOnClickListener(v -> change(id, quantity + 1));
		view.findViewById(R.id.remove).setOnClickListener(v -> remove(id));
	}

	/** Zeigt die neue Stückzahl sofort an; das Handy bestätigt mit dem neuen Stand. */
	private void change(long id, int quantity) {
		JsonObject state = State.load(this);
		JsonArray array = State.array(state, "scans");
		for (int i = 0; i < array.size(); i++) {
			JsonObject s = array.get(i).getAsJsonObject();
			if (s.get("id").getAsLong() == id) {
				s.addProperty("q", quantity);
				s.remove("p");
			}
		}
		State.save(this, state);
		PhoneLink.setQuantity(this, id, quantity);
	}

	private void remove(long id) {
		JsonObject state = State.load(this);
		JsonArray array = State.array(state, "scans");
		for (int i = 0; i < array.size(); i++) {
			if (array.get(i).getAsJsonObject().get("id").getAsLong() == id) {
				array.remove(i);
				break;
			}
		}
		State.save(this, state);
		PhoneLink.delete(this, id);
	}

	@Override
	protected void onStart() {
		super.onStart();
		State.addListener(this);
		onStateChanged();
		PhoneLink.requestSync(this);
		if (Settings.keepScansVisible(this)) {
			screenKeeper.start();
		}
	}

	@Override
	protected void onStop() {
		screenKeeper.stop();
		State.removeListener(this);
		super.onStop();
	}

	/** Jede Berührung macht das Display wieder hell. */
	@Override
	public boolean dispatchTouchEvent(MotionEvent event) {
		screenKeeper.interaction();
		return super.dispatchTouchEvent(event);
	}

	@Override
	public void onStateChanged() {
		// Ein neuer Scan weckt das gedimmte Display kurz auf
		screenKeeper.interaction();
		JsonObject state = State.load(this);
		scans.clear();
		JsonArray array = State.array(state, "scans");
		for (int i = 0; i < array.size(); i++) {
			scans.add(array.get(i).getAsJsonObject());
		}
		total = state != null && state.has("total") ? state.get("total").getAsString() : "";
		battery = state != null && state.has("bat") ? state.get("bat").getAsInt() : -1;
		adapter.notifyDataSetChanged();
		if (scans.isEmpty()) {
			empty.setText(state == null ? R.string.no_data : R.string.scans_empty);
			if (battery >= 0) {
				empty.append("\n" + getString(battery <= 20 ? R.string.scanner_battery_low : R.string.scanner_battery, battery));
			}
			empty.setVisibility(View.VISIBLE);
		} else {
			empty.setVisibility(View.GONE);
		}
	}
}
