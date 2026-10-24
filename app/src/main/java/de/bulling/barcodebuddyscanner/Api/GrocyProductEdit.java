package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ein vorhandenes Produkt in Grocy ändern und seine Barcodes verwalten (Barcode, Geschäft, Mengeneinheit, Menge wie in Grocy).
 * Die Aufrufe blockieren, gehören also auf einen Hintergrund-Thread.
 */
public final class GrocyProductEdit {

	private GrocyProductEdit() {
	}

	/** Die Felder eines Produkts, die sich ändern lassen. 0 bei einer Nummer = nicht gesetzt. */
	public static final class Product {
		public long   id;
		public String name;
		public long   groupId;
		public long   locationId;
		public long   shopId;
		/** Standard-Einkaufseinheit */
		public long   quPurchase;
		/** Lagereinheit (nur zur Anzeige: Grocy lässt sie nicht mehr ändern, sobald Bestand gebucht wurde) */
		public long   quStock;
		public double minStock;
		/** Standard-Haltbarkeit in Tagen, -1 = unbegrenzt, 0 = keine */
		public int    bestBeforeDays;
	}

	/** Ein Barcode eines Produkts. */
	public static final class Barcode {
		/** Nummer des Eintrags, 0 bei einem neuen Barcode */
		public long   id;
		public long   productId;
		public String barcode;
		public long   shopId;
		public long   quId;
		/** Menge in der Einheit des Barcodes, null = keine (dann gilt 1) */
		public Double amount;
	}

	public static Product loadProduct(GrocyClient client, long productId) throws IOException {
		JsonElement response = client.get("objects/products/" + productId);
		if (response == null || !response.isJsonObject()) {
			throw new IOException("unerwartete Antwort");
		}
		return parseProduct(response.getAsJsonObject());
	}

	static Product parseProduct(JsonObject o) {
		Product p = new Product();
		p.id             = (long) number(o, "id");
		p.name           = text(o, "name");
		p.groupId        = (long) number(o, "product_group_id");
		p.locationId     = (long) number(o, "location_id");
		p.shopId         = (long) number(o, "shopping_location_id");
		p.quPurchase     = (long) number(o, "qu_id_purchase");
		p.quStock        = (long) number(o, "qu_id_stock");
		p.minStock       = number(o, "min_stock_amount");
		p.bestBeforeDays = (int) number(o, "default_best_before_days");
		return p;
	}

	/**
	 * Körper für das Ändern. Gruppe und Geschäft sind in Grocy leer erlaubt, deshalb geht dann ein JSON-null mit; die übrigen
	 * Felder werden immer geschickt.
	 */
	static JsonObject productBody(Product p) {
		JsonObject body = new JsonObject();
		body.addProperty("name", p.name);
		if (p.groupId > 0) {
			body.addProperty("product_group_id", p.groupId);
		} else {
			body.add("product_group_id", com.google.gson.JsonNull.INSTANCE);
		}
		if (p.locationId > 0) {
			body.addProperty("location_id", p.locationId);
		}
		if (p.shopId > 0) {
			body.addProperty("shopping_location_id", p.shopId);
		} else {
			body.add("shopping_location_id", com.google.gson.JsonNull.INSTANCE);
		}
		if (p.quPurchase > 0) {
			body.addProperty("qu_id_purchase", p.quPurchase);
		}
		body.addProperty("min_stock_amount", p.minStock);
		body.addProperty("default_best_before_days", p.bestBeforeDays);
		return body;
	}

	public static void saveProduct(GrocyClient client, Product p) throws IOException {
		client.put("objects/products/" + p.id, productBody(p).toString());
	}

	// ---------------------------------------------------------------- Barcodes

	public static List<Barcode> loadBarcodes(GrocyClient client, long productId) throws IOException {
		return parseBarcodes(client.get("objects/product_barcodes?query%5B%5D=product_id%3D" + productId));
	}

	static List<Barcode> parseBarcodes(JsonElement rows) {
		List<Barcode> list = new ArrayList<>();
		if (rows == null || !rows.isJsonArray()) {
			return list;
		}
		for (JsonElement e : rows.getAsJsonArray()) {
			if (!e.isJsonObject() || !e.getAsJsonObject().has("barcode")) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			Barcode b = new Barcode();
			b.id        = (long) number(o, "id");
			b.productId = (long) number(o, "product_id");
			b.barcode   = text(o, "barcode");
			b.shopId    = (long) number(o, "shopping_location_id");
			b.quId      = (long) number(o, "qu_id");
			String amount = text(o, "amount");
			b.amount    = amount == null || amount.isEmpty() ? null : number(o, "amount");
			list.add(b);
		}
		return list;
	}

	static JsonObject barcodeBody(Barcode b) {
		JsonObject body = new JsonObject();
		body.addProperty("product_id", b.productId);
		body.addProperty("barcode", b.barcode);
		if (b.quId > 0) {
			body.addProperty("qu_id", b.quId);
		} else {
			body.add("qu_id", com.google.gson.JsonNull.INSTANCE);
		}
		if (b.amount != null) {
			body.addProperty("amount", b.amount);
		} else {
			body.add("amount", com.google.gson.JsonNull.INSTANCE);
		}
		if (b.shopId > 0) {
			body.addProperty("shopping_location_id", b.shopId);
		} else {
			body.add("shopping_location_id", com.google.gson.JsonNull.INSTANCE);
		}
		return body;
	}

	/** Legt einen Barcode an (id = 0) oder ändert ihn. */
	public static void saveBarcode(GrocyClient client, Barcode b) throws IOException {
		if (b.id > 0) {
			client.put("objects/product_barcodes/" + b.id, barcodeBody(b).toString());
		} else {
			client.post("objects/product_barcodes", barcodeBody(b).toString());
		}
	}

	public static void deleteBarcode(GrocyClient client, long barcodeId) throws IOException {
		client.delete("objects/product_barcodes/" + barcodeId);
	}

	private static String text(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e == null || e.isJsonNull() ? null : e.getAsString();
	}

	private static double number(JsonObject o, String key) {
		String s = text(o, key);
		if (s == null || s.isEmpty()) {
			return 0;
		}
		try {
			return Double.parseDouble(s);
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
