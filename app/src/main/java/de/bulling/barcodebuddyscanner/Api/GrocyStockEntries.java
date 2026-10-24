package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bestandseinträge (Chargen) eines Produkts in Grocy: lesen, ändern und entfernen. Die Aufrufe blockieren, gehören also auf einen
 * Hintergrund-Thread.
 */
public final class GrocyStockEntries {

	/** Grocy nutzt dieses Datum für "unbegrenzt haltbar". */
	public static final String NO_DUE_DATE = "2999-12-31";

	private GrocyStockEntries() {
	}

	/** Ein Bestandseintrag. Mengen in Lagereinheiten, Preis je Lagereinheit. */
	public static final class Entry {
		/** Nummer des Eintrags (für Änderungen) */
		public long    id;
		/** Kennung der Charge, so nennt sie Grocy beim Verbrauchen */
		public String  stockId;
		public long    productId;
		public double  amount;
		/** "yyyy-MM-dd", "2999-12-31" = unbegrenzt */
		public String  due;
		public String  purchased;
		public Double  price;
		public boolean open;
		public long    locationId;
		public long    shopId;
		public String  note;

		public boolean hasNoDueDate() {
			return due == null || due.startsWith("2999");
		}
	}

	/** Alle Einträge eines Produkts, der früheste Ablauf zuerst (wie Grocy sie verbraucht). */
	public static List<Entry> load(GrocyClient client, long productId) throws IOException {
		return parse(client.get("objects/stock?query%5B%5D=product_id%3D" + productId));
	}

	static List<Entry> parse(JsonElement rows) {
		List<Entry> entries = new ArrayList<>();
		if (rows == null || !rows.isJsonArray()) {
			return entries;
		}
		for (JsonElement e : rows.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			if (!o.has("id")) {
				continue;
			}
			Entry entry = new Entry();
			entry.id         = (long) number(o, "id");
			entry.stockId    = text(o, "stock_id");
			entry.productId  = (long) number(o, "product_id");
			entry.amount     = number(o, "amount");
			entry.due        = text(o, "best_before_date");
			entry.purchased  = text(o, "purchased_date");
			String price     = text(o, "price");
			entry.price      = price == null || price.isEmpty() ? null : number(o, "price");
			entry.open       = number(o, "open") != 0;
			entry.locationId = (long) number(o, "location_id");
			entry.shopId     = (long) number(o, "shopping_location_id");
			entry.note       = text(o, "note");
			if (entry.amount > 0) {
				entries.add(entry);
			}
		}
		Collections.sort(entries, (a, b) -> {
			String da = a.due == null ? "9999" : a.due;
			String db = b.due == null ? "9999" : b.due;
			int c = da.compareTo(db);
			return c != 0 ? c : Long.compare(a.id, b.id);
		});
		return entries;
	}

	/**
	 * Körper für das Ändern eines Eintrags. Grocy setzt weggelassene Felder auf Standardwerte (zum Beispiel das Datum auf heute),
	 * deshalb werden immer alle Felder geschickt.
	 */
	static JsonObject editBody(Entry entry) {
		JsonObject body = new JsonObject();
		body.addProperty("amount", entry.amount);
		body.addProperty("best_before_date", entry.due == null || entry.due.isEmpty() ? NO_DUE_DATE : entry.due);
		if (entry.price != null) {
			body.addProperty("price", entry.price);
		}
		body.addProperty("open", entry.open);
		if (entry.locationId > 0) {
			body.addProperty("location_id", entry.locationId);
		}
		if (entry.shopId > 0) {
			body.addProperty("shopping_location_id", entry.shopId);
		}
		if (entry.purchased != null && !entry.purchased.isEmpty()) {
			body.addProperty("purchased_date", entry.purchased);
		}
		return body;
	}

	/** Speichert die Änderungen an einem Eintrag. */
	public static void update(GrocyClient client, Entry entry) throws IOException {
		client.put("stock/entry/" + entry.id, editBody(entry).toString());
	}

	/**
	 * Entfernt einen Eintrag aus dem Bestand. Grocy kennt dafür nur das Verbrauchen genau dieser Charge; die Buchung lässt sich
	 * mit der zurückgegebenen Nummer wieder zurücknehmen (null = unbekannt).
	 */
	public static String remove(GrocyClient client, Entry entry) throws IOException {
		JsonObject body = GrocyStock.consumeBody(entry.amount);
		body.addProperty("stock_entry_id", entry.stockId);
		body.addProperty("exact_amount", true);
		return GrocyStock.transactionId(client.post("stock/products/" + entry.productId + "/consume", body.toString()));
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
