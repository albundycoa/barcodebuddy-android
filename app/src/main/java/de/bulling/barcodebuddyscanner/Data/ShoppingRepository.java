package de.bulling.barcodebuddyscanner.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import de.bulling.barcodebuddyscanner.Api.OpenFoodFacts;
import de.bulling.barcodebuddyscanner.Helper.Deposit;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;
import de.bulling.barcodebuddyscanner.Wear.WearSync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lokaler Speicher für die Einkaufsliste, den Verlauf und die Kopie der
 * Grocy-Stammdaten. Alle Zugriffe laufen nacheinander auf einem eigenen Thread,
 * Ergebnisse kommen auf dem Main-Thread an. Nach jeder Änderung werden die
 * Listener benachrichtigt.
 */
public final class ShoppingRepository {

	private static final String TAG = "ShoppingRepository";

	public interface Callback<T> {
		void onResult(T result);
	}

	public interface Listener {
		void onDataChanged();
	}

	public static final class ArchiveResult {
		public final int unitsArchived;
		public final int remainingItems;

		ArchiveResult(int unitsArchived, int remainingItems) {
			this.unitsArchived  = unitsArchived;
			this.remainingItems = remainingItems;
		}
	}

	/** Die offene Liste samt allem, was zum Gruppieren nach Geschäften nötig ist. */
	public static final class OpenList {
		public final List<ShoppingItem> items;
		/** Es gibt eine lokale Kopie der Grocy-Daten (sonst ist "unbekannt" nicht aussagekräftig) */
		public final boolean            cacheAvailable;
		/** Position der Geschäfte in der vom Nutzer gewählten Reihenfolge */
		public final Map<Long, Integer> shopPosition;

		OpenList(List<ShoppingItem> items, boolean cacheAvailable, Map<Long, Integer> shopPosition) {
			this.items          = items;
			this.cacheAvailable = cacheAvailable;
			this.shopPosition   = shopPosition;
		}
	}

	public static final class Shop {
		public final long   id;
		public final String name;

		Shop(long id, String name) {
			this.id   = id;
			this.name = name;
		}
	}

	private static final String[] ITEM_COLUMNS = {
			"id", "trip_id", "barcode", "name", "quantity", "scanned_at", "sent_count", "message", "error",
			"price", "scan_price", "shop_name", "deposit"
	};

	/** Offene Artikel mit den passenden Grocy-Daten (Name, Geschäft, Einheiten, Letztpreis). */
	private static final String OPEN_ITEMS_SQL =
			"SELECT i.id, i.trip_id, i.barcode, i.name, i.quantity, i.scanned_at, i.sent_count, i.message, "
					+ "i.error, i.price, i.scan_price, i.shop_name, i.deposit, p.group_id AS g_group_id, "
					+ "b.product_id AS g_product_id, b.stock_per_scan AS g_stock_per_scan, "
					+ "p.name AS g_name, p.stock_to_price AS g_stock_to_price, p.stock_unit AS g_stock_unit, "
					+ "p.price_unit AS g_price_unit, p.best_before_days AS g_bb_days, p.tare AS g_tare, "
					+ "b.exact AS g_exact, st.amount AS g_stock, "
					+ "COALESCE(b.shop_id, p.shop_id) AS g_shop_id, s.name AS g_shop_name, "
					+ "lp.price AS g_last_price, o.name AS off_name "
					+ "FROM items i "
					// Wie Barcode Buddy: ein führend mit Nullen aufgefüllter Code (GTIN-14) trifft auch den EAN-13
					+ "LEFT JOIN g_barcodes b ON b.barcode = i.barcode OR b.barcode = ltrim(i.barcode, '0') "
					+ "LEFT JOIN g_products p ON p.id = b.product_id "
					+ "LEFT JOIN g_shops s ON s.id = COALESCE(b.shop_id, p.shop_id) "
					+ "LEFT JOIN g_lastprice lp ON lp.product_id = p.id "
					+ "LEFT JOIN g_stock st ON st.product_id = p.id "
					+ "LEFT JOIN off_cache o ON o.barcode = i.barcode "
					+ "WHERE i.trip_id = 0 ORDER BY i.scanned_at DESC";

	private static ShoppingRepository instance;

	public static synchronized ShoppingRepository get(Context context) {
		if (instance == null) {
			instance = new ShoppingRepository(context.getApplicationContext());
		}
		return instance;
	}

	private final Context                       appContext;
	private final ShoppingDb                     dbHelper;
	private final ExecutorService                io        = Executors.newSingleThreadExecutor();
	/** Netzwerkabfragen (OpenFoodFacts) getrennt von der Datenbank, damit ein langsamer Server nichts aufhält */
	private final ExecutorService                net       = Executors.newFixedThreadPool(2);
	private final Handler                        main      = new Handler(Looper.getMainLooper());
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

	private ShoppingRepository(Context appContext) {
		this.appContext = appContext;
		dbHelper = new ShoppingDb(appContext);
	}

	public void addListener(Listener listener) {
		listeners.addIfAbsent(listener);
	}

	public void removeListener(Listener listener) {
		listeners.remove(listener);
	}

	private void notifyChanged() {
		for (Listener l : listeners) {
			l.onDataChanged();
		}
		// Die Uhr bekommt jede Änderung mit, auch wenn die App nicht geöffnet ist
		WearSync.push(appContext);
	}

	/** Lesezugriff: Ergebnis zurück auf den Main-Thread, keine Benachrichtigung. */
	private <T> void read(final Callable<T> work, final Callback<T> callback) {
		io.execute(() -> {
			try {
				final T result = work.call();
				if (callback != null) {
					main.post(() -> callback.onResult(result));
				}
			} catch (Exception e) {
				Log.e(TAG, "Datenbankfehler", e);
			}
		});
	}

	/** Schreibzugriff: danach werden alle Listener benachrichtigt. */
	private <T> void write(final Callable<T> work, final Callback<T> callback) {
		io.execute(() -> {
			try {
				final T result = work.call();
				main.post(() -> {
					if (callback != null) {
						callback.onResult(result);
					}
					notifyChanged();
				});
			} catch (Exception e) {
				Log.e(TAG, "Datenbankfehler", e);
			}
		});
	}

	// ---------------------------------------------------------------- Einkaufsliste

	/** Zählt einen Scan: erhöht die Stückzahl des Artikels oder legt ihn neu an. */
	public void addScan(final String barcode, Callback<ShoppingItem> callback) {
		write(() -> {
			SQLiteDatabase db  = dbHelper.getWritableDatabase();
			long           now = System.currentTimeMillis();
			db.beginTransaction();
			try {
				ShoppingItem item = null;
				try (Cursor c = db.query(ShoppingDb.T_ITEMS, ITEM_COLUMNS,
						"trip_id = 0 AND barcode = ?", new String[]{barcode}, null, null, null, "1")) {
					if (c.moveToFirst()) {
						item = itemFromCursor(c);
					}
				}
				if (item != null) {
					item.quantity++;
					item.scannedAt = now;
					ContentValues v = new ContentValues();
					v.put("quantity", item.quantity);
					v.put("scanned_at", now);
					db.update(ShoppingDb.T_ITEMS, v, "id = ?", new String[]{String.valueOf(item.id)});
				} else {
					item = new ShoppingItem();
					item.barcode   = barcode;
					item.quantity  = 1;
					item.scannedAt = now;
					item.id        = db.insert(ShoppingDb.T_ITEMS, null, itemToValues(item, false));
				}
				db.setTransactionSuccessful();
				return item;
			} finally {
				db.endTransaction();
			}
		}, item -> {
			if (item.quantity == 1) {
				// Neuer Eintrag: bei einem unbekannten Barcode gleich einen Namen suchen
				prefetchSuggestion(item.barcode);
			}
			if (callback != null) {
				callback.onResult(item);
			}
		});
	}

	// ---------------------------------------------------------------- OpenFoodFacts

	private static final long OFF_RETRY_MS = 7L * 24 * 60 * 60 * 1000;

	/** Darf die App unbekannte Barcodes bei OpenFoodFacts nachschlagen (Einstellung, Standard: ja)? */
	private boolean offEnabled() {
		return new SharedPrefHelper(appContext).isOffLookupEnabled();
	}

	/** Holt im Hintergrund einen Namensvorschlag; die Liste lädt neu, sobald er da ist. */
	private final java.util.Set<String> prefetching = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

	private void prefetchSuggestion(final String barcode) {
		if (!prefetching.add(barcode)) {
			return;
		}
		suggestionFor(barcode, name -> {
			prefetching.remove(barcode);
			if (name != null) {
				notifyChanged();
			}
		});
	}

	/**
	 * Namensvorschlag für einen Barcode, den Grocy nicht kennt: aus dem Zwischenspeicher oder von OpenFoodFacts.
	 * Das Ergebnis ist null bei bekanntem Barcode, ausgeschaltetem Nachschlagen, fehlender Grocy-Kopie,
	 * keinem Treffer oder keiner Verbindung.
	 */
	public void suggestionFor(final String barcode, final Callback<String> callback) {
		if (!offEnabled() || !OpenFoodFacts.isProductBarcode(barcode)) {
			main.post(() -> callback.onResult(null));
			return;
		}
		net.execute(() -> {
			String name = null;
			try {
				SQLiteDatabase db = dbHelper.getWritableDatabase();
				// Ohne Kopie der Grocy-Daten lässt sich "unbekannt" nicht beurteilen; was Grocy kennt, braucht keinen Vorschlag
				if (DatabaseUtils.queryNumEntries(db, ShoppingDb.T_PRODUCTS) > 0 && !isKnownBarcode(db, barcode)) {
					name = cachedOrFetched(db, barcode);
				}
			} catch (Exception e) {
				Log.w(TAG, "OpenFoodFacts-Abfrage fehlgeschlagen: " + e.getMessage());
			}
			final String result = name;
			main.post(() -> callback.onResult(result));
		});
	}

	private static boolean isKnownBarcode(SQLiteDatabase db, String barcode) {
		return DatabaseUtils.longForQuery(db, "SELECT COUNT(*) FROM g_barcodes WHERE barcode = ? OR barcode = ltrim(?, '0')",
				new String[]{barcode, barcode}) > 0;
	}

	private static String cachedOrFetched(SQLiteDatabase db, String barcode) throws java.io.IOException {
		try (Cursor c = db.query(ShoppingDb.T_OFF, new String[]{"name", "checked_at"}, "barcode = ?",
				new String[]{barcode}, null, null, null)) {
			if (c.moveToFirst()) {
				String cachedName = c.getString(0);
				// Treffer bleiben, ein "nicht gefunden" wird nach einer Woche noch einmal geprüft (die Datenbank wächst)
				if (cachedName != null || System.currentTimeMillis() - c.getLong(1) < OFF_RETRY_MS) {
					return cachedName;
				}
			}
		}
		OpenFoodFacts.Result result = OpenFoodFacts.lookup(barcode);
		ContentValues v = new ContentValues();
		v.put("barcode", barcode);
		v.put("name", result.name);
		v.put("checked_at", System.currentTimeMillis());
		db.insertWithOnConflict(ShoppingDb.T_OFF, null, v, SQLiteDatabase.CONFLICT_REPLACE);
		return result.name;
	}

	public void getOpenList(Callback<OpenList> callback) {
		read(() -> {
			SQLiteDatabase db = dbHelper.getReadableDatabase();
			List<ShoppingItem> items = new ArrayList<>();
			try (Cursor c = db.rawQuery(OPEN_ITEMS_SQL, null)) {
				while (c.moveToNext()) {
					items.add(openItemFromCursor(c));
				}
			}
			Deposit.apply(items, new SharedPrefHelper(appContext).getDepositRules());
			boolean cache = DatabaseUtils.queryNumEntries(db, ShoppingDb.T_PRODUCTS) > 0;
			return new OpenList(items, cache, readShopPositions(db));
		}, list -> {
			// Auch ältere Einträge bekommen einen Vorschlag (die Abfrage selbst merkt sich Fehlanzeigen)
			if (list.cacheAvailable) {
				for (ShoppingItem item : list.items) {
					if (!item.known && item.suggestion == null && item.displayName() == null) {
						prefetchSuggestion(item.barcode);
					}
				}
			}
			if (callback != null) {
				callback.onResult(list);
			}
		});
	}

	public void setQuantity(final long itemId, final int quantity) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.put("quantity", Math.max(1, quantity));
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_ITEMS, v, "id = ?",
					new String[]{String.valueOf(itemId)});
		}, null);
	}

	/** Setzt den Preis je Preiseinheit; null stellt den letzten Einkaufspreis aus Grocy wieder her. */
	public void setPrice(final long itemId, final Double price) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.put("price", price);
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_ITEMS, v, "id = ?",
					new String[]{String.valueOf(itemId)});
		}, null);
	}

	/** Übernimmt das neue Geschäft eines Barcodes in die lokale Kopie, damit die Liste sofort stimmt. */
	public void setBarcodeShop(final String barcode, final long shopId) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.put("shop_id", shopId);
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_BARCODES, v, "barcode = ? OR barcode = ltrim(?, '0')",
					new String[]{barcode, barcode});
		}, null);
	}

	public void deleteItem(final long itemId) {
		write(() -> dbHelper.getWritableDatabase().delete(ShoppingDb.T_ITEMS, "id = ?",
				new String[]{String.valueOf(itemId)}), null);
	}

	/** Setzt einen zuvor entfernten Artikel mit allen Werten wieder ein. */
	public void restoreItem(final ShoppingItem item) {
		write(() -> dbHelper.getWritableDatabase().insertWithOnConflict(ShoppingDb.T_ITEMS, null,
				itemToValues(item, true), SQLiteDatabase.CONFLICT_REPLACE), null);
	}

	public void clearOpenList() {
		write(() -> dbHelper.getWritableDatabase().delete(ShoppingDb.T_ITEMS, "trip_id = 0", null), null);
	}

	// ---------------------------------------------------------------- Senden

	/**
	 * Merkt sich den Sendefortschritt eines Artikels samt Name und Preis, die
	 * beim Senden galten. Löst bewusst keine Benachrichtigung aus, damit die
	 * Liste beim Senden nicht ständig neu lädt.
	 */
	public void updateProgress(final long itemId, final int sentCount, final String message, final String error,
							   final String name, final Double price, final Double scanPrice, final String shopName) {
		read(() -> {
			ContentValues v = new ContentValues();
			v.put("sent_count", sentCount);
			v.put("message", message);
			v.put("error", error);
			if (name != null) {
				v.put("name", name);
			}
			if (price != null) {
				v.put("price", price);
			}
			if (scanPrice != null) {
				v.put("scan_price", scanPrice);
			}
			if (shopName != null) {
				v.put("shop_name", shopName);
			}
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_ITEMS, v, "id = ?",
					new String[]{String.valueOf(itemId)});
		}, null);
	}

	/**
	 * Schließt einen Sendevorgang ab: Alles, was gesendet wurde, wandert als
	 * Verlaufseintrag in den Verlauf. Nicht (vollständig) gesendete Stücke bleiben
	 * in der offenen Liste.
	 */
	public void finishSend(Callback<ArchiveResult> callback) {
		write(() -> {
			SQLiteDatabase db  = dbHelper.getWritableDatabase();
			long           now = System.currentTimeMillis();
			db.beginTransaction();
			try {
				List<ShoppingItem> open = readItems(db, 0);
				final Map<Long, Double> deposits = openDeposits(db);
				long tripId = -1;
				double depositReturnTaken = 0;
				int  items  = 0;
				int  units  = 0;
				for (ShoppingItem it : open) {
					int sent = Math.min(it.sentCount, it.quantity);
					if (sent <= 0) {
						continue;
					}
					if (tripId < 0) {
						ContentValues tv = new ContentValues();
						tv.put("sent_at", now);
						tv.put("item_count", 0);
						tv.put("unit_count", 0);
						tv.put("complete", 0);
						// Das abgegebene Pfand gehört zu diesem Einkauf und ist damit verbraucht
						depositReturnTaken = new SharedPrefHelper(appContext).getDepositReturn();
						if (depositReturnTaken > 0) {
							tv.put("deposit_return", depositReturnTaken);
						}
						tripId = db.insert(ShoppingDb.T_TRIPS, null, tv);
					}
					if (sent == it.quantity) {
						ContentValues v = new ContentValues();
						v.put("trip_id", tripId);
						v.put("error", (String) null);
						v.put("deposit", deposits.get(it.id));
						db.update(ShoppingDb.T_ITEMS, v, "id = ?", new String[]{String.valueOf(it.id)});
					} else {
						ShoppingItem archived = it.copy();
						archived.tripId   = tripId;
						archived.deposit  = deposits.get(it.id);
						archived.quantity = sent;
						archived.error    = null;
						db.insert(ShoppingDb.T_ITEMS, null, itemToValues(archived, false));

						ContentValues v = new ContentValues();
						v.put("quantity", it.quantity - sent);
						v.put("sent_count", 0);
						db.update(ShoppingDb.T_ITEMS, v, "id = ?", new String[]{String.valueOf(it.id)});
					}
					items++;
					units += sent;
				}
				int remaining = (int) DatabaseUtils.queryNumEntries(db, ShoppingDb.T_ITEMS, "trip_id = 0");
				if (tripId >= 0) {
					ContentValues tv = new ContentValues();
					tv.put("item_count", items);
					tv.put("unit_count", units);
					tv.put("complete", remaining == 0 ? 1 : 0);
					db.update(ShoppingDb.T_TRIPS, tv, "id = ?", new String[]{String.valueOf(tripId)});
				}
				db.setTransactionSuccessful();
				if (depositReturnTaken > 0) {
					new SharedPrefHelper(appContext).setDepositReturn(0);
				}
				return new ArchiveResult(units, remaining);
			} finally {
				db.endTransaction();
			}
		}, callback);
	}

	/** Pfand je Scan für alle offenen Artikel (Nummer des Artikels -> Betrag), nach den aktuellen Regeln. */
	private Map<Long, Double> openDeposits(SQLiteDatabase db) {
		List<ShoppingItem> items = new ArrayList<>();
		try (Cursor c = db.rawQuery(OPEN_ITEMS_SQL, null)) {
			while (c.moveToNext()) {
				items.add(openItemFromCursor(c));
			}
		}
		Deposit.apply(items, new SharedPrefHelper(appContext).getDepositRules());
		Map<Long, Double> result = new HashMap<>();
		for (ShoppingItem item : items) {
			if (item.deposit != null) {
				result.put(item.id, item.deposit);
			}
		}
		return result;
	}

	/** Lässt alle Listen neu laden, z. B. nachdem das Pfand geändert wurde. */
	public void reload() {
		main.post(this::notifyChanged);
	}

	// ---------------------------------------------------------------- Verlauf

	public void getTrips(Callback<List<Trip>> callback) {
		read(() -> {
			List<Trip> trips = new ArrayList<>();
			try (Cursor c = dbHelper.getReadableDatabase().query(ShoppingDb.T_TRIPS, null, null, null,
					null, null, "sent_at DESC")) {
				while (c.moveToNext()) {
					trips.add(tripFromCursor(c));
				}
			}
			return trips;
		}, callback);
	}

	public void getTrip(final long tripId, Callback<Trip> callback) {
		read(() -> {
			try (Cursor c = dbHelper.getReadableDatabase().query(ShoppingDb.T_TRIPS, null, "id = ?",
					new String[]{String.valueOf(tripId)}, null, null, null)) {
				return c.moveToFirst() ? tripFromCursor(c) : null;
			}
		}, callback);
	}

	public void getTripItems(final long tripId, Callback<List<ShoppingItem>> callback) {
		read(() -> readItems(dbHelper.getReadableDatabase(), tripId), callback);
	}

	public void deleteTrip(final long tripId) {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			db.beginTransaction();
			try {
				String[] arg = {String.valueOf(tripId)};
				db.delete(ShoppingDb.T_ITEMS, "trip_id = ?", arg);
				db.delete(ShoppingDb.T_TRIPS, "id = ?", arg);
				db.setTransactionSuccessful();
				return null;
			} finally {
				db.endTransaction();
			}
		}, null);
	}

	public void clearHistory() {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			db.beginTransaction();
			try {
				db.delete(ShoppingDb.T_ITEMS, "trip_id <> 0", null);
				db.delete(ShoppingDb.T_TRIPS, null, null);
				db.setTransactionSuccessful();
				return null;
			} finally {
				db.endTransaction();
			}
		}, null);
	}

	// ---------------------------------------------------------------- Grocy-Stammdaten und Geschäfte

	/** Ersetzt die lokale Kopie der Grocy-Stammdaten vollständig. */
	public void replaceGrocyCache(final GrocyData data, Callback<Boolean> callback) {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			db.beginTransaction();
			try {
				db.delete(ShoppingDb.T_SHOPS, null, null);
				db.delete(ShoppingDb.T_PRODUCTS, null, null);
				db.delete(ShoppingDb.T_BARCODES, null, null);
				db.delete(ShoppingDb.T_LASTPRICE, null, null);
				db.delete(ShoppingDb.T_STOCK, null, null);
				db.delete(ShoppingDb.T_UNITS, null, null);
				db.delete(ShoppingDb.T_GROUPS, null, null);
				db.delete(ShoppingDb.T_LOCATIONS, null, null);
				for (ContentValues v : data.locations) {
					db.insertWithOnConflict(ShoppingDb.T_LOCATIONS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
				}
				for (ContentValues v : data.groups) {
					db.insertWithOnConflict(ShoppingDb.T_GROUPS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
				}
				for (ContentValues v : data.units) {
					db.insertWithOnConflict(ShoppingDb.T_UNITS, null, v, SQLiteDatabase.CONFLICT_REPLACE);
				}
				mergeShoppingEntries(db, data.shopping);
				for (ContentValues v : data.stock) {
					db.insertWithOnConflict(ShoppingDb.T_STOCK, null, v, SQLiteDatabase.CONFLICT_REPLACE);
				}
				for (ContentValues v : data.shops) {
					db.insert(ShoppingDb.T_SHOPS, null, v);
				}
				for (ContentValues v : data.products) {
					db.insert(ShoppingDb.T_PRODUCTS, null, v);
				}
				for (ContentValues v : data.barcodes) {
					db.insertWithOnConflict(ShoppingDb.T_BARCODES, null, v, SQLiteDatabase.CONFLICT_REPLACE);
				}
				for (ContentValues v : data.lastPrices) {
					db.insertWithOnConflict(ShoppingDb.T_LASTPRICE, null, v, SQLiteDatabase.CONFLICT_REPLACE);
				}
				db.setTransactionSuccessful();
				return true;
			} finally {
				db.endTransaction();
			}
		}, callback);
	}

	/**
	 * Verwirft die lokale Kopie der Grocy-Daten samt Einkaufsliste und lokalem Abhak-Stand. Nötig beim
	 * Wechsel auf einen anderen Grocy-Server, weil dessen Nummern sonst mit den alten durcheinandergeraten.
	 * Scans, Verlauf und die Reihenfolge der Geschäfte bleiben erhalten.
	 */
	public void clearGrocyCache() {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			db.beginTransaction();
			try {
				for (String table : new String[]{ShoppingDb.T_SHOPS, ShoppingDb.T_PRODUCTS, ShoppingDb.T_BARCODES,
						ShoppingDb.T_LASTPRICE, ShoppingDb.T_STOCK, ShoppingDb.T_SHOPPING, ShoppingDb.T_UNITS, ShoppingDb.T_GROUPS, ShoppingDb.T_LOCATIONS}) {
					db.delete(table, null, null);
				}
				db.setTransactionSuccessful();
				return null;
			} finally {
				db.endTransaction();
			}
		}, null);
	}

	/** Alle Geschäfte in der vom Nutzer gewählten Reihenfolge; neue Geschäfte stehen hinten, nach Name. */
	public void getShops(Callback<List<Shop>> callback) {
		read(() -> {
			SQLiteDatabase db = dbHelper.getReadableDatabase();
			Map<Long, Integer> positions = readShopPositions(db);
			List<Shop> shops = new ArrayList<>();
			try (Cursor c = db.query(ShoppingDb.T_SHOPS, new String[]{"id", "name"}, null, null,
					null, null, "name COLLATE NOCASE")) {
				while (c.moveToNext()) {
					shops.add(new Shop(c.getLong(0), c.getString(1)));
				}
			}
			final Map<Long, Integer> pos = positions;
			java.util.Collections.sort(shops, (a, b) -> {
				int pa = pos.containsKey(a.id) ? pos.get(a.id) : Integer.MAX_VALUE;
				int pb = pos.containsKey(b.id) ? pos.get(b.id) : Integer.MAX_VALUE;
				if (pa != pb) {
					return pa < pb ? -1 : 1;
				}
				return a.name.compareToIgnoreCase(b.name);
			});
			return shops;
		}, callback);
	}

	/** Ein Eintrag einer Auswahlliste aus den Grocy-Stammdaten. */
	public static final class Option {
		public final long   id;
		public final String name;

		Option(long id, String name) {
			this.id   = id;
			this.name = name;
		}

		@Override
		public String toString() {
			return name;
		}
	}

	/** Orte, Geschäfte, Produktgruppen oder Mengeneinheiten für die Auswahlfelder, nach Name sortiert. */
	public void getOptions(final String kind, Callback<List<Option>> callback) {
		final String table;
		switch (kind) {
			case "locations": table = ShoppingDb.T_LOCATIONS; break;
			case "shops":     table = ShoppingDb.T_SHOPS; break;
			case "groups":    table = ShoppingDb.T_GROUPS; break;
			default:          table = ShoppingDb.T_UNITS; break;
		}
		read(() -> {
			List<Option> options = new ArrayList<>();
			try (Cursor c = dbHelper.getReadableDatabase().query(table, new String[]{"id", "name"}, null, null,
					null, null, "name COLLATE NOCASE")) {
				while (c.moveToNext()) {
					options.add(new Option(c.getLong(0), c.getString(1)));
				}
			}
			return options;
		}, callback);
	}

	/** Alle Produktgruppen aus Grocy in der vom Nutzer gewählten Reihenfolge; neue stehen hinten, nach Name. */
	public void getGroups(Callback<List<String>> callback) {
		read(() -> {
			SQLiteDatabase db = dbHelper.getReadableDatabase();
			final Map<String, Integer> pos = readGroupPositions(db);
			List<String> groups = new ArrayList<>();
			try (Cursor c = db.query(ShoppingDb.T_GROUPS, new String[]{"name"}, null, null, null, null,
					"name COLLATE NOCASE")) {
				while (c.moveToNext()) {
					groups.add(c.getString(0));
				}
			}
			java.util.Collections.sort(groups, (a, b) -> {
				int pa = pos.containsKey(a) ? pos.get(a) : Integer.MAX_VALUE;
				int pb = pos.containsKey(b) ? pos.get(b) : Integer.MAX_VALUE;
				return pa != pb ? Integer.compare(pa, pb) : a.compareToIgnoreCase(b);
			});
			return groups;
		}, callback);
	}

	public void saveGroupOrder(final List<String> names) {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			db.beginTransaction();
			try {
				db.delete(ShoppingDb.T_GROUP_ORDER, null, null);
				for (int i = 0; i < names.size(); i++) {
					ContentValues v = new ContentValues();
					v.put("group_name", names.get(i));
					v.put("position", i);
					db.insert(ShoppingDb.T_GROUP_ORDER, null, v);
				}
				db.setTransactionSuccessful();
				return null;
			} finally {
				db.endTransaction();
			}
		}, null);
	}

	private static Map<String, Integer> readGroupPositions(SQLiteDatabase db) {
		Map<String, Integer> positions = new HashMap<>();
		try (Cursor c = db.query(ShoppingDb.T_GROUP_ORDER, new String[]{"group_name", "position"},
				null, null, null, null, null)) {
			while (c.moveToNext()) {
				positions.put(c.getString(0), c.getInt(1));
			}
		}
		return positions;
	}

	public void saveShopOrder(final List<Long> shopIds) {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			db.beginTransaction();
			try {
				db.delete(ShoppingDb.T_SHOP_ORDER, null, null);
				for (int i = 0; i < shopIds.size(); i++) {
					ContentValues v = new ContentValues();
					v.put("shop_id", shopIds.get(i));
					v.put("position", i);
					db.insert(ShoppingDb.T_SHOP_ORDER, null, v);
				}
				db.setTransactionSuccessful();
				return null;
			} finally {
				db.endTransaction();
			}
		}, null);
	}

	// ---------------------------------------------------------------- Einkaufsliste aus Grocy

	/** Ein Eintrag der Grocy-Einkaufsliste für die Anzeige. */
	public static final class ShoppingEntry {
		public long    id;
		public Long    productId;
		public String  name;        // Produktname oder Notiz
		public boolean isNote;      // Eintrag ohne Produkt
		public double  amount;      // Menge in der Einheit des Eintrags
		public String  unit;
		public double  stockAmount; // Menge in Lagereinheiten
		public double  scanned;     // schon gescannte Menge in Lagereinheiten
		public boolean serverDone;  // Stand in Grocy
		public Integer manual;      // Wunsch des Nutzers: 1 = abgehakt, 0 = offen, null = keiner
		public Double  pendingAmount; // von Hand geänderte Menge, die Grocy noch nicht kennt (amount zeigt sie schon)
		public Long    shopId;
		public String  shopName;
		public String  group;       // Produktgruppe; bei freien Einträgen die von Hand gewählte (oder null)
		public long    quId;        // Einheit des Eintrags in Grocy, 0 = unbekannt
		public String  stockUnit;
		public Double  lastPrice;   // letzter Preis je Lagereinheit
		public Integer bestBeforeDays;

		/** Abgehakt (von Hand oder in Grocy) oder durch Scans bereits vollständig abgedeckt. */
		public boolean isDone() {
			if (isCoveredByScans()) {
				return true;
			}
			return manual != null ? manual == 1 : serverDone;
		}

		/** Der gewünschte Stand weicht vom Stand in Grocy ab und muss noch dorthin übertragen werden. */
		public boolean needsUpload() {
			return isDone() != serverDone || pendingAmount != null;
		}

		/** Von Hand abgehakt und noch nicht eingebucht: wird beim Senden gebucht. */
		public boolean needsBooking() {
			return manual != null && manual == 1 && !isCoveredByScans();
		}

		/** Durch Scans erledigt (lässt sich nicht von Hand zurücknehmen). */
		public boolean isCoveredByScans() {
			return !isNote && scanned > 0 && scanned + 1e-9 >= stockAmount;
		}
	}

	public static final class ShoppingData {
		public final List<ShoppingEntry> entries;
		public final Map<Long, Integer>  shopPosition;
		public final Map<String, Integer> groupPosition;

		ShoppingData(List<ShoppingEntry> entries, Map<Long, Integer> shopPosition, Map<String, Integer> groupPosition) {
			this.entries       = entries;
			this.shopPosition  = shopPosition;
			this.groupPosition = groupPosition;
		}

		/** Gesamtwert der Liste wie in Grocy: Menge in Lagereinheiten mal letzter Preis, ohne bekannte Preise null. */
		public double totalValue() {
			double sum = 0;
			for (ShoppingEntry e : entries) {
				if (!e.isNote && e.lastPrice != null) {
					sum += e.lastPrice * e.stockAmount;
				}
			}
			return sum;
		}

		private int groupRank(ShoppingEntry e) {
			if (e.group == null) {
				return Integer.MAX_VALUE;
			}
			Integer p = groupPosition.get(e.group);
			return p != null ? p : Integer.MAX_VALUE - 1;
		}

		/** Reihenfolge der offenen Einträge: nach Produktgruppe (in der gewählten Reihenfolge), dann nach Name. */
		public java.util.Comparator<ShoppingEntry> openOrder() {
			return (a, b) -> {
				int c = Integer.compare(groupRank(a), groupRank(b));
				if (c != 0) {
					return c;
				}
				c = String.valueOf(a.group).compareToIgnoreCase(String.valueOf(b.group));
				return c != 0 ? c : a.name.compareToIgnoreCase(b.name);
			};
		}
	}

	/**
	 * Übernimmt die Einkaufsliste aus Grocy. Der Wunsch des Nutzers (abgehakt oder wieder geöffnet) bleibt
	 * erhalten, bis er erfüllt ist.
	 */
	private static void mergeShoppingEntries(SQLiteDatabase db, List<ContentValues> incoming) {
		Map<Long, Integer> wishes = new HashMap<>();
		try (Cursor c = db.query(ShoppingDb.T_SHOPPING, new String[]{"id", "manual"}, "manual IS NOT NULL", null,
				null, null, null)) {
			while (c.moveToNext()) {
				wishes.put(c.getLong(0), c.getInt(1));
			}
		}
		Map<Long, Double> amounts = new HashMap<>();
		try (Cursor c = db.query(ShoppingDb.T_SHOPPING, new String[]{"id", "manual_amount"}, "manual_amount IS NOT NULL",
				null, null, null, null)) {
			while (c.moveToNext()) {
				amounts.put(c.getLong(0), c.getDouble(1));
			}
		}
		db.delete(ShoppingDb.T_SHOPPING, null, null);
		for (ContentValues v : incoming) {
			Double pendingAmount = amounts.get(v.getAsLong("id"));
			if (pendingAmount != null && Math.abs(pendingAmount - v.getAsDouble("amount")) > 0.0001) {
				// Grocy kennt die neue Menge noch nicht: sie bleibt vorgemerkt
				v.put("manual_amount", pendingAmount);
			}
			Integer wish = wishes.get(v.getAsLong("id"));
			if (wish != null) {
				boolean serverDone = v.getAsInteger("done") != null && v.getAsInteger("done") != 0;
				// Ein gewünschtes "offen" ist erfüllt, wenn Grocy den Eintrag ebenfalls offen hat
				if (wish == 1 || serverDone) {
					v.put("manual", wish);
				}
			}
			db.insertWithOnConflict(ShoppingDb.T_SHOPPING, null, v, SQLiteDatabase.CONFLICT_REPLACE);
		}
		// Gruppen gelöschter freier Einträge aufräumen; ganz frische bleiben, falls der Abgleich vor dem Anlegen geladen hat
		db.execSQL("DELETE FROM " + ShoppingDb.T_NOTE_GROUP + " WHERE entry_id NOT IN (SELECT id FROM " + ShoppingDb.T_SHOPPING
				+ ") AND created_at < ?", new Object[]{System.currentTimeMillis() - 10 * 60 * 1000L});
	}

	/** Die Einkaufsliste mit Produktdaten und der Menge, die schon gescannt wurde. */
	public void getShoppingList(Callback<ShoppingData> callback) {
		read(() -> {
			SQLiteDatabase db = dbHelper.getReadableDatabase();
			String sql = "SELECT e.id, e.product_id, e.note, e.amount, e.stock_amount, e.done, e.manual, e.manual_amount, e.qu_id, "
					+ "u.name AS unit, p.name AS pname, COALESCE(p.group_name, ng.group_name) AS group_name, p.shop_id, p.stock_unit, p.best_before_days, "
					+ "s.name AS shop, lp.price AS last_price, COALESCE(sc.scanned, 0) AS scanned "
					+ "FROM g_shopping e "
					+ "LEFT JOIN g_units u ON u.id = e.qu_id "
					+ "LEFT JOIN shopping_note_group ng ON ng.entry_id = e.id "
					+ "LEFT JOIN g_products p ON p.id = e.product_id "
					+ "LEFT JOIN g_shops s ON s.id = p.shop_id "
					+ "LEFT JOIN g_lastprice lp ON lp.product_id = e.product_id "
					+ "LEFT JOIN (SELECT b.product_id AS pid, SUM(i.quantity * b.stock_per_scan) AS scanned "
					+ "FROM items i JOIN g_barcodes b ON b.barcode = i.barcode WHERE i.trip_id = 0 "
					+ "GROUP BY b.product_id) sc ON sc.pid = e.product_id "
					+ "ORDER BY e.id";
			List<ShoppingEntry> entries = new ArrayList<>();
			try (Cursor c = db.rawQuery(sql, null)) {
				while (c.moveToNext()) {
					ShoppingEntry e = new ShoppingEntry();
					e.id = c.getLong(c.getColumnIndexOrThrow("id"));
					int productCol = c.getColumnIndexOrThrow("product_id");
					e.productId = c.isNull(productCol) ? null : c.getLong(productCol);
					String pname = c.getString(c.getColumnIndexOrThrow("pname"));
					String note = c.getString(c.getColumnIndexOrThrow("note"));
					e.isNote = e.productId == null || pname == null;
					e.name = pname != null ? pname : (note != null ? note : "?");
					e.amount = c.getDouble(c.getColumnIndexOrThrow("amount"));
					e.stockAmount = c.getDouble(c.getColumnIndexOrThrow("stock_amount"));
					e.unit = c.getString(c.getColumnIndexOrThrow("unit"));
					int quCol = c.getColumnIndexOrThrow("qu_id");
					e.quId = c.isNull(quCol) ? 0 : c.getLong(quCol);
					Double pending = getDouble(c, "manual_amount");
					if (pending != null && pending > 0 && e.amount > 0) {
						// Die Menge in Lagereinheiten wächst und schrumpft mit
						e.stockAmount = e.stockAmount * pending / e.amount;
						e.amount = pending;
						e.pendingAmount = pending;
					}
					e.serverDone = c.getInt(c.getColumnIndexOrThrow("done")) != 0;
					int manualCol = c.getColumnIndexOrThrow("manual");
					e.manual = c.isNull(manualCol) ? null : c.getInt(manualCol);
					e.scanned = c.getDouble(c.getColumnIndexOrThrow("scanned"));
					e.group = c.getString(c.getColumnIndexOrThrow("group_name"));
					int shopCol = c.getColumnIndexOrThrow("shop_id");
					e.shopId = c.isNull(shopCol) ? null : c.getLong(shopCol);
					e.shopName = c.getString(c.getColumnIndexOrThrow("shop"));
					e.stockUnit = c.getString(c.getColumnIndexOrThrow("stock_unit"));
					e.lastPrice = getDouble(c, "last_price");
					Double days = getDouble(c, "best_before_days");
					e.bestBeforeDays = days == null ? null : (int) Math.round(days);
					entries.add(e);
				}
			}
			return new ShoppingData(entries, readShopPositions(db), readGroupPositions(db));
		}, callback);
	}

	/** Merkt die Produktgruppe eines freien Eintrags (null/leer entfernt sie). */
	public void setNoteGroup(final long entryId, final String groupName) {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			if (groupName == null || groupName.isEmpty()) {
				return db.delete(ShoppingDb.T_NOTE_GROUP, "entry_id = ?", new String[]{String.valueOf(entryId)});
			}
			ContentValues v = new ContentValues();
			v.put("entry_id", entryId);
			v.put("group_name", groupName);
			v.put("created_at", System.currentTimeMillis());
			return db.insertWithOnConflict(ShoppingDb.T_NOTE_GROUP, null, v, SQLiteDatabase.CONFLICT_REPLACE);
		}, null);
	}

	/** Nimmt einen Eintrag aus der lokalen Kopie der Einkaufsliste, nachdem Grocy ihn gelöscht hat. */
	public void removeShoppingEntry(final long entryId) {
		write(() -> {
			SQLiteDatabase db = dbHelper.getWritableDatabase();
			String[] arg = {String.valueOf(entryId)};
			db.delete(ShoppingDb.T_NOTE_GROUP, "entry_id = ?", arg);
			return db.delete(ShoppingDb.T_SHOPPING, "id = ?", arg);
		}, null);
	}

	/** Hakt einen Eintrag von Hand ab oder öffnet ihn wieder; die App überträgt das an Grocy, sobald es geht. */
	public void setShoppingDone(final long entryId, final boolean done) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.put("manual", done ? 1 : 0);
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_SHOPPING, v, "id = ?",
					new String[]{String.valueOf(entryId)});
		}, null);
	}

	/** Ändert die Menge eines Eintrags von Hand; die App überträgt das an Grocy, sobald es geht. */
	public void setShoppingAmount(final long entryId, final double amount) {
		write(() -> {
			dbHelper.getWritableDatabase().execSQL("UPDATE " + ShoppingDb.T_SHOPPING
					+ " SET manual_amount = CASE WHEN ABS(amount - ?) < 0.0001 THEN NULL ELSE ? END WHERE id = ?",
					new Object[]{amount, amount, entryId});
			return null;
		}, null);
	}

	/** Grocy hat die geänderte Menge dieses Eintrags übernommen. */
	public void setServerAmount(final long entryId, final double amount) {
		write(() -> {
			dbHelper.getWritableDatabase().execSQL("UPDATE " + ShoppingDb.T_SHOPPING
					+ " SET stock_amount = CASE WHEN amount > 0 THEN stock_amount * ? / amount ELSE stock_amount END, "
					+ "amount = ?, manual_amount = NULL WHERE id = ?", new Object[]{amount, amount, entryId});
			return null;
		}, null);
	}

	/** Grocy hat den Abhak-Stand dieses Eintrags übernommen. */
	public void setServerDone(final long entryId, final boolean done) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.put("done", done ? 1 : 0);
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_SHOPPING, v, "id = ?",
					new String[]{String.valueOf(entryId)});
		}, null);
	}

	/** Der Eintrag wurde gebucht beziehungsweise abgehakt übertragen: kein offener Wunsch mehr. */
	public void clearShoppingManual(final long entryId) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.putNull("manual");
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_SHOPPING, v, "id = ?",
					new String[]{String.valueOf(entryId)});
		}, null);
	}

	// ---------------------------------------------------------------- Produktdatenbank

	/** Ein Produkt aus der lokalen Kopie der Grocy-Daten. */
	public static final class ProductRow {
		public long   id;
		public Long   shopId;
		public String name;
		public String group;
		public String shop;
		public String stockUnit;
		public String priceUnit;
		public Double stock;
		/** Letzter Preis je Preiseinheit (z. B. je Pack) */
		public Double lastPrice;
		public int    barcodes;
	}

	/** Produkte, deren Name oder Barcode den Suchtext enthält (leer = alle), nach Name sortiert. */
	public void getProducts(final String query, Callback<List<ProductRow>> callback) {
		read(() -> {
			String like = "%" + (query == null ? "" : query.trim().replace("%", "").replace("_", "")) + "%";
			String sql = "SELECT p.id, p.shop_id, p.name, p.group_name, p.stock_unit, p.price_unit, p.stock_to_price, "
					+ "(SELECT name FROM g_shops WHERE id = p.shop_id) AS shop, st.amount AS stock, lp.price AS last_price, "
					+ "(SELECT COUNT(*) FROM g_barcodes b WHERE b.product_id = p.id) AS barcodes "
					+ "FROM g_products p LEFT JOIN g_stock st ON st.product_id = p.id "
					+ "LEFT JOIN g_lastprice lp ON lp.product_id = p.id "
					+ "WHERE p.name LIKE ? OR p.id IN (SELECT product_id FROM g_barcodes WHERE barcode LIKE ?) "
					+ "ORDER BY p.name COLLATE NOCASE";
			List<ProductRow> rows = new ArrayList<>();
			try (Cursor c = dbHelper.getReadableDatabase().rawQuery(sql, new String[]{like, like})) {
				while (c.moveToNext()) {
					ProductRow row = new ProductRow();
					row.id        = c.getLong(c.getColumnIndexOrThrow("id"));
					row.name      = c.getString(c.getColumnIndexOrThrow("name"));
					int shopIdCol = c.getColumnIndexOrThrow("shop_id");
					row.shopId    = c.isNull(shopIdCol) ? null : c.getLong(shopIdCol);
					row.group     = c.getString(c.getColumnIndexOrThrow("group_name"));
					row.shop      = c.getString(c.getColumnIndexOrThrow("shop"));
					row.stockUnit = c.getString(c.getColumnIndexOrThrow("stock_unit"));
					row.priceUnit = c.getString(c.getColumnIndexOrThrow("price_unit"));
					row.stock     = getDouble(c, "stock");
					Double price  = getDouble(c, "last_price");
					double factor = c.getDouble(c.getColumnIndexOrThrow("stock_to_price"));
					row.lastPrice = price == null || factor <= 0 ? null : price / factor;
					row.barcodes  = c.getInt(c.getColumnIndexOrThrow("barcodes"));
					rows.add(row);
				}
			}
			return rows;
		}, callback);
	}

	/** Die Barcodes eines Produkts als lesbare Zeilen: Barcode, Geschäft, Menge je Scan. */
	public void getProductBarcodes(final long productId, Callback<List<String>> callback) {
		read(() -> {
			List<String> lines = new ArrayList<>();
			String sql = "SELECT b.barcode, b.stock_per_scan, b.exact, p.stock_unit, "
					+ "(SELECT name FROM g_shops WHERE id = COALESCE(b.shop_id, p.shop_id)) AS shop "
					+ "FROM g_barcodes b JOIN g_products p ON p.id = b.product_id WHERE b.product_id = ? ORDER BY b.barcode";
			try (Cursor c = dbHelper.getReadableDatabase().rawQuery(sql, new String[]{String.valueOf(productId)})) {
				while (c.moveToNext()) {
					StringBuilder line = new StringBuilder(c.getString(0));
					String shop = c.getString(c.getColumnIndexOrThrow("shop"));
					if (shop != null) {
						line.append(" · ").append(shop);
					}
					if (c.getInt(c.getColumnIndexOrThrow("exact")) != 0) {
						double perScan = c.getDouble(1);
						String unit = c.getString(c.getColumnIndexOrThrow("stock_unit"));
						line.append(" · ").append(perScan == Math.rint(perScan)
								? String.valueOf((long) perScan) : String.valueOf(perScan).replace('.', ','))
								.append(unit == null ? "" : " " + unit).append("/Scan");
					}
					lines.add(line.toString());
				}
			}
			return lines;
		}, callback);
	}

	public void getProductCount(Callback<Integer> callback) {
		read(() -> (int) DatabaseUtils.queryNumEntries(dbHelper.getReadableDatabase(), ShoppingDb.T_PRODUCTS), callback);
	}

	// ---------------------------------------------------------------- Inventur

	/** Ein Produkt für die Inventur: Bestand laut lokaler Kopie und Menge, die ein Scan des Barcodes zählt. */
	public static final class InventoryProduct {
		public long    id;
		public String  name;
		public String  group;
		public String  stockUnit;
		/** Bestand in Lagereinheiten */
		public double  stock;
		/** Lagereinheiten je Scan dieses Barcodes */
		public double  stockPerScan = 1;
		/** Einheit des Barcodes, z. B. "Pack" (kann fehlen) */
		public String  scanUnit;
		public Integer bestBeforeDays;
	}

	/** Das Produkt zu einem Barcode, oder null, wenn die lokale Kopie den Barcode nicht kennt. */
	public void findInventoryProduct(final String barcode, Callback<InventoryProduct> callback) {
		read(() -> {
			String sql = "SELECT p.id, p.name, p.group_name, p.stock_unit, p.best_before_days, "
					+ "COALESCE(st.amount, 0) AS stock, b.stock_per_scan, b.scan_unit "
					+ "FROM g_barcodes b JOIN g_products p ON p.id = b.product_id "
					+ "LEFT JOIN g_stock st ON st.product_id = p.id WHERE b.barcode = ?";
			try (Cursor c = dbHelper.getReadableDatabase().rawQuery(sql, new String[]{barcode})) {
				if (!c.moveToFirst()) {
					return null;
				}
				InventoryProduct p = new InventoryProduct();
				p.id             = c.getLong(c.getColumnIndexOrThrow("id"));
				p.name           = c.getString(c.getColumnIndexOrThrow("name"));
				p.group          = c.getString(c.getColumnIndexOrThrow("group_name"));
				p.stockUnit      = c.getString(c.getColumnIndexOrThrow("stock_unit"));
				p.stock          = c.getDouble(c.getColumnIndexOrThrow("stock"));
				p.stockPerScan   = c.getDouble(c.getColumnIndexOrThrow("stock_per_scan"));
				p.scanUnit       = c.getString(c.getColumnIndexOrThrow("scan_unit"));
				Double days      = getDouble(c, "best_before_days");
				p.bestBeforeDays = days == null ? null : (int) Math.round(days);
				return p;
			}
		}, callback);
	}

	/** Alles, was die Bestandsabfrage zu einem gescannten Barcode anzeigt. */
	public static final class StockInfo {
		public long    id;
		public String  name;
		public String  group;
		public String  stockUnit;
		/** Bestand laut lokaler Kopie in Lagereinheiten */
		public double  stock;
		public double  stockPerScan = 1;
		public String  scanUnit;
		/** Schnellverbrauch-Menge in Lagereinheiten, null = nicht bekannt */
		public Double  quickConsume;
		/** Letzter Preis je Preiseinheit */
		public Double  lastPrice;
		public String  priceUnit;
		/** Menge, die offen auf der Einkaufsliste steht, in Lagereinheiten */
		public double  onShoppingList;
	}

	/** Die Bestandsauskunft zu einem Barcode, oder null, wenn die lokale Kopie ihn nicht kennt. */
	public void findStockInfo(final String barcode, Callback<StockInfo> callback) {
		read(() -> {
			String sql = "SELECT p.id, p.name, p.group_name, p.stock_unit, p.price_unit, p.stock_to_price, "
					+ "p.consume_amount, COALESCE(st.amount, 0) AS stock, b.stock_per_scan, b.scan_unit, "
					+ "lp.price AS last_price, (SELECT COALESCE(SUM(s.stock_amount), 0) FROM g_shopping s "
					+ "WHERE s.product_id = p.id AND s.done = 0) AS on_list "
					+ "FROM g_barcodes b JOIN g_products p ON p.id = b.product_id "
					+ "LEFT JOIN g_stock st ON st.product_id = p.id LEFT JOIN g_lastprice lp ON lp.product_id = p.id "
					+ "WHERE b.barcode = ?";
			try (Cursor c = dbHelper.getReadableDatabase().rawQuery(sql, new String[]{barcode})) {
				if (!c.moveToFirst()) {
					return null;
				}
				StockInfo info = new StockInfo();
				info.id             = c.getLong(c.getColumnIndexOrThrow("id"));
				info.name           = c.getString(c.getColumnIndexOrThrow("name"));
				info.group          = c.getString(c.getColumnIndexOrThrow("group_name"));
				info.stockUnit      = c.getString(c.getColumnIndexOrThrow("stock_unit"));
				info.priceUnit      = c.getString(c.getColumnIndexOrThrow("price_unit"));
				info.stock          = c.getDouble(c.getColumnIndexOrThrow("stock"));
				info.stockPerScan   = c.getDouble(c.getColumnIndexOrThrow("stock_per_scan"));
				info.scanUnit       = c.getString(c.getColumnIndexOrThrow("scan_unit"));
				info.quickConsume   = getDouble(c, "consume_amount");
				info.onShoppingList = c.getDouble(c.getColumnIndexOrThrow("on_list"));
				Double price        = getDouble(c, "last_price");
				double factor       = c.getDouble(c.getColumnIndexOrThrow("stock_to_price"));
				info.lastPrice      = price == null || factor <= 0 ? null : price / factor;
				return info;
			}
		}, callback);
	}

	/** Anzahl der Produkte, von denen laut lokaler Kopie etwas im Bestand ist. */
	public void getStockedProductCount(Callback<Integer> callback) {
		read(() -> (int) DatabaseUtils.queryNumEntries(dbHelper.getReadableDatabase(), ShoppingDb.T_STOCK, "amount > 0"),
				callback);
	}

	// ---------------------------------------------------------------- Helfer

	private static Map<Long, Integer> readShopPositions(SQLiteDatabase db) {
		Map<Long, Integer> positions = new HashMap<>();
		try (Cursor c = db.query(ShoppingDb.T_SHOP_ORDER, new String[]{"shop_id", "position"},
				null, null, null, null, null)) {
			while (c.moveToNext()) {
				positions.put(c.getLong(0), c.getInt(1));
			}
		}
		return positions;
	}

	private static List<ShoppingItem> readItems(SQLiteDatabase db, long tripId) {
		List<ShoppingItem> items = new ArrayList<>();
		try (Cursor c = db.query(ShoppingDb.T_ITEMS, ITEM_COLUMNS, "trip_id = ?",
				new String[]{String.valueOf(tripId)}, null, null, "scanned_at DESC")) {
			while (c.moveToNext()) {
				items.add(itemFromCursor(c));
			}
		}
		return items;
	}

	private static ShoppingItem itemFromCursor(Cursor c) {
		ShoppingItem item = new ShoppingItem();
		item.id        = c.getLong(c.getColumnIndexOrThrow("id"));
		item.tripId    = c.getLong(c.getColumnIndexOrThrow("trip_id"));
		item.barcode   = c.getString(c.getColumnIndexOrThrow("barcode"));
		item.name      = c.getString(c.getColumnIndexOrThrow("name"));
		item.quantity  = c.getInt(c.getColumnIndexOrThrow("quantity"));
		item.scannedAt = c.getLong(c.getColumnIndexOrThrow("scanned_at"));
		item.sentCount = c.getInt(c.getColumnIndexOrThrow("sent_count"));
		item.message   = c.getString(c.getColumnIndexOrThrow("message"));
		item.error     = c.getString(c.getColumnIndexOrThrow("error"));
		item.price     = getDouble(c, "price");
		item.scanPrice = getDouble(c, "scan_price");
		item.shopName  = c.getString(c.getColumnIndexOrThrow("shop_name"));
		item.deposit   = getDouble(c, "deposit");
		return item;
	}

	/** Wie {@link #itemFromCursor}, dazu die Grocy-Spalten der offenen Liste. */
	private static ShoppingItem openItemFromCursor(Cursor c) {
		ShoppingItem item = itemFromCursor(c);
		item.suggestion = c.getString(c.getColumnIndexOrThrow("off_name"));
		int productCol = c.getColumnIndexOrThrow("g_product_id");
		if (!c.isNull(productCol)) {
			item.known         = true;
			item.productId     = c.getLong(productCol);
			item.productName   = c.getString(c.getColumnIndexOrThrow("g_name"));
			int groupCol = c.getColumnIndexOrThrow("g_group_id");
			item.groupId       = c.isNull(groupCol) ? null : c.getLong(groupCol);
			item.stockPerScan  = c.getDouble(c.getColumnIndexOrThrow("g_stock_per_scan"));
			item.stockToPrice  = c.getDouble(c.getColumnIndexOrThrow("g_stock_to_price"));
			item.stockUnitName = c.getString(c.getColumnIndexOrThrow("g_stock_unit"));
			item.priceUnitName = c.getString(c.getColumnIndexOrThrow("g_price_unit"));
			item.lastPrice     = getDouble(c, "g_last_price");
			item.stock         = getDouble(c, "g_stock");
			Double days        = getDouble(c, "g_bb_days");
			item.bestBeforeDays = days == null ? null : (int) Math.round(days);
			item.tare          = c.getInt(c.getColumnIndexOrThrow("g_tare")) != 0;
			item.exactAmount   = c.getInt(c.getColumnIndexOrThrow("g_exact")) != 0;
			int shopCol = c.getColumnIndexOrThrow("g_shop_id");
			if (!c.isNull(shopCol)) {
				item.shopId   = c.getLong(shopCol);
				item.shopName = c.getString(c.getColumnIndexOrThrow("g_shop_name"));
			} else {
				item.shopName = null;
			}
		}
		return item;
	}

	private static Double getDouble(Cursor c, String column) {
		int index = c.getColumnIndexOrThrow(column);
		return c.isNull(index) ? null : c.getDouble(index);
	}

	private static ContentValues itemToValues(ShoppingItem item, boolean withId) {
		ContentValues v = new ContentValues();
		if (withId) {
			v.put("id", item.id);
		}
		v.put("trip_id", item.tripId);
		v.put("barcode", item.barcode);
		v.put("name", item.name);
		v.put("quantity", item.quantity);
		v.put("scanned_at", item.scannedAt);
		v.put("sent_count", item.sentCount);
		v.put("message", item.message);
		v.put("error", item.error);
		v.put("price", item.price);
		v.put("scan_price", item.scanPrice);
		v.put("shop_name", item.shopName);
		v.put("deposit", item.deposit);
		return v;
	}

	private static Trip tripFromCursor(Cursor c) {
		Trip trip = new Trip();
		trip.id        = c.getLong(c.getColumnIndexOrThrow("id"));
		trip.sentAt    = c.getLong(c.getColumnIndexOrThrow("sent_at"));
		trip.itemCount = c.getInt(c.getColumnIndexOrThrow("item_count"));
		trip.unitCount = c.getInt(c.getColumnIndexOrThrow("unit_count"));
		trip.complete  = c.getInt(c.getColumnIndexOrThrow("complete")) != 0;
		trip.depositReturn = c.isNull(c.getColumnIndexOrThrow("deposit_return")) ? 0
				: c.getDouble(c.getColumnIndexOrThrow("deposit_return"));
		return trip;
	}
}
