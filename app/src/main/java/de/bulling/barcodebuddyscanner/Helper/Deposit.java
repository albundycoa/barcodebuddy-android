package de.bulling.barcodebuddyscanner.Helper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;

/**
 * Pfand je Produktgruppe. Der Betrag gilt je Lagereinheit (z. B. je Flasche) und wird nur in der App mitgerechnet:
 * in der Liste, im Verlauf und im Beleg. Auf den Produktpreis in Grocy oder Barcode Buddy wird nie etwas aufgeschlagen.
 * Gespeichert wird eine Zuordnung von Grocy-Produktgruppe (Nummer) zu Betrag.
 */
public final class Deposit {

	private Deposit() {
	}

	/** {"12":0.25,"15":0.08} -> {12=0.25, 15=0.08}; Ungültiges wird übersprungen, leere Eingabe ergibt eine leere Zuordnung. */
	public static Map<Long, Double> parse(String json) {
		Map<Long, Double> rules = new LinkedHashMap<>();
		if (json == null || json.trim().isEmpty()) {
			return rules;
		}
		try {
			JsonElement root = JsonParser.parseString(json);
			if (!root.isJsonObject()) {
				return rules;
			}
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
				try {
					long group = Long.parseLong(e.getKey());
					double amount = e.getValue().getAsDouble();
					if (amount > 0 && !Double.isNaN(amount) && !Double.isInfinite(amount)) {
						rules.put(group, amount);
					}
				} catch (RuntimeException ignored) {
					// ein kaputter Eintrag soll die anderen nicht mitnehmen
				}
			}
		} catch (RuntimeException e) {
			return new LinkedHashMap<>();
		}
		return rules;
	}

	public static String serialize(Map<Long, Double> rules) {
		JsonObject root = new JsonObject();
		for (Map.Entry<Long, Double> e : rules.entrySet()) {
			if (e.getValue() != null && e.getValue() > 0) {
				root.addProperty(String.valueOf(e.getKey()), e.getValue());
			}
		}
		return root.toString();
	}

	/**
	 * Setzt bei jedem Artikel das Pfand je Scan: Betrag der Produktgruppe mal Lagereinheiten je Scan (ein Sechserträger
	 * mit 6 Flaschen zählt also sechsfach). Artikel ohne bekanntes Produkt oder ohne Regel bekommen keins.
	 */
	public static void apply(List<ShoppingItem> items, Map<Long, Double> rules) {
		for (ShoppingItem item : items) {
			item.deposit = perScan(item, rules);
		}
	}

	/** Pfand für einen Scan dieses Artikels, null = keins. */
	public static Double perScan(ShoppingItem item, Map<Long, Double> rules) {
		if (!item.known || item.groupId == null) {
			return null;
		}
		Double amount = rules.get(item.groupId);
		if (amount == null || amount <= 0) {
			return null;
		}
		return amount * (item.stockPerScan > 0 ? item.stockPerScan : 1);
	}
}
