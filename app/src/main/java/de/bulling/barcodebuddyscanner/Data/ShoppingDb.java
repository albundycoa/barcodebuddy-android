package de.bulling.barcodebuddyscanner.Data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

class ShoppingDb extends SQLiteOpenHelper {

	private static final String DB_NAME    = "shopping.db";
	private static final int    DB_VERSION = 1;

	static final String T_ITEMS = "items";
	static final String T_TRIPS = "trips";

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
				+ "error TEXT)");
		db.execSQL("CREATE INDEX items_trip ON " + T_ITEMS + " (trip_id)");
	}

	@Override
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		// Bisher gibt es nur Version 1
	}
}
