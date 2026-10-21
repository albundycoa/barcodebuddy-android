package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Ein Produkt im Bestand: Schnellverbrauch, alles verbrauchen (erst nach einer zweiten Bestätigung) und öffnen. Gebucht
 * wird über das Handy; dessen Meldung samt "Rückgängig" erscheint hier, solange sie frisch ist.
 */
public class StockProductActivity extends Activity implements State.Listener {

	static final String EXTRA_ID = "id";

	/** So lange gilt eine Meldung des Handys als frisch. */
	private static final long MESSAGE_MS = 60_000;
	/** Länger als das wartet die Uhr nicht auf die Antwort des Handys. */
	private static final long BUSY_MS = 10_000;
	private static final long CONFIRM_MS = 4_000;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private long productId;
	private JsonObject item;
	private long lastTs;
	private boolean busy;
	private boolean confirmAll;

	private TextView name;
	private TextView info;
	private TextView message;
	private Button consume;
	private Button all;
	private Button open;
	private Button undo;

	private final Runnable endBusy = () -> {
		busy = false;
		render();
	};
	private final Runnable endConfirm = () -> {
		confirmAll = false;
		render();
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_stock_product);
		productId = getIntent().getLongExtra(EXTRA_ID, -1);
		name = findViewById(R.id.product_name);
		info = findViewById(R.id.product_info);
		message = findViewById(R.id.product_message);
		consume = findViewById(R.id.button_consume);
		all = findViewById(R.id.button_all);
		open = findViewById(R.id.button_open);
		undo = findViewById(R.id.button_undo);

		consume.setOnClickListener(v -> book("consume", item.get("cq").getAsDouble()));
		open.setOnClickListener(v -> book("open", item.get("oq").getAsDouble()));
		all.setOnClickListener(v -> {
			if (!confirmAll) {
				// Erst die zweite Berührung bucht: ein versehentlicher Tipp am Handgelenk soll nichts verbrauchen
				confirmAll = true;
				handler.removeCallbacks(endConfirm);
				handler.postDelayed(endConfirm, CONFIRM_MS);
				render();
				return;
			}
			confirmAll = false;
			book("consume", item.get("am").getAsDouble());
		});
		undo.setOnClickListener(v -> {
			JsonObject stock = State.loadStock(this);
			if (stock != null && stock.has("tx")) {
				startBusy();
				PhoneLink.undo(this, stock.get("tx").getAsString());
			}
		});
	}

	@Override
	protected void onStart() {
		super.onStart();
		State.addListener(this);
		onStateChanged();
	}

	@Override
	protected void onStop() {
		State.removeListener(this);
		handler.removeCallbacksAndMessages(null);
		super.onStop();
	}

	private void book(String action, double amount) {
		if (busy || item == null || !(amount > 0)) {
			return;
		}
		String unit = item.get("t").getAsString();
		String text = (format(amount) + " " + unit).trim();
		startBusy();
		PhoneLink.book(this, action, productId, amount, item.get("n").getAsString(), text);
	}

	private void startBusy() {
		busy = true;
		confirmAll = false;
		handler.removeCallbacks(endBusy);
		handler.postDelayed(endBusy, BUSY_MS);
		render();
	}

	@Override
	public void onStateChanged() {
		JsonObject stock = State.loadStock(this);
		item = null;
		if (stock != null) {
			JsonArray array = State.array(stock, "i");
			for (int i = 0; i < array.size(); i++) {
				JsonObject o = array.get(i).getAsJsonObject();
				if (o.get("id").getAsLong() == productId) {
					item = o;
				}
			}
			long ts = stock.has("ts") ? stock.get("ts").getAsLong() : 0;
			if (ts > lastTs) {
				// Neuer Stand vom Handy: die Buchung ist durch
				lastTs = ts;
				busy = false;
				handler.removeCallbacks(endBusy);
			}
		}
		render();
	}

	private void render() {
		JsonObject stock = State.loadStock(this);
		if (item == null) {
			name.setText("");
			info.setText(R.string.stock_gone);
			consume.setVisibility(android.view.View.GONE);
			all.setVisibility(android.view.View.GONE);
			open.setVisibility(android.view.View.GONE);
		} else {
			name.setText(item.get("n").getAsString());
			info.setText(item.get("a").getAsString() + " · " + item.get("w").getAsString());
			consume.setVisibility(android.view.View.VISIBLE);
			all.setVisibility(android.view.View.VISIBLE);
			open.setVisibility(android.view.View.VISIBLE);
			consume.setText(getString(R.string.stock_consume, format(item.get("cq").getAsDouble())));
			open.setText(getString(R.string.stock_open, format(item.get("oq").getAsDouble())));
			all.setText(confirmAll ? R.string.stock_all_confirm : R.string.stock_all);
			consume.setEnabled(!busy && item.get("fc").getAsBoolean());
			all.setEnabled(!busy && item.get("fa").getAsBoolean());
			open.setEnabled(!busy && item.get("fo").getAsBoolean());
		}
		boolean fresh = stock != null && stock.has("m") && stock.has("mt")
				&& System.currentTimeMillis() - stock.get("mt").getAsLong() < MESSAGE_MS;
		if (busy) {
			message.setText(R.string.stock_busy);
			undo.setVisibility(android.view.View.GONE);
		} else if (fresh) {
			message.setText(stock.get("m").getAsString());
			undo.setVisibility(stock.has("tx") ? android.view.View.VISIBLE : android.view.View.GONE);
		} else {
			message.setText("");
			undo.setVisibility(android.view.View.GONE);
		}
	}

	/** 1.0 -> "1", 0.5 -> "0,5" */
	static String format(double value) {
		if (value == Math.rint(value)) {
			return String.valueOf((long) value);
		}
		String text = String.format(java.util.Locale.GERMANY, "%.3f", value);
		text = text.replaceAll("0+$", "");
		return text.endsWith(",") ? text.substring(0, text.length() - 1) : text;
	}
}
