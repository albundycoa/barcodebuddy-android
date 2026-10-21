package de.bulling.barcodebuddyscanner.wear;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Letzter Stand vom Handy (Einkaufsliste und Scans) als JSON. Er bleibt gespeichert, damit die Uhr
 * auch ohne Verbindung etwas anzeigt. Eigene Änderungen werden sofort eingetragen; das Handy schickt
 * danach den bestätigten Stand.
 */
final class State {

	private static final String PREFS = "state";
	private static final String KEY   = "json";
	private static final String KEY_STOCK = "stock_json";

	interface Listener {
		void onStateChanged();
	}

	private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
	private static final Handler main = new Handler(Looper.getMainLooper());

	private State() {
	}

	static void addListener(Listener l) {
		listeners.addIfAbsent(l);
	}

	static void removeListener(Listener l) {
		listeners.remove(l);
	}

	/** Gespeicherter Stand, oder null, wenn das Handy noch nie etwas geschickt hat. */
	static JsonObject load(Context context) {
		String json = prefs(context).getString(KEY, null);
		if (json == null) {
			return null;
		}
		try {
			JsonElement e = JsonParser.parseString(json);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	static void save(Context context, String json) {
		prefs(context).edit().putString(KEY, json).apply();
		notifyChanged();
		// Zifferblätter mit den Komplikationen für Scan- und Einkaufsliste bekommen die neuen Zahlen
		Complications.refresh(context);
	}

	/** Letzter Bestand vom Handy (Bestandsseite), oder null, wenn noch keiner ankam. */
	static JsonObject loadStock(Context context) {
		String json = prefs(context).getString(KEY_STOCK, null);
		if (json == null) {
			return null;
		}
		try {
			JsonElement e = JsonParser.parseString(json);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (RuntimeException ex) {
			return null;
		}
	}

	static void saveStock(Context context, String json) {
		prefs(context).edit().putString(KEY_STOCK, json).apply();
		notifyChanged();
	}

	static void save(Context context, JsonObject state) {
		save(context, state.toString());
	}

	static JsonArray array(JsonObject state, String name) {
		return state != null && state.has(name) && state.get(name).isJsonArray() ? state.getAsJsonArray(name) : new JsonArray();
	}

	private static void notifyChanged() {
		main.post(() -> {
			for (Listener l : listeners) {
				l.onStateChanged();
			}
		});
	}

	private static SharedPreferences prefs(Context context) {
		return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
