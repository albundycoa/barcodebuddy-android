package de.bulling.barcodebuddyscanner.wear;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.wear.ambient.AmbientLifecycleObserver;
import androidx.wear.ambient.AmbientLifecycleObserverKt;
import androidx.wear.widget.WearableRecyclerView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Gescannte Artikel des laufenden Einkaufs: Stückzahl ändern oder den Artikel entfernen. Auf Wunsch (Einstellung der Uhr)
 * bleibt die Liste im Ambient-Modus sichtbar: Das Display wird gedimmt statt auszugehen und beim Anheben der Uhr wieder hell.
 */
public class ScansActivity extends ComponentActivity implements State.Listener {

	private static final int TYPE_TOTAL = 0;
	private static final int TYPE_SCAN  = 1;

	private final List<JsonObject> scans = new ArrayList<>();
	private String total = "";
	private int battery = -1;
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
		keepVisibleInAmbientMode();
	}

	/** Hält die Scanliste im Ambient-Modus auf dem Display (gedimmt), wenn die Einstellung an ist. */
	private void keepVisibleInAmbientMode() {
		if (!Settings.keepScansVisible(this)) {
			return;
		}
		final View content = findViewById(android.R.id.content);
		AmbientLifecycleObserver ambient = AmbientLifecycleObserverKt.AmbientLifecycleObserver(this,
				new AmbientLifecycleObserver.AmbientLifecycleCallback() {
					@Override
					public void onEnterAmbient(AmbientLifecycleObserver.AmbientDetails details) {
						// Weniger Helligkeit der Oberfläche schont das Display, das System dimmt den Rest
						content.setAlpha(0.55f);
					}

					@Override
					public void onUpdateAmbient() {
					}

					@Override
					public void onExitAmbient() {
						content.setAlpha(1f);
					}
				});
		getLifecycle().addObserver(ambient);
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
	}

	@Override
	protected void onStop() {
		State.removeListener(this);
		super.onStop();
	}

	@Override
	public void onStateChanged() {
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
