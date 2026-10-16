package de.bulling.barcodebuddyscanner.Api;

import android.content.ContentValues;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.bulling.barcodebuddyscanner.Data.GrocyData;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Lädt Geschäfte, Produkte, Barcodes, Einheiten und Letztpreise aus Grocy in die
 * lokale Datenbank, damit die Einkaufsliste auch ohne Verbindung zum Server
 * Namen, Geschäfte und Preise anzeigen kann.
 */
public final class GrocySync {

	private static final String TAG = "GrocySync";

	/** Wie lange eine Synchronisierung als aktuell gilt, wenn nicht ausdrücklich neu geladen wird */
	private static final long FRESH_MS = 10 * 60 * 1000L;

	public interface Callback {
		/** ok = false: Verbindung oder Daten fehlerhaft, die bisherige Kopie bleibt erhalten */
		void onDone(boolean ok, String message);
	}

	private static final ExecutorService executor = Executors.newSingleThreadExecutor();
	private static final AtomicBoolean   running  = new AtomicBoolean(false);
	private static final Handler         main     = new Handler(Looper.getMainLooper());

	private GrocySync() {
	}

	/** Synchronisiert, wenn Grocy gekoppelt ist und die letzte Synchronisierung schon etwas her ist. */
	public static void startIfStale(Context context) {
		if (!SharedPrefHelper.hasGrocy(context)) {
			return;
		}
		long last = SharedPrefHelper.getGrocyLastSync(context);
		if (System.currentTimeMillis() - last < FRESH_MS) {
			return;
		}
		start(context, null);
	}

	public static void start(Context context, final Callback callback) {
		final Context appContext = context.getApplicationContext();
		final GrocyClient client = new SharedPrefHelper(appContext).initGrocyClient();
		if (client == null) {
			deliver(callback, false, "Grocy ist nicht verbunden.");
			return;
		}
		if (!running.compareAndSet(false, true)) {
			deliver(callback, false, "Es läuft bereits eine Synchronisierung.");
			return;
		}
		executor.execute(() -> {
			try {
				final GrocyData data = load(client);
				ShoppingRepository.get(appContext).replaceGrocyCache(data, stored -> {
					String summary = data.products.size() + " Produkte, " + data.shops.size() + " Geschäfte";
					SharedPrefHelper.saveGrocySync(appContext, System.currentTimeMillis(), summary);
					running.set(false);
					if (callback != null) {
						callback.onDone(true, summary);
					}
				});
			} catch (IOException e) {
				Log.w(TAG, "Synchronisierung fehlgeschlagen: " + e.getMessage());
				running.set(false);
				deliver(callback, false, describe(e));
			} catch (RuntimeException e) {
				Log.e(TAG, "Unerwartete Grocy-Daten", e);
				running.set(false);
				deliver(callback, false, "Unerwartete Antwort von Grocy.");
			}
		});
	}

	private static void deliver(final Callback callback, final boolean ok, final String message) {
		if (callback != null) {
			main.post(() -> callback.onDone(ok, message));
		}
	}

	private static String describe(IOException e) {
		if (e instanceof GrocyClient.HttpException) {
			int code = ((GrocyClient.HttpException) e).statusCode;
			if (code == 401 || code == 403) {
				return "Ungültiger Grocy-API-Schlüssel.";
			}
			if (code == 404) {
				return "Grocy nicht gefunden. Bitte prüfe die Adresse.";
			}
			return "Grocy antwortet mit Fehler " + code + ".";
		}
		return "Grocy nicht erreichbar.";
	}

	/** Prüft Adresse und Schlüssel, ohne etwas zu speichern. */
	public static void verify(final GrocyClient client, final Callback callback) {
		executor.execute(() -> {
			try {
				client.get("system/info");
				deliver(callback, true, "");
			} catch (IOException e) {
				deliver(callback, false, describe(e));
			}
		});
	}

	// ---------------------------------------------------------------- Aufbereiten

	private static GrocyData load(GrocyClient client) throws IOException {
		JsonArray shops      = array(client.get("objects/shopping_locations"));
		JsonArray units      = array(client.get("objects/quantity_units"));
		JsonArray products   = array(client.get("objects/products"));
		JsonArray barcodes   = array(client.get("objects/product_barcodes"));
		JsonArray conv       = array(client.get("objects/quantity_unit_conversions_resolved"));
		JsonArray lastPrices = array(client.get("objects/products_last_purchased"));

		GrocyData data = new GrocyData();

		Map<Long, String> unitNames = new HashMap<>();
		for (JsonElement e : units) {
			JsonObject o = e.getAsJsonObject();
			Long id = asLong(o, "id");
			if (id != null) {
				unitNames.put(id, asString(o, "name"));
			}
		}

		for (JsonElement e : shops) {
			JsonObject o = e.getAsJsonObject();
			Long id = asLong(o, "id");
			Long active = asLong(o, "active");
			if (id == null || (active != null && active == 0)) {
				continue;
			}
			ContentValues v = new ContentValues();
			v.put("id", id);
			v.put("name", asString(o, "name"));
			data.shops.add(v);
		}

		// Umrechnungsfaktoren je Produkt: Menge in "von"-Einheit mal Faktor = Menge in "nach"-Einheit
		Map<String, Double> factors = new HashMap<>();
		for (JsonElement e : conv) {
			JsonObject o = e.getAsJsonObject();
			Long product = asLong(o, "product_id");
			Long from = asLong(o, "from_qu_id");
			Long to = asLong(o, "to_qu_id");
			Double factor = asDouble(o, "factor");
			if (product != null && from != null && to != null && factor != null) {
				factors.put(product + ":" + from + ":" + to, factor);
			}
		}

		Map<Long, long[]> productUnits = new HashMap<>(); // id -> {Lagereinheit, Einkaufseinheit}
		for (JsonElement e : products) {
			JsonObject o = e.getAsJsonObject();
			Long id = asLong(o, "id");
			String name = asString(o, "name");
			Long stock = asLong(o, "qu_id_stock");
			if (id == null || name == null || stock == null) {
				continue;
			}
			Long price = asLong(o, "qu_id_price");
			Long purchase = asLong(o, "qu_id_purchase");
			long priceUnit = price != null ? price : stock;
			productUnits.put(id, new long[]{stock, purchase != null ? purchase : stock});

			ContentValues v = new ContentValues();
			v.put("id", id);
			v.put("name", name);
			Long shop = asLong(o, "shopping_location_id");
			if (shop != null) {
				v.put("shop_id", shop);
			}
			v.put("stock_to_price", factor(factors, id, stock, priceUnit));
			v.put("stock_unit", unitNames.get(stock));
			v.put("price_unit", unitNames.get(priceUnit));
			data.products.add(v);
		}

		for (JsonElement e : barcodes) {
			JsonObject o = e.getAsJsonObject();
			Long product = asLong(o, "product_id");
			String code = asString(o, "barcode");
			long[] units2 = product == null ? null : productUnits.get(product);
			if (code == null || units2 == null) {
				continue;
			}
			Double amount = asDouble(o, "amount");
			Long quId = asLong(o, "qu_id");
			// Ohne eigene Einheit am Barcode gilt die Einkaufseinheit des Produkts
			long unit = quId != null ? quId : units2[1];
			double perScan = (amount != null && amount > 0 ? amount : 1) * factor(factors, product, unit, units2[0]);

			ContentValues v = new ContentValues();
			v.put("barcode", code);
			v.put("product_id", product);
			v.put("stock_per_scan", perScan);
			Long shop = asLong(o, "shopping_location_id");
			if (shop != null) {
				v.put("shop_id", shop);
			}
			data.barcodes.add(v);
		}

		for (JsonElement e : lastPrices) {
			JsonObject o = e.getAsJsonObject();
			Long product = asLong(o, "product_id");
			Double price = asDouble(o, "price");
			if (product != null && price != null) {
				ContentValues v = new ContentValues();
				v.put("product_id", product);
				v.put("price", price);
				data.lastPrices.add(v);
			}
		}
		return data;
	}

	private static double factor(Map<String, Double> factors, long product, long from, long to) {
		if (from == to) {
			return 1;
		}
		Double f = factors.get(product + ":" + from + ":" + to);
		return f != null ? f : 1;
	}

	private static JsonArray array(JsonElement element) throws IOException {
		if (element == null || !element.isJsonArray()) {
			throw new IOException("Unerwartete Antwort");
		}
		return element.getAsJsonArray();
	}

	/** Grocy liefert leere Felder teils als "" statt null. */
	private static String asString(JsonObject o, String key) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		String s = e.getAsString();
		return s.isEmpty() ? null : s;
	}

	private static Long asLong(JsonObject o, String key) {
		Double d = asDouble(o, key);
		return d == null ? null : (long) d.doubleValue();
	}

	private static Double asDouble(JsonObject o, String key) {
		String s = asString(o, key);
		if (s == null) {
			return null;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
