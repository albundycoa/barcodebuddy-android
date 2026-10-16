package de.bulling.barcodebuddyscanner.Data;

import android.content.ContentValues;

import java.util.ArrayList;
import java.util.List;

/**
 * Stammdaten aus Grocy, fertig für die lokale Datenbank aufbereitet.
 */
public final class GrocyData {
	public final List<ContentValues> shops      = new ArrayList<>();
	public final List<ContentValues> products   = new ArrayList<>();
	public final List<ContentValues> barcodes   = new ArrayList<>();
	public final List<ContentValues> lastPrices = new ArrayList<>();
}
