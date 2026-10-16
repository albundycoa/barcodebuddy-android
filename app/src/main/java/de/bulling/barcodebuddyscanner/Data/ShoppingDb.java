package de.bulling.barcodebuddyscanner.Data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

class ShoppingDb extends SQLiteOpenHelper {

	private static final String DB_NAME    = "shopping.db";
	private static final int    DB_VERSION = 2;

	static final String T_ITEMS      = "items";
	static final String T_TRIPS      = "trips";
	static final String T_SHOPS      = "g_shops";
	static final String T_PRODUCTS   = "g_products";
	static final String T_BARCODES   = "g_barcodes";
	static final String T_LASTPRICE  = "g_lastprice";
	static final String T_SHOP_ORDER = "shop_order";

	ShoppingDb(Context context) {
		super(context, DB_NAME, null, DB_VERSION);
	}

	@Override
	public void onCreate(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + T_TRIPS + " ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "sent_at INTEGER NOT NULL, "
				+ "item_count INTEGER NOT NULL, "
				+ "unit_count INTEGER NOT NULL, "
				+ "complete INTEGER NOT NULL)");
		db.execSQL("CREATE TABLE " + T_ITEMS + " ("
				+ "id INTEGER PRIMARY KEY AUTOINCREMENT, "
				+ "trip_id INTEGER NOT NULL DEFAULT 0, "
				+ "barcode TEXT NOT NULL, "
				+ "name TEXT, "
				+ "quantity INTEGER NOT NULL, "
				+ "scanned_at INTEGER NOT NULL, "
				+ "sent_count INTEGER NOT NULL DEFAULT 0, "
				+ "message TEXT, "
				+ "error TEXT, "
				+ "price REAL, "
				+ "scan_price REAL)");
		db.execSQL("CREATE INDEX items_trip ON " + T_ITEMS + " (trip_id)");
		createGrocyTables(db);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 2) {
			db.execSQL("ALTER TABLE " + T_ITEMS + " ADD COLUMN price REAL");
			db.execSQL("ALTER TABLE " + T_ITEMS + " ADD COLUMN scan_price REAL");
			createGrocyTables(db);
		}
	}

	/**
	 * Lokale Kopie der Grocy-Stammdaten, damit die Liste auch ohne Verbindung
	 * zum Server (z. B. im Laden) Namen, Geschäfte und Preise anzeigen kann.
	 */
	private static void createGrocyTables(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE " + T_SHOPS + " ("
				+ "id INTEGER PRIMARY KEY, "
				+ "name TEXT NOT NULL)");
		// stock_to_price: Faktor von der Lagereinheit in die Preiseinheit (z. B. Stück -> Pack = 0,125)
		db.execSQL("CREATE TABLE " + T_PRODUCTS + " ("
				+ "id INTEGER PRIMARY KEY, "
				+ "name TEXT NOT NULL, "
				+ "shop_id INTEGER, "
				+ "stock_to_price REAL NOT NULL DEFAULT 1, "
				+ "stock_unit TEXT, "
				+ "price_unit TEXT)");
		// stock_per_scan: Wie viele Lagereinheiten ein Scan dieses Barcodes bucht
		db.execSQL("CREATE TABLE " + T_BARCODES + " ("
				+ "barcode TEXT PRIMARY KEY, "
				+ "product_id INTEGER NOT NULL, "
				+ "stock_per_scan REAL NOT NULL DEFAULT 1, "
				+ "shop_id INTEGER)");
		// price: letzter Einkaufspreis je Lagereinheit
		db.execSQL("CREATE TABLE " + T_LASTPRICE + " ("
				+ "product_id INTEGER PRIMARY KEY, "
				+ "price REAL NOT NULL)");
		db.execSQL("CREATE TABLE " + T_SHOP_ORDER + " ("
				+ "shop_id INTEGER PRIMARY KEY, "
				+ "position INTEGER NOT NULL)");
	}
}
