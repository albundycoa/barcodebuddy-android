package de.bulling.barcodebuddyscanner.Helper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Produkte und Produktgruppen für die Uhr (Data Layer, Pfad /bbs/catalog), damit sie dort Einträge zur Einkaufsliste hinzufügen kann,
 * ohne selbst mit Grocy zu sprechen. Kurze Schlüssel, damit auch viele Produkte weit unter der Grenze von 100 KB bleiben.
 *
 * <pre>
 * { "p": [ { "id": 7, "n": "Cola", "g": "Getränk" } ], "g": ["Fleisch", "Getränk"] }
 * </pre>
 *
 * Die Gruppen stehen in der vom Nutzer gewählten Reihenfolge. Reine Logik, getestet.
 */
public final class WearCatalogJson {

	/** Ein Produkt für die Auswahl auf der Uhr. */
	public static final class Product {
		public final long   id;
		public final String name;
		public final String group;

		public Product(long id, String name, String group) {
			this.id    = id;
			this.name  = name;
			this.group = group;
		}
	}

	private WearCatalogJson() {
	}

	public static String build(List<Product> products, List<String> groups) {
		JsonObject root = new JsonObject();
		JsonArray p = new JsonArray();
		for (Product product : products) {
			if (product.name == null || product.name.trim().isEmpty()) {
				continue;
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", product.id);
			o.addProperty("n", product.name);
			if (product.group != null && !product.group.isEmpty()) {
				o.addProperty("g", product.group);
			}
			p.add(o);
		}
		root.add("p", p);
		JsonArray g = new JsonArray();
		for (String group : groups) {
			if (group != null && !group.trim().isEmpty()) {
				g.add(group);
			}
		}
		root.add("g", g);
		return root.toString();
	}
}
