package de.bulling.barcodebuddyscanner.wear;

import android.content.Context;
import android.widget.Toast;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;

/** Schickt Befehle an das Handy. */
final class PhoneLink {

	private static final String PATH_ACTION = "/bbs/action";

	private PhoneLink() {
	}

	static void send(Context context, JsonObject command) {
		final Context app = context.getApplicationContext();
		final byte[] data = command.toString().getBytes(StandardCharsets.UTF_8);
		Wearable.getNodeClient(app).getConnectedNodes()
				.addOnSuccessListener(nodes -> {
					if (nodes.isEmpty()) {
						Toast.makeText(app, R.string.no_phone, Toast.LENGTH_SHORT).show();
					}
					for (Node node : nodes) {
						Wearable.getMessageClient(app).sendMessage(node.getId(), PATH_ACTION, data);
					}
				})
				.addOnFailureListener(e -> Toast.makeText(app, R.string.no_phone, Toast.LENGTH_SHORT).show());
	}

	/** Bittet das Handy um den aktuellen Stand. */
	static void requestSync(Context context) {
		final Context app = context.getApplicationContext();
		// Die Data Layer meldet nur Änderungen: den bereits vorhandenen Stand holt die Uhr selbst ab
		Wearable.getDataClient(app).getDataItems(android.net.Uri.parse("wear://*/bbs/state"))
				.addOnSuccessListener(items -> {
					for (com.google.android.gms.wearable.DataItem item : items) {
						String json = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).getDataMap().getString("json");
						if (json != null) {
							State.save(app, json);
						}
					}
					items.release();
				});
		JsonObject c = new JsonObject();
		c.addProperty("a", "sync");
		send(context, c);
	}

	/** Liest den zuletzt vom Handy geschickten Produktkatalog (für "Hinzufügen"). */
	static void requestCatalog(Context context) {
		final Context app = context.getApplicationContext();
		Wearable.getDataClient(app).getDataItems(android.net.Uri.parse("wear://*/bbs/catalog"))
				.addOnSuccessListener(items -> {
					for (com.google.android.gms.wearable.DataItem item : items) {
						String json = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).getDataMap().getString("json");
						if (json != null) {
							State.saveCatalog(app, json);
						}
					}
					items.release();
				});
		// Das Handy schickt den Katalog mit jeder Änderung; ein Sync-Befehl stößt das an
		JsonObject c = new JsonObject();
		c.addProperty("a", "sync");
		send(context, c);
	}

	/** Noch {@code amount} von dem Produkt auf die Einkaufsliste (in der Einkaufseinheit, zum vorhandenen Eintrag dazu). */
	static void addProduct(Context context, long productId, double amount) {
		JsonObject c = new JsonObject();
		c.addProperty("a", "shop_add");
		c.addProperty("p", productId);
		c.addProperty("x", amount);
		send(context, c);
	}

	/** Freien Eintrag (ohne Produkt) anlegen; group "" = keine Produktgruppe. */
	static void addNote(Context context, String text, String group, double amount) {
		JsonObject c = new JsonObject();
		c.addProperty("a", "shop_note");
		c.addProperty("t", text);
		c.addProperty("g", group);
		c.addProperty("x", amount);
		send(context, c);
	}

	/** Entfernt einen Eintrag von der Einkaufsliste. */
	static void deleteEntry(Context context, long id) {
		JsonObject c = new JsonObject();
		c.addProperty("a", "shop_del");
		c.addProperty("id", id);
		send(context, c);
	}

	/** Bittet das Handy um den aktuellen Bestand aus Grocy (kommt als Datenelement zurück). */
	static void requestStock(Context context) {
		final Context app = context.getApplicationContext();
		Wearable.getDataClient(app).getDataItems(android.net.Uri.parse("wear://*/bbs/stock"))
				.addOnSuccessListener(items -> {
					for (com.google.android.gms.wearable.DataItem item : items) {
						String json = com.google.android.gms.wearable.DataMapItem.fromDataItem(item).getDataMap().getString("json");
						if (json != null) {
							State.saveStock(app, json);
						}
					}
					items.release();
				});
		JsonObject c = new JsonObject();
		c.addProperty("a", "stock");
		send(context, c);
	}

	/** Verbrauchen ("consume") oder Öffnen ("open") der Menge in Lagereinheiten; name und text bilden die Meldung. */
	static void book(Context context, String action, long productId, double amount, String name, String text) {
		JsonObject c = new JsonObject();
		c.addProperty("a", action);
		c.addProperty("p", productId);
		c.addProperty("x", amount);
		c.addProperty("n", name);
		c.addProperty("t", text);
		send(context, c);
	}

	/** Nimmt die letzte Buchung zurück (Nummer aus der Meldung des Handys). */
	static void undo(Context context, String transaction) {
		JsonObject c = new JsonObject();
		c.addProperty("a", "undo");
		c.addProperty("tx", transaction);
		send(context, c);
	}

	static void setDone(Context context, long id, boolean done) {
		JsonObject c = new JsonObject();
		c.addProperty("a", "done");
		c.addProperty("id", id);
		c.addProperty("v", done);
		send(context, c);
	}

	static void setQuantity(Context context, long id, int quantity) {
		JsonObject c = new JsonObject();
		c.addProperty("a", "qty");
		c.addProperty("id", id);
		c.addProperty("q", quantity);
		send(context, c);
	}

	static void delete(Context context, long id) {
		JsonObject c = new JsonObject();
		c.addProperty("a", "del");
		c.addProperty("id", id);
		send(context, c);
	}
}
