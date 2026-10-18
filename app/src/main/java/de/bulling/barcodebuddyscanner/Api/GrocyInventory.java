package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Helper.PurchaseSender;

/**
 * Bestand in Grocy abfragen und per Inventur auf eine gezählte Menge setzen (die Aufrufe blockieren, also nicht auf
 * dem Main-Thread verwenden).
 */
public final class GrocyInventory {

	private GrocyInventory() {
	}

	/** Der aktuelle Bestand je Produkt in Lagereinheiten (Produkte ohne Bestand fehlen). */
	public static Map<Long, Double> loadStock(GrocyClient client) throws IOException {
		JsonElement response = client.get("objects/stock");
		if (response == null || !response.isJsonArray()) {
			throw new IOException("Unerwartete Antwort");
		}
		Map<Long, Double> stock = new HashMap<>();
		try {
			for (JsonElement e : response.getAsJsonArray()) {
				JsonObject row = e.getAsJsonObject();
				long product = row.get("product_id").getAsLong();
				double amount = row.get("amount").getAsDouble();
				Double sum = stock.get(product);
				stock.put(product, (sum == null ? 0 : sum) + amount);
			}
		} catch (RuntimeException e) {
			throw new IOException("Unerwartete Antwort", e);
		}
		return stock;
	}

	/**
	 * Setzt den Bestand des Produkts auf die gezählte Menge. Wird der Bestand größer, verlangt Grocy ein
	 * Mindesthaltbarkeitsdatum; es gilt wie beim Einkauf die Standard-Haltbarkeit des Produkts.
	 */
	public static void setStock(GrocyClient client, long productId, double newAmount, boolean increase,
	                            Integer bestBeforeDays) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("new_amount", newAmount);
		if (increase) {
			body.addProperty("best_before_date", PurchaseSender.bestBeforeDate(bestBeforeDays));
		}
		client.post("stock/products/" + productId + "/inventory", body.toString());
	}
}
