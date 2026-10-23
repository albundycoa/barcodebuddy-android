package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;

import de.bulling.barcodebuddyscanner.Helper.StockOverview;
import de.bulling.barcodebuddyscanner.Helper.Texts;
import de.bulling.barcodebuddyscanner.R;

/**
 * Einträge der Grocy-Einkaufsliste anlegen und löschen, auch freie Einträge ohne Produkt (nur ein Text als Notiz). Die
 * Aufrufe blockieren und gehören auf einen Hintergrund-Thread.
 */
public final class GrocyShopping {

	private GrocyShopping() {
	}

	/** Nachricht für einen freien Eintrag: Notiz und Menge, ohne Produkt, auf der Einkaufsliste mit der Nummer listId. */
	static JsonObject noteBody(String note, double amount, long listId) {
		JsonObject body = new JsonObject();
		body.addProperty("note", note);
		body.addProperty("amount", amount);
		body.addProperty("shopping_list_id", listId);
		return body;
	}

	/** Schiebt einen Eintrag auf eine andere Einkaufsliste. */
	public static void moveEntry(GrocyClient client, long entryId, long listId) throws IOException {
		JsonObject body = new JsonObject();
		body.addProperty("shopping_list_id", listId);
		client.put("objects/shopping_list/" + entryId, body.toString());
	}

	/** Nummer des neu angelegten Eintrags aus Grocys Antwort ({"created_object_id": 12}); -1, wenn sie fehlt. */
	static long createdId(JsonElement response) {
		try {
			return response.getAsJsonObject().get("created_object_id").getAsLong();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	/** Legt einen freien Eintrag an und liefert seine Nummer in Grocy. */
	public static long addNote(GrocyClient client, String note, double amount, long listId) throws IOException {
		long id = createdId(client.post("objects/shopping_list", noteBody(note, amount, listId).toString()));
		if (id < 0) {
			throw new IOException(Texts.get(R.string.err_grocy_no_id));
		}
		return id;
	}

	/** Entfernt einen Eintrag von der Einkaufsliste. */
	public static void deleteEntry(GrocyClient client, long entryId) throws IOException {
		client.delete("objects/shopping_list/" + entryId);
	}

	/** Einheiten und Standardwerte eines einzelnen Produkts (für die Mengenmaske); null, wenn Grocy es nicht kennt. */
	public static StockOverview.Product loadProduct(GrocyClient client, long productId) throws IOException {
		return GrocyStock.loadProductSetups(client).get(productId);
	}
}
