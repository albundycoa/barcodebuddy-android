package de.bulling.barcodebuddyscanner.Data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
			"price", "scan_price", "shop_name"
	};

	/** Offene Artikel mit den passenden Grocy-Daten (Name, Geschäft, Einheiten, Letztpreis). */
	private static final String OPEN_ITEMS_SQL =
			"SELECT i.id, i.trip_id, i.barcode, i.name, i.quantity, i.scanned_at, i.sent_count, i.message, "
					+ "i.error, i.price, i.scan_price, i.shop_name, "
					+ "b.product_id AS g_product_id, b.stock_per_scan AS g_stock_per_scan, "
					+ "p.name AS g_name, p.stock_to_price AS g_stock_to_price, p.stock_unit AS g_stock_unit, "
					+ "p.price_unit AS g_price_unit, p.best_before_days AS g_bb_days, p.tare AS g_tare, "
					+ "b.exact AS g_exact, st.amount AS g_stock, "
					+ "COALESCE(b.shop_id, p.shop_id) AS g_shop_id, s.name AS g_shop_name, "
					+ "lp.price AS g_last_price "
					+ "FROM items i "
					// Wie Barcode Buddy: ein führend mit Nullen aufgefüllter Code (GTIN-14) trifft auch den EAN-13
					+ "LEFT JOIN g_barcodes b ON b.barcode = i.barcode OR b.barcode = ltrim(i.barcode, '0') "
					+ "LEFT JOIN g_products p ON p.id = b.product_id "
					+ "LEFT JOIN g_shops s ON s.id = COALESCE(b.shop_id, p.shop_id) "
					+ "LEFT JOIN g_lastprice lp ON lp.product_id = p.id "
					+ "LEFT JOIN g_stock st ON st.product_id = p.id "
					+ "WHERE i.trip_id = 0 ORDER BY i.scanned_at DESC";

	private static ShoppingRepository instance;

	public static synchronized ShoppingRepository get(Context context) {
		if (instance == null) {
			instance = new ShoppingRepository(context.getApplicationContext());
		}
		return instance;
	}

	private final ShoppingDb                     dbHelper;
	private final ExecutorService                io        = Executors.newSingleThreadExecutor();
	private final Handler                        main      = new Handler(Looper.getMainLooper());
	private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

	private ShoppingRepository(Context appContext) {
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
		}, callback);
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
			boolean cache = DatabaseUtils.queryNumEntries(db, ShoppingDb.T_PRODUCTS) > 0;
			return new OpenList(items, cache, readShopPositions(db));
		}, callback);
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
				long tripId = -1;
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
						tripId = db.insert(ShoppingDb.T_TRIPS, null, tv);
					}
					if (sent == it.quantity) {
						ContentValues v = new ContentValues();
						v.put("trip_id", tripId);
						v.put("error", (String) null);
						db.update(ShoppingDb.T_ITEMS, v, "id = ?", new String[]{String.valueOf(it.id)});
					} else {
						ShoppingItem archived = it.copy();
						archived.tripId   = tripId;
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
				return new ArchiveResult(units, remaining);
			} finally {
				db.endTransaction();
			}
		}, callback);
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
		public boolean doneInGrocy; // abgehakt (aus Grocy oder von Hand)
		public boolean pending;     // lokal geändert, noch nicht an Grocy übertragen
		public Long    shopId;
		public String  shopName;
		public String  group;
		public String  stockUnit;
		public Double  lastPrice;   // letzter Preis je Lagereinheit
		public Integer bestBeforeDays;

		/** Abgehakt oder durch Scans bereits vollständig abgedeckt. */
		public boolean isDone() {
			return doneInGrocy || isCoveredByScans();
		}

		/** Durch Scans erledigt (lässt sich nicht von Hand zurücknehmen). */
		public boolean isCoveredByScans() {
			return !isNote && scanned > 0 && scanned + 1e-9 >= stockAmount;
		}
	}

	public static final class ShoppingData {
		public final List<ShoppingEntry> entries;
		public final Map<Long, Integer>  shopPosition;

		ShoppingData(List<ShoppingEntry> entries, Map<Long, Integer> shopPosition) {
			this.entries      = entries;
			this.shopPosition = shopPosition;
		}
	}

	/**
	 * Übernimmt die Einkaufsliste aus Grocy. Einträge, die lokal abgehakt oder wieder geöffnet wurden und
	 * noch nicht übertragen sind, behalten ihren lokalen Stand.
	 */
	private static void mergeShoppingEntries(SQLiteDatabase db, List<ContentValues> incoming) {
		Map<Long, Integer> localDone = new HashMap<>();
		try (Cursor c = db.query(ShoppingDb.T_SHOPPING, new String[]{"id", "done"}, "pending = 1", null,
				null, null, null)) {
			while (c.moveToNext()) {
				localDone.put(c.getLong(0), c.getInt(1));
			}
		}
		db.delete(ShoppingDb.T_SHOPPING, null, null);
		for (ContentValues v : incoming) {
			Integer local = localDone.get(v.getAsLong("id"));
			if (local != null) {
				v.put("done", local);
				v.put("pending", 1);
			}
			db.insertWithOnConflict(ShoppingDb.T_SHOPPING, null, v, SQLiteDatabase.CONFLICT_REPLACE);
		}
	}

	/** Die Einkaufsliste mit Produktdaten und der Menge, die schon gescannt wurde. */
	public void getShoppingList(Callback<ShoppingData> callback) {
		read(() -> {
			SQLiteDatabase db = dbHelper.getReadableDatabase();
			String sql = "SELECT e.id, e.product_id, e.note, e.amount, e.stock_amount, e.done, e.pending, "
					+ "u.name AS unit, p.name AS pname, p.group_name, p.shop_id, p.stock_unit, p.best_before_days, "
					+ "s.name AS shop, lp.price AS last_price, COALESCE(sc.scanned, 0) AS scanned "
					+ "FROM g_shopping e "
					+ "LEFT JOIN g_units u ON u.id = e.qu_id "
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
					e.doneInGrocy = c.getInt(c.getColumnIndexOrThrow("done")) != 0;
					e.pending = c.getInt(c.getColumnIndexOrThrow("pending")) != 0;
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
			return new ShoppingData(entries, readShopPositions(db));
		}, callback);
	}

	/** Hakt einen Eintrag von Hand ab oder öffnet ihn wieder; die Änderung wird beim Senden übertragen. */
	public void setShoppingDone(final long entryId, final boolean done) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.put("done", done ? 1 : 0);
			v.put("pending", 1);
			return dbHelper.getWritableDatabase().update(ShoppingDb.T_SHOPPING, v, "id = ?",
					new String[]{String.valueOf(entryId)});
		}, null);
	}

	// ---------------------------------------------------------------- Produktdatenbank

	/** Ein Produkt aus der lokalen Kopie der Grocy-Daten. */
	public static final class ProductRow {
		public long   id;
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
			String sql = "SELECT p.id, p.name, p.group_name, p.stock_unit, p.price_unit, p.stock_to_price, "
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
		return item;
	}

	/** Wie {@link #itemFromCursor}, dazu die Grocy-Spalten der offenen Liste. */
	private static ShoppingItem openItemFromCursor(Cursor c) {
		ShoppingItem item = itemFromCursor(c);
		int productCol = c.getColumnIndexOrThrow("g_product_id");
		if (!c.isNull(productCol)) {
			item.known         = true;
			item.productId     = c.getLong(productCol);
			item.productName   = c.getString(c.getColumnIndexOrThrow("g_name"));
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
		return v;
	}

	private static Trip tripFromCursor(Cursor c) {
		Trip trip = new Trip();
		trip.id        = c.getLong(c.getColumnIndexOrThrow("id"));
		trip.sentAt    = c.getLong(c.getColumnIndexOrThrow("sent_at"));
		trip.itemCount = c.getInt(c.getColumnIndexOrThrow("item_count"));
		trip.unitCount = c.getInt(c.getColumnIndexOrThrow("unit_count"));
		trip.complete  = c.getInt(c.getColumnIndexOrThrow("complete")) != 0;
		return trip;
	}
}
