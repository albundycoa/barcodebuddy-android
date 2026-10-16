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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lokaler Speicher für die Einkaufsliste und den Verlauf. Alle Zugriffe laufen
 * nacheinander auf einem eigenen Thread, Ergebnisse kommen auf dem Main-Thread an.
 * Nach jeder Änderung werden die Listener benachrichtigt.
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

	private static final String[] ITEM_COLUMNS = {
			"id", "trip_id", "barcode", "name", "quantity", "scanned_at", "sent_count", "message", "error"
	};

	private static ShoppingRepository instance;

	public static synchronized ShoppingRepository get(Context context) {
		if (instance == null) {
			instance = new ShoppingRepository(context.getApplicationContext());
		}
		return instance;
	}

	private final ShoppingDb                       dbHelper;
	private final ExecutorService                  io        = Executors.newSingleThreadExecutor();
	private final Handler                          main      = new Handler(Looper.getMainLooper());
	private final CopyOnWriteArrayList<Listener>   listeners = new CopyOnWriteArrayList<>();

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

	public void getOpenItems(Callback<List<ShoppingItem>> callback) {
		read(() -> readItems(dbHelper.getReadableDatabase(), 0), callback);
	}

	public void setQuantity(final long itemId, final int quantity) {
		write(() -> {
			ContentValues v = new ContentValues();
			v.put("quantity", Math.max(1, quantity));
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
	 * Merkt sich den Sendefortschritt eines Artikels. Löst bewusst keine
	 * Benachrichtigung aus, damit die Liste beim Senden nicht ständig neu lädt.
	 */
	public void updateProgress(final long itemId, final int sentCount, final String message, final String error) {
		read(() -> {
			ContentValues v = new ContentValues();
			v.put("sent_count", sentCount);
			v.put("message", message);
			v.put("error", error);
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

	// ---------------------------------------------------------------- Helfer

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
		return item;
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
