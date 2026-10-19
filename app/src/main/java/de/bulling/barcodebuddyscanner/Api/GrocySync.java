package de.bulling.barcodebuddyscanner.Api;

import de.bulling.barcodebuddyscanner.R;
import de.bulling.barcodebuddyscanner.Helper.Texts;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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

	/** Version der gespeicherten Datenfelder; bei einer Änderung wird einmal neu synchronisiert. */
	private static final int CACHE_VERSION = 10;

	public interface Callback {
		/** ok = false: Verbindung oder Daten fehlerhaft, die bisherige Kopie bleibt erhalten */
		void onDone(boolean ok, String message);
	}

	private static final ExecutorService executor = Executors.newSingleThreadExecutor();
	private static final AtomicBoolean   running  = new AtomicBoolean(false);
	private static final AtomicBoolean   pushing  = new AtomicBoolean(false);
	private static final AtomicBoolean   pushAgain = new AtomicBoolean(false);
	private static final Handler         main     = new Handler(Looper.getMainLooper());

	private GrocySync() {
	}

	/** Synchronisiert, wenn Grocy gekoppelt ist und die letzte Synchronisierung schon etwas her ist. */
	public static void startIfStale(Context context) {
		if (!SharedPrefHelper.hasGrocy(context)) {
			return;
		}
		// Nach einem App-Update mit neuen Datenfeldern wird einmal neu geladen, auch im manuellen Modus
		boolean outdated = !SharedPrefHelper.isGrocyCacheCurrent(context, CACHE_VERSION);
		if (!outdated) {
			if (!SharedPrefHelper.isSyncOnOpenEnabled(context)) {
				return;
			}
			long last = SharedPrefHelper.getGrocyLastSync(context);
			if (System.currentTimeMillis() - last < FRESH_MS) {
				return;
			}
			startIfChanged(context);
			return;
		}
		start(context, null);
	}

	/**
	 * Wie {@link #start}, lädt aber nur, wenn sich in Grocy seit der letzten Synchronisierung etwas geändert hat.
	 * Ob etwas geändert wurde, sagt Grocy mit einem einzigen kleinen Aufruf; im Zweifel wird geladen.
	 */
	public static void startIfChanged(Context context) {
		final Context appContext = context.getApplicationContext();
		final GrocyClient client = new SharedPrefHelper(appContext).initGrocyClient();
		if (client == null) {
			return;
		}
		if (!SharedPrefHelper.isGrocyCacheCurrent(appContext, CACHE_VERSION)) {
			start(appContext, null);
			return;
		}
		executor.execute(() -> {
			String now = fetchChangedTime(client);
			if (now != null && now.equals(SharedPrefHelper.getGrocyChangedTime(appContext))) {
				// Nichts Neues in Grocy: die Kopie ist aktuell
				SharedPrefHelper.saveGrocySync(appContext, System.currentTimeMillis(),
						SharedPrefHelper.getGrocySummary(appContext));
				return;
			}
			main.post(() -> start(appContext, null));
		});
	}

	/** Zeitpunkt der letzten Änderung der Grocy-Datenbank, oder null, wenn Grocy ihn nicht liefert. */
	private static String fetchChangedTime(GrocyClient client) {
		try {
			JsonElement response = client.get("system/db-changed-time");
			if (response != null && response.isJsonObject()) {
				return asString(response.getAsJsonObject(), "changed_time");
			}
		} catch (IOException | RuntimeException ignored) {
			// Ohne diese Angabe wird einfach immer vollständig geladen
		}
		return null;
	}

	/**
	 * Synchronisiert und wartet auf das Ergebnis; für den Hintergrund-Worker. Nicht auf dem
	 * Main-Thread aufrufen.
	 */
	public static boolean syncBlocking(Context context) {
		final Context appContext = context.getApplicationContext();
		GrocyClient client = new SharedPrefHelper(appContext).initGrocyClient();
		if (client == null || !running.compareAndSet(false, true)) {
			return false;
		}
		try {
			final String changedTime = fetchChangedTime(client);
			final GrocyData data = load(client);
			final CountDownLatch latch = new CountDownLatch(1);
			final boolean[] stored = {false};
			ShoppingRepository.get(appContext).replaceGrocyCache(data, ok -> {
				stored[0] = ok != null && ok;
				latch.countDown();
			});
			if (!latch.await(60, TimeUnit.SECONDS) || !stored[0]) {
				return false;
			}
			SharedPrefHelper.saveGrocySync(appContext, System.currentTimeMillis(),
					Texts.get(R.string.sync_summary, data.products.size(), data.shops.size()));
			SharedPrefHelper.setGrocyCacheVersion(appContext, CACHE_VERSION);
			SharedPrefHelper.setGrocyChangedTime(appContext, changedTime);
			return true;
		} catch (IOException | RuntimeException e) {
			Log.w(TAG, "Hintergrund-Synchronisierung fehlgeschlagen: " + e.getMessage());
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			running.set(false);
		}
	}

	public static void start(Context context, final Callback callback) {
		final Context appContext = context.getApplicationContext();
		final GrocyClient client = new SharedPrefHelper(appContext).initGrocyClient();
		if (client == null) {
			deliver(callback, false, Texts.get(R.string.sync_not_connected));
			return;
		}
		if (!running.compareAndSet(false, true)) {
			deliver(callback, false, Texts.get(R.string.sync_already_running));
			return;
		}
		executor.execute(() -> {
			try {
				final String changedTime = fetchChangedTime(client);
				final GrocyData data = load(client);
				ShoppingRepository.get(appContext).replaceGrocyCache(data, stored -> {
					String summary = Texts.get(R.string.sync_summary, data.products.size(), data.shops.size());
					SharedPrefHelper.saveGrocySync(appContext, System.currentTimeMillis(), summary);
					SharedPrefHelper.setGrocyCacheVersion(appContext, CACHE_VERSION);
					SharedPrefHelper.setGrocyChangedTime(appContext, changedTime);
					running.set(false);
					if (callback != null) {
						callback.onDone(true, summary);
					}
					// Verbindung steht: liegengebliebene Änderungen nachholen
					pushShoppingDone(appContext);
				});
			} catch (IOException e) {
				Log.w(TAG, "Synchronisierung fehlgeschlagen: " + e.getMessage());
				running.set(false);
				deliver(callback, false, describe(e));
			} catch (RuntimeException e) {
				Log.e(TAG, "Unerwartete Grocy-Daten", e);
				running.set(false);
				deliver(callback, false, Texts.get(R.string.err_grocy_unexpected_response));
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
				return Texts.get(R.string.grocy_err_key);
			}
			if (code == 404) {
				return Texts.get(R.string.sync_err_not_found);
			}
			return Texts.get(R.string.sync_err_http, code);
		}
		return Texts.get(R.string.grocy_err_unreachable);
	}

	/**
	 * Überträgt abgehakte, wieder geöffnete oder in der Menge geänderte Einträge der Einkaufsliste sofort an Grocy. Klappt das nicht
	 * (kein Netz, Server nicht erreichbar), bleibt der Wunsch gespeichert und wird beim nächsten Aufruf
	 * erneut versucht.
	 */
	public static void pushShoppingDone(Context context) {
		final Context appContext = context.getApplicationContext();
		final GrocyClient client = new SharedPrefHelper(appContext).initGrocyClient();
		if (client == null) {
			return;
		}
		final ShoppingRepository repo = ShoppingRepository.get(appContext);
		repo.getShoppingList(data -> {
			final java.util.List<ShoppingRepository.ShoppingEntry> todo = new java.util.ArrayList<>();
			for (ShoppingRepository.ShoppingEntry e : data.entries) {
				if (e.needsUpload()) {
					todo.add(e);
				}
			}
			if (todo.isEmpty()) {
				return;
			}
			if (!pushing.compareAndSet(false, true)) {
				pushAgain.set(true);
				return;
			}
			executor.execute(() -> {
				try {
					for (ShoppingRepository.ShoppingEntry e : todo) {
						boolean done = e.isDone();
						boolean doneChanged = done != e.serverDone;
						JsonObject body = new JsonObject();
						if (doneChanged) {
							body.addProperty("done", done ? 1 : 0);
						}
						if (e.pendingAmount != null) {
							body.addProperty("amount", e.pendingAmount);
						}
						client.put("objects/shopping_list/" + e.id, body.toString());
						if (doneChanged) {
							repo.setServerDone(e.id, done);
						}
						if (e.pendingAmount != null) {
							repo.setServerAmount(e.id, e.pendingAmount);
						}
					}
				} catch (IOException ex) {
					Log.i(TAG, "Abhaken noch nicht übertragen: " + ex.getMessage());
				} finally {
					pushing.set(false);
					if (pushAgain.getAndSet(false)) {
						main.post(() -> pushShoppingDone(appContext));
					}
				}
			});
		});
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
		JsonArray groups     = array(client.get("objects/product_groups"));
		JsonArray locations  = array(client.get("objects/locations"));
		JsonArray stockRows  = array(client.get("objects/stock"));
		JsonArray shoppingRows = array(client.get("objects/shopping_list"));

		GrocyData data = new GrocyData();

		Map<Long, String> unitNames = new HashMap<>();
		for (JsonElement e : units) {
			JsonObject o = e.getAsJsonObject();
			Long id = asLong(o, "id");
			if (id != null) {
				unitNames.put(id, asString(o, "name"));
				ContentValues uv = new ContentValues();
				uv.put("id", id);
				uv.put("name", asString(o, "name") == null ? "" : asString(o, "name"));
				data.units.add(uv);
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

		for (JsonElement e : locations) {
			JsonObject o = e.getAsJsonObject();
			Long id = asLong(o, "id");
			Long active = asLong(o, "active");
			if (id == null || asString(o, "name") == null || (active != null && active == 0)) {
				continue;
			}
			ContentValues lv = new ContentValues();
			lv.put("id", id);
			lv.put("name", asString(o, "name"));
			data.locations.add(lv);
		}

		Map<Long, String> groupNames = new HashMap<>();
		for (JsonElement e : groups) {
			JsonObject o = e.getAsJsonObject();
			Long id = asLong(o, "id");
			if (id != null) {
				groupNames.put(id, asString(o, "name"));
				if (asString(o, "name") != null) {
					ContentValues gv = new ContentValues();
					gv.put("id", id);
					gv.put("name", asString(o, "name"));
					data.groups.add(gv);
				}
			}
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
			Long days = asLong(o, "default_best_before_days");
			if (days != null) {
				v.put("best_before_days", days);
			}
			// Schnellverbrauch-Menge nur übernehmen, wenn sie in der Lagereinheit steht
			Long consumeUnit = asLong(o, "qu_id_consume");
			Double quick = asDouble(o, "quick_consume_amount");
			if (quick != null && quick > 0 && (consumeUnit == null || consumeUnit.equals(stock))) {
				v.put("consume_amount", quick);
			}
			Long tare = asLong(o, "enable_tare_weight_handling");
			v.put("tare", tare != null && tare != 0 ? 1 : 0);
			Long group = asLong(o, "product_group_id");
			if (group != null && groupNames.get(group) != null) {
				v.put("group_name", groupNames.get(group));
			}
			if (group != null) {
				v.put("group_id", group);
			}
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
			v.put("exact", amount != null && amount > 0 ? 1 : 0);
			if (unitNames.get(unit) != null) {
				v.put("scan_unit", unitNames.get(unit));
			}
			Long shop = asLong(o, "shopping_location_id");
			if (shop != null) {
				v.put("shop_id", shop);
			}
			data.barcodes.add(v);
		}

		// Einkaufsliste: Menge zusätzlich in Lagereinheiten, um sie mit gescannten Mengen zu vergleichen
		for (JsonElement e : shoppingRows) {
			JsonObject o = e.getAsJsonObject();
			Long id = asLong(o, "id");
			if (id == null) {
				continue;
			}
			Long product = asLong(o, "product_id");
			Double amount = asDouble(o, "amount");
			Long quId = asLong(o, "qu_id");
			double listAmount = amount != null ? amount : 1;
			double stockAmount = listAmount;
			long[] pu = product == null ? null : productUnits.get(product);
			if (pu != null && quId != null) {
				stockAmount = listAmount * factor(factors, product, quId, pu[0]);
			}
			ContentValues v = new ContentValues();
			v.put("id", id);
			Long listId = asLong(o, "shopping_list_id");
			if (listId != null) {
				v.put("list_id", listId);
			}
			if (product != null) {
				v.put("product_id", product);
			}
			String note = asString(o, "note");
			if (note != null) {
				v.put("note", note);
			}
			v.put("amount", listAmount);
			if (quId != null) {
				v.put("qu_id", quId);
			} else if (pu != null) {
				v.put("qu_id", pu[0]);
			}
			v.put("stock_amount", stockAmount);
			Long done = asLong(o, "done");
			v.put("done", done != null && done != 0 ? 1 : 0);
			data.shopping.add(v);
		}

		// Bestand: Grocy liefert einzelne Bestandseinträge (Chargen), die hier je Produkt addiert werden
		Map<Long, Double> stockSums = new HashMap<>();
		for (JsonElement e : stockRows) {
			JsonObject o = e.getAsJsonObject();
			Long product = asLong(o, "product_id");
			Double amount = asDouble(o, "amount");
			if (product != null && amount != null) {
				Double sum = stockSums.get(product);
				stockSums.put(product, (sum == null ? 0 : sum) + amount);
			}
		}
		for (Map.Entry<Long, Double> entry : stockSums.entrySet()) {
			ContentValues v = new ContentValues();
			v.put("product_id", entry.getKey());
			v.put("amount", entry.getValue());
			data.stock.add(v);
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
			throw new IOException(Texts.get(R.string.err_unexpected_response));
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
