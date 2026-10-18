package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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

	/** Setzt das Produkt mit der Menge (in Lagereinheiten) auf die Einkaufsliste. */
	public static void addToShoppingList(GrocyClient client, long productId, double amount) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("product_id", productId);
		body.addProperty("list_id", 1);
		body.addProperty("product_amount", amount);
		client.post("stock/shoppinglist/add-product", body.toString());
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
