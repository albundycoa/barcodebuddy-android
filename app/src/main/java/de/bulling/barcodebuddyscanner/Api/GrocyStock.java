package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Helper.StockOverview;

import de.bulling.barcodebuddyscanner.Helper.Texts;
import de.bulling.barcodebuddyscanner.R;

/**
 * Bestandsabfragen und einfache Buchungen in Grocy: aktueller Bestand eines Produkts, Verbrauchen, Einkaufsliste und
 * Haltbarkeiten (die Aufrufe blockieren, also nicht auf dem Main-Thread verwenden).
 */
public final class GrocyStock {

	private GrocyStock() {
	}

	/** Der aktuelle Stand eines Produkts direkt aus Grocy. */
	public static final class Live {
		/** Bestand in Lagereinheiten */
		public double amount;
		/** Davon angebrochen */
		public double opened;
		/** Nächstes Mindesthaltbarkeitsdatum "yyyy-MM-dd", null = keines bekannt oder unbegrenzt */
		public String nextDue;
	}

	/** Eine Charge im Bestand mit ihrem Mindesthaltbarkeitsdatum. */
	public static final class Batch {
		public long   productId;
		public double amount;
		/** "yyyy-MM-dd" */
		public String due;
	}

	public static Live loadProduct(GrocyClient client, long productId) throws IOException {
		JsonElement response = client.get("stock/products/" + productId);
		if (response == null || !response.isJsonObject()) {
			throw new IOException(Texts.get(R.string.err_unexpected_response));
		}
		JsonObject o = response.getAsJsonObject();
		Live live = new Live();
		live.amount  = number(o, "stock_amount");
		live.opened  = number(o, "stock_amount_opened");
		live.nextDue = due(text(o, "next_due_date"));
		return live;
	}

	/** Verbraucht die Menge (in Lagereinheiten) vom Bestand. */
	public static void consume(GrocyClient client, long productId, double amount) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("amount", amount);
		body.addProperty("transaction_type", "consume");
		body.addProperty("spoiled", false);
		client.post("stock/products/" + productId + "/consume", body.toString());
	}

	/** Wie {@link #consume}, liefert aber die Nummer der Buchung, mit der sie sich zurücknehmen lässt (null = unbekannt). */
	public static String consumeBooking(GrocyClient client, long productId, double amount) throws IOException {
		return transactionId(client.post("stock/products/" + productId + "/consume", consumeBody(amount).toString()));
	}

	/** Öffnet die Menge (in Lagereinheiten); liefert die Buchungsnummer zum Zurücknehmen (null = unbekannt). */
	public static String open(GrocyClient client, long productId, double amount) throws IOException {
		return transactionId(client.post("stock/products/" + productId + "/open", openBody(amount).toString()));
	}

	/** Nimmt eine Buchung zurück (Grocy: "Undo" im Bestandsjournal). */
	public static void undo(GrocyClient client, String transactionId) throws IOException {
		client.post("stock/transactions/" + transactionId + "/undo", "{}");
	}

	static JsonObject consumeBody(double amount) {
		JsonObject body = new JsonObject();
		body.addProperty("amount", amount);
		body.addProperty("transaction_type", "consume");
		body.addProperty("spoiled", false);
		return body;
	}

	static JsonObject openBody(double amount) {
		JsonObject body = new JsonObject();
		body.addProperty("amount", amount);
		return body;
	}

	/** Grocy antwortet mit den gebuchten Zeilen; alle teilen sich eine "transaction_id". */
	static String transactionId(JsonElement response) {
		if (response == null || !response.isJsonArray() || response.getAsJsonArray().size() == 0) {
			return null;
		}
		JsonElement first = response.getAsJsonArray().get(0);
		if (!first.isJsonObject()) {
			return null;
		}
		return text(first.getAsJsonObject(), "transaction_id");
	}

	/** Alle Chargen im Bestand (auch ohne Haltbarkeitsdatum) mit Menge, Preis je Lagereinheit und geöffnet-Kennzeichen. */
	public static List<StockOverview.StockRow> loadStockRows(GrocyClient client) throws IOException {
		JsonElement response = client.get("objects/stock");
		if (response == null || !response.isJsonArray()) {
			throw new IOException(Texts.get(R.string.err_unexpected_response));
		}
		List<StockOverview.StockRow> rows = new ArrayList<>();
		try {
			for (JsonElement e : response.getAsJsonArray()) {
				JsonObject row = e.getAsJsonObject();
				String price = text(row, "price");
				Double priceValue = null;
				if (price != null && !price.isEmpty()) {
					try {
						priceValue = Double.parseDouble(price);
					} catch (NumberFormatException ignored) {
						// Preis unbekannt
					}
				}
				rows.add(new StockOverview.StockRow(row.get("product_id").getAsLong(), number(row, "amount"),
						number(row, "open") != 0, priceValue, due(text(row, "best_before_date"))));
			}
		} catch (RuntimeException e) {
			throw new IOException(Texts.get(R.string.err_unexpected_response), e);
		}
		return rows;
	}

	/**
	 * Produkteinstellungen für die Bestandsübersicht: Schnellverbrauchs- und Schnelleröffnungsmenge (nur, wenn sie in der
	 * Lagereinheit angegeben sind, sonst 1), Öffnen erlaubt, Tara-Gewicht.
	 */
	public static Map<Long, StockOverview.Product> loadProductSetups(GrocyClient client) throws IOException {
		JsonElement response = client.get("objects/products");
		if (response == null || !response.isJsonArray()) {
			throw new IOException(Texts.get(R.string.err_unexpected_response));
		}
		Map<Long, StockOverview.Product> result = new HashMap<>();
		try {
			for (JsonElement e : response.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				StockOverview.Product p = new StockOverview.Product();
				p.id   = o.get("id").getAsLong();
				p.name = text(o, "name");
				String stockUnit   = text(o, "qu_id_stock");
				String consumeUnit = text(o, "qu_id_consume");
				boolean sameUnit = consumeUnit == null || consumeUnit.isEmpty() || consumeUnit.equals(stockUnit);
				double quick = number(o, "quick_consume_amount");
				double quickOpen = number(o, "quick_open_amount");
				p.consumeAmount = sameUnit && quick > 0 ? quick : 1;
				p.openAmount    = sameUnit && quickOpen > 0 ? quickOpen : 1;
				p.canOpen       = number(o, "disable_open") == 0;
				p.tare          = number(o, "enable_tare_weight_handling") != 0;
				p.quStock       = (long) number(o, "qu_id_stock");
				p.quPurchase    = (long) number(o, "qu_id_purchase");
				result.put(p.id, p);
			}
		} catch (RuntimeException e) {
			throw new IOException(Texts.get(R.string.err_unexpected_response), e);
		}
		return result;
	}

	/** Setzt das Produkt mit der Menge (in Lagereinheiten) auf die Einkaufsliste. */
	public static void addToShoppingList(GrocyClient client, long productId, double amount) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("product_id", productId);
		body.addProperty("list_id", 1);
		body.addProperty("product_amount", amount);
		client.post("stock/shoppinglist/add-product", body.toString());
	}

	/** Ein vorhandener Eintrag der Einkaufsliste, zu dem eine neue Menge dazugezählt werden kann. */
	static final class ListEntry {
		final long   id;
		final double amount;

		ListEntry(long id, double amount) {
			this.id     = id;
			this.amount = amount;
		}
	}

	/**
	 * Sucht in den Zeilen der Einkaufsliste einen Eintrag desselben Produkts in derselben Einheit. Nur dann lassen sich die
	 * Mengen einfach addieren (wie in Grocy), sonst entsteht eine eigene Zeile.
	 */
	static ListEntry findMergeable(JsonElement rows, long quId) {
		if (rows == null || !rows.isJsonArray()) {
			return null;
		}
		for (JsonElement e : rows.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			if ((long) number(o, "qu_id") == quId && o.has("id")) {
				return new ListEntry(o.get("id").getAsLong(), number(o, "amount"));
			}
		}
		return null;
	}

	static JsonObject newListEntryBody(long productId, double amount, long quId) {
		JsonObject body = new JsonObject();
		body.addProperty("product_id", productId);
		body.addProperty("amount", amount);
		body.addProperty("qu_id", quId);
		body.addProperty("shopping_list_id", 1);
		return body;
	}

	/**
	 * Offene Einträge der Standardeinkaufsliste je Produktnummer (erledigte zählen nicht). Einheitennamen kommen aus
	 * {@code unitNames}, fehlende bleiben leer.
	 */
	static Map<Long, List<StockOverview.ListRow>> parseShoppingRows(JsonElement rows, Map<Long, String> unitNames) {
		Map<Long, List<StockOverview.ListRow>> result = new HashMap<>();
		if (rows == null || !rows.isJsonArray()) {
			return result;
		}
		for (JsonElement e : rows.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			long listId = o.has("shopping_list_id") ? (long) number(o, "shopping_list_id") : 1;
			if (!o.has("id") || !o.has("product_id") || number(o, "done") != 0 || (listId != 1 && listId != 0)) {
				continue;
			}
			long product = (long) number(o, "product_id");
			long quId = (long) number(o, "qu_id");
			List<StockOverview.ListRow> list = result.get(product);
			if (list == null) {
				list = new ArrayList<>();
				result.put(product, list);
			}
			list.add(new StockOverview.ListRow(o.get("id").getAsLong(), number(o, "amount"), quId, unitNames.get(quId)));
		}
		return result;
	}

	/** Lädt die offenen Einträge der Einkaufsliste live aus Grocy. */
	public static Map<Long, List<StockOverview.ListRow>> loadShoppingRows(GrocyClient client, Map<Long, String> unitNames)
			throws IOException {
		return parseShoppingRows(client.get("objects/shopping_list"), unitNames);
	}

	/** Entfernt einen Eintrag von der Einkaufsliste. */
	public static void removeFromShoppingList(GrocyClient client, long entryId) throws IOException {
		client.delete("objects/shopping_list/" + entryId);
	}

	/**
	 * Legt fest, wie viel von dem Produkt in der gewählten Einheit auf der Einkaufsliste steht, so wie Grocys eigenes Formular:
	 * Die Menge steht in der gewählten Einheit. Gibt es schon einen Eintrag mit derselben Einheit, wird seine Menge auf den
	 * neuen Wert gesetzt (nicht addiert, die Maske zeigt ja die bisherige Menge), sonst entsteht ein neuer Eintrag.
	 */
	public static void addToShoppingList(GrocyClient client, long productId, double amount, long quId) throws IOException {
		putOnShoppingList(client, productId, amount, quId, false);
	}

	/** Wie {@link #addToShoppingList}, zählt die Menge aber zu einem vorhandenen Eintrag derselben Einheit dazu ("noch eins"). */
	public static void addMoreToShoppingList(GrocyClient client, long productId, double amount, long quId) throws IOException {
		putOnShoppingList(client, productId, amount, quId, true);
	}

	private static void putOnShoppingList(GrocyClient client, long productId, double amount, long quId, boolean add) throws IOException {
		JsonElement existing = client.get("objects/shopping_list?query%5B%5D=product_id%3D" + productId
				+ "&query%5B%5D=shopping_list_id%3D1");
		ListEntry merge = findMergeable(existing, quId);
		if (merge != null) {
			JsonObject body = new JsonObject();
			body.addProperty("amount", add ? merge.amount + amount : amount);
			client.put("objects/shopping_list/" + merge.id, body.toString());
		} else {
			client.post("objects/shopping_list", newListEntryBody(productId, amount, quId).toString());
		}
	}

	/** Alle Chargen im Bestand, Chargen ohne Haltbarkeitsdatum oder mit unbegrenztem Datum fehlen. */
	public static List<Batch> loadBatches(GrocyClient client) throws IOException {
		JsonElement response = client.get("objects/stock");
		if (response == null || !response.isJsonArray()) {
			throw new IOException(Texts.get(R.string.err_unexpected_response));
		}
		List<Batch> batches = new ArrayList<>();
		try {
			for (JsonElement e : response.getAsJsonArray()) {
				JsonObject row = e.getAsJsonObject();
				String due = due(text(row, "best_before_date"));
				if (due == null) {
					continue;
				}
				Batch batch = new Batch();
				batch.productId = row.get("product_id").getAsLong();
				batch.amount    = number(row, "amount");
				batch.due       = due;
				batches.add(batch);
			}
		} catch (RuntimeException e) {
			throw new IOException(Texts.get(R.string.err_unexpected_response), e);
		}
		return batches;
	}

	/** Grocy nutzt 2999-12-31 für "unbegrenzt haltbar". */
	static String due(String date) {
		if (date == null || date.length() < 10 || date.startsWith("2999")) {
			return null;
		}
		return date.substring(0, 10);
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
