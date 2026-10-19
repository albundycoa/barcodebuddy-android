package de.bulling.barcodebuddyscanner.Data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

class ShoppingDb extends SQLiteOpenHelper {

	private static final String DB_NAME    = "shopping.db";
	private static final int    DB_VERSION = 11;

	static final String T_ITEMS      = "items";
	static final String T_TRIPS      = "trips";
	static final String T_SHOPS      = "g_shops";
	static final String T_PRODUCTS   = "g_products";
	static final String T_BARCODES   = "g_barcodes";
	static final String T_LASTPRICE  = "g_lastprice";
	static final String T_SHOP_ORDER = "shop_order";
	static final String T_STOCK      = "g_stock";
	static final String T_SHOPPING   = "g_shopping";
	static final String T_UNITS      = "g_units";
	static final String T_GROUPS      = "g_groups";
	static final String T_LOCATIONS   = "g_locations";
	static final String T_GROUP_ORDER = "group_order";
	static final String T_OFF         = "off_cache";

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
				+ "scan_price REAL, "
				+ "shop_name TEXT)");
		db.execSQL("CREATE INDEX items_trip ON " + T_ITEMS + " (trip_id)");
		createGrocyTables(db);
		createShoppingTables(db);
		createOffTable(db);
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 2) {
			db.execSQL("ALTER TABLE " + T_ITEMS + " ADD COLUMN price REAL");
			db.execSQL("ALTER TABLE " + T_ITEMS + " ADD COLUMN scan_price REAL");
			db.execSQL("ALTER TABLE " + T_ITEMS + " ADD COLUMN shop_name TEXT");
			createGrocyTables(db);
		}
		if (oldVersion >= 2 && oldVersion < 5) {
			// Die Einkaufsliste ist nur eine Kopie aus Grocy und wird bei der nächsten Synchronisierung neu gefüllt
			db.execSQL("DROP TABLE IF EXISTS " + T_SHOPPING);
			createShoppingTables(db);
		}
		if (oldVersion >= 2 && oldVersion < 6) {
			// Produktgruppen kommen mit der nächsten Synchronisierung aus Grocy
			createGroupTables(db);
		}
		if (oldVersion >= 2 && oldVersion < 7) {
			// Lagerorte kommen mit der nächsten Synchronisierung aus Grocy
			createLocationTable(db);
		}
		if (oldVersion >= 2 && oldVersion < 8) {
			// Name der Einheit, in der ein Barcode gezählt wird (z. B. Pack), kommt mit der nächsten Synchronisierung
			db.execSQL("ALTER TABLE " + T_BARCODES + " ADD COLUMN scan_unit TEXT");
		}
		if (oldVersion >= 2 && oldVersion < 9) {
			// Schnellverbrauch-Menge je Produkt, kommt mit der nächsten Synchronisierung
			db.execSQL("ALTER TABLE " + T_PRODUCTS + " ADD COLUMN consume_amount REAL");
		}
		if (oldVersion >= 5 && oldVersion < 10) {
			// Von Hand geänderte Menge eines Eintrags der Einkaufsliste, bis Grocy sie übernommen hat
			db.execSQL("ALTER TABLE " + T_SHOPPING + " ADD COLUMN manual_amount REAL");
		}
		if (oldVersion < 11) {
			// Namensvorschläge von OpenFoodFacts für unbekannte Barcodes
			createOffTable(db);
		}
		if (oldVersion >= 2 && oldVersion < 3) {
			db.execSQL("ALTER TABLE " + T_ITEMS + " ADD COLUMN shop_name TEXT");
			// Die Kopie der Grocy-Daten wird bei der nächsten Synchronisierung vollständig neu gefüllt
			db.execSQL("ALTER TABLE " + T_PRODUCTS + " ADD COLUMN best_before_days INTEGER");
			db.execSQL("ALTER TABLE " + T_PRODUCTS + " ADD COLUMN tare INTEGER NOT NULL DEFAULT 0");
			db.execSQL("ALTER TABLE " + T_PRODUCTS + " ADD COLUMN group_name TEXT");
			db.execSQL("ALTER TABLE " + T_BARCODES + " ADD COLUMN exact INTEGER NOT NULL DEFAULT 0");
			db.execSQL("CREATE TABLE " + T_STOCK + " (product_id INTEGER PRIMARY KEY, amount REAL NOT NULL)");
		}
	}

	/** Antworten von OpenFoodFacts je Barcode; name NULL = dort nicht gefunden (wird später erneut gefragt). */
	private static void createOffTable(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE IF NOT EXISTS " + T_OFF + " ("
				+ "barcode TEXT PRIMARY KEY, "
				+ "name TEXT, "
				+ "checked_at INTEGER NOT NULL)");
	}

	/** Einkaufsliste aus Grocy mit lokalem Abhak-Stand und Einheitennamen. */
	private static void createShoppingTables(SQLiteDatabase db) {
		// done: Stand in Grocy; manual: Wunsch des Nutzers (1 = abgehakt, 0 = offen, NULL = keiner)
		// stock_amount: Menge in Lagereinheiten (für den Vergleich mit gescannten Mengen)
		db.execSQL("CREATE TABLE IF NOT EXISTS " + T_SHOPPING + " ("
				+ "id INTEGER PRIMARY KEY, "
				+ "list_id INTEGER, "
				+ "product_id INTEGER, "
				+ "note TEXT, "
				+ "amount REAL NOT NULL DEFAULT 1, "
				+ "qu_id INTEGER, "
				+ "stock_amount REAL NOT NULL DEFAULT 1, "
				+ "done INTEGER NOT NULL DEFAULT 0, "
				+ "manual INTEGER, "
				+ "manual_amount REAL)");
		db.execSQL("CREATE TABLE IF NOT EXISTS " + T_UNITS + " ("
				+ "id INTEGER PRIMARY KEY, "
				+ "name TEXT NOT NULL)");
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
				+ "price_unit TEXT, "
				+ "best_before_days INTEGER, "
				+ "tare INTEGER NOT NULL DEFAULT 0, "
				+ "group_name TEXT, "
				+ "consume_amount REAL)");
		// stock_per_scan: Wie viele Lagereinheiten ein Scan dieses Barcodes bucht;
		// exact: Der Barcode hat in Grocy eine eigene Menge (sonst entscheidet Barcode Buddy)
		db.execSQL("CREATE TABLE " + T_BARCODES + " ("
				+ "barcode TEXT PRIMARY KEY, "
				+ "product_id INTEGER NOT NULL, "
				+ "stock_per_scan REAL NOT NULL DEFAULT 1, "
				+ "exact INTEGER NOT NULL DEFAULT 0, "
				+ "shop_id INTEGER, "
				+ "scan_unit TEXT)");
		// Aktueller Bestand je Produkt in Lagereinheiten
		db.execSQL("CREATE TABLE " + T_STOCK + " ("
				+ "product_id INTEGER PRIMARY KEY, "
				+ "amount REAL NOT NULL)");
		// price: letzter Einkaufspreis je Lagereinheit
		db.execSQL("CREATE TABLE " + T_LASTPRICE + " ("
				+ "product_id INTEGER PRIMARY KEY, "
				+ "price REAL NOT NULL)");
		db.execSQL("CREATE TABLE " + T_SHOP_ORDER + " ("
				+ "shop_id INTEGER PRIMARY KEY, "
				+ "position INTEGER NOT NULL)");
		createGroupTables(db);
		createLocationTable(db);
	}

	/** Lagerorte aus Grocy (Auswahl beim Anlegen eines Produkts). */
	private static void createLocationTable(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE IF NOT EXISTS " + T_LOCATIONS + " ("
				+ "id INTEGER PRIMARY KEY, "
				+ "name TEXT NOT NULL)");
	}

	/** Produktgruppen aus Grocy und die vom Nutzer gewählte Reihenfolge (nach Name, die Namen sind eindeutig). */
	private static void createGroupTables(SQLiteDatabase db) {
		db.execSQL("CREATE TABLE IF NOT EXISTS " + T_GROUPS + " ("
				+ "id INTEGER PRIMARY KEY, "
				+ "name TEXT NOT NULL)");
		db.execSQL("CREATE TABLE IF NOT EXISTS " + T_GROUP_ORDER + " ("
				+ "group_name TEXT PRIMARY KEY, "
				+ "position INTEGER NOT NULL)");
	}
}
