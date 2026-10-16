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
