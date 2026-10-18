package de.bulling.barcodebuddyscanner.Api;

import de.bulling.barcodebuddyscanner.R;
import de.bulling.barcodebuddyscanner.Helper.Texts;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Legt Produkte, Barcodes und produktspezifische Mengeneinheiten-Umrechnungen in Grocy an (die Aufrufe
 * blockieren, also nicht auf dem Main-Thread verwenden).
 */
public final class GrocyProducts {

	private GrocyProducts() {
	}

	/** 1 [from] = factor [to], nur für dieses Produkt. */
	public static final class Conversion {
		public final long   from;
		public final long   to;
		public final double factor;

		public Conversion(long from, long to, double factor) {
			this.from   = from;
			this.to     = to;
			this.factor = factor;
		}
	}

	/** Die Pflichtangaben eines neuen Produkts. */
	public static final class Spec {
		public String name;
		public long   locationId;
		public Long   shopId;
		public Long   groupId;
		public long   quStock;
		public long   quPurchase;
		public long   quConsume;
		public long   quPrice;
		/** Barcode, der dem Produkt zugeordnet wird (leer = keiner) */
		public String barcode;
		public final List<Conversion> conversions = new ArrayList<>();
	}

	public static final class Result {
		public final long   productId;
		/** Hinweis, wenn das Produkt angelegt wurde, aber eine Umrechnung nicht gespeichert werden konnte */
		public final String warning;

		Result(long productId, String warning) {
			this.productId = productId;
			this.warning   = warning;
		}
	}

	/**
	 * Legt das Produkt an und hängt Barcode (mit dem Geschäft des Produkts) und Umrechnungen an. Schlägt nur
	 * eine Umrechnung fehl, bleibt das Produkt bestehen und der Fehler kommt als Hinweis zurück.
	 */
	public static Result createProduct(GrocyClient client, Spec spec) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("name", spec.name);
		body.addProperty("location_id", spec.locationId);
		if (spec.shopId != null) {
			body.addProperty("shopping_location_id", spec.shopId);
		}
		if (spec.groupId != null) {
			body.addProperty("product_group_id", spec.groupId);
		}
		body.addProperty("qu_id_stock", spec.quStock);
		body.addProperty("qu_id_purchase", spec.quPurchase);
		body.addProperty("qu_id_consume", spec.quConsume);
		body.addProperty("qu_id_price", spec.quPrice);
		long productId = createdId(client.post("objects/products", body.toString()));

		if (spec.barcode != null && !spec.barcode.isEmpty()) {
			linkBarcode(client, productId, spec.barcode, spec.shopId);
		}
		String warning = null;
		for (Conversion c : spec.conversions) {
			try {
				saveConversion(client, productId, c);
			} catch (IOException e) {
				warning = Texts.get(R.string.product_created_conversion_failed, e.getMessage());
			}
		}
		return new Result(productId, warning);
	}

	/**
	 * Speichert eine Umrechnung. Grocy legt bei verschiedenen Einkaufs- und Bestandseinheiten selbst eine
	 * Umrechnung 1:1 samt Gegenrichtung an, die dann geändert werden muss, statt eine neue anzulegen.
	 */
	private static void saveConversion(GrocyClient client, long productId, Conversion c) throws IOException {
		JsonElement existing = client.get("objects/quantity_unit_conversions?query%5B%5D=product_id%3D" + productId);
		Long forwardId = null;
		Long reverseId = null;
		if (existing != null && existing.isJsonArray()) {
			for (JsonElement e : existing.getAsJsonArray()) {
				JsonObject o = e.getAsJsonObject();
				long from = o.get("from_qu_id").getAsLong();
				long to = o.get("to_qu_id").getAsLong();
				if (from == c.from && to == c.to) {
					forwardId = o.get("id").getAsLong();
				} else if (from == c.to && to == c.from) {
					reverseId = o.get("id").getAsLong();
				}
			}
		}
		if (forwardId != null) {
			JsonObject body = new JsonObject();
			body.addProperty("factor", c.factor);
			client.put("objects/quantity_unit_conversions/" + forwardId, body.toString());
		} else {
			JsonObject conversion = new JsonObject();
			conversion.addProperty("product_id", productId);
			conversion.addProperty("from_qu_id", c.from);
			conversion.addProperty("to_qu_id", c.to);
			conversion.addProperty("factor", c.factor);
			client.post("objects/quantity_unit_conversions", conversion.toString());
		}
		if (reverseId != null) {
			JsonObject body = new JsonObject();
			body.addProperty("factor", 1.0 / c.factor);
			client.put("objects/quantity_unit_conversions/" + reverseId, body.toString());
		}
	}

	/** Hängt einen weiteren Barcode an ein vorhandenes Produkt, mit dem Geschäft, in dem er gescannt wurde. */
	public static void linkBarcode(GrocyClient client, long productId, String barcode, Long shopId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("product_id", productId);
		body.addProperty("barcode", barcode);
		if (shopId != null) {
			body.addProperty("shopping_location_id", shopId);
		}
		client.post("objects/product_barcodes", body.toString());
	}

	private static long createdId(JsonElement response) throws IOException {
		try {
			return response.getAsJsonObject().get("created_object_id").getAsLong();
		} catch (RuntimeException e) {
			throw new IOException(Texts.get(R.string.err_grocy_no_id));
		}
	}
}
