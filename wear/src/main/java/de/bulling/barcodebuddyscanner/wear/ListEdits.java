package de.bulling.barcodebuddyscanner.wear;

import android.content.Context;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Änderungen an Einkaufsliste und Scanliste, die Zifferblatt und Kachel gemeinsam brauchen: erst im gespeicherten Stand
 * eintragen (damit die Anzeige sofort stimmt), dann dem Handy melden. Das Handy bestätigt mit dem neuen Stand.
 */
final class ListEdits {

	private ListEdits() {
	}

	/** Eintrag der Einkaufsliste abhaken oder wieder öffnen. Durch Scan erledigte lassen sich nicht ändern; liefert false. */
	static boolean toggleShopping(Context context, long id) {
		JsonObject state = State.load(context);
		JsonArray shop = State.array(state, "shop");
		boolean done = false;
		boolean found = false;
		for (int i = 0; i < shop.size(); i++) {
			JsonObject e = shop.get(i).getAsJsonObject();
			if (e.get("id").getAsLong() == id) {
				if (e.has("c") && e.get("c").getAsBoolean()) {
					return false;
				}
				done = !e.get("d").getAsBoolean();
				e.addProperty("d", done);
				found = true;
			}
		}
		if (!found) {
			return false;
		}
		state.add("shop", openFirst(shop));
		State.save(context, state);
		PhoneLink.setDone(context, id, done);
		return true;
	}

	/** Gescannten Artikel aus der Scanliste entfernen (Fehlscan). */
	static void removeScan(Context context, long id) {
		JsonObject state = State.load(context);
		JsonArray array = State.array(state, "scans");
		for (int i = 0; i < array.size(); i++) {
			if (array.get(i).getAsJsonObject().get("id").getAsLong() == id) {
				array.remove(i);
				break;
			}
		}
		State.save(context, state);
		PhoneLink.delete(context, id);
	}

	/** Offene Einträge zuerst, erledigte ganz unten; die Reihenfolge innerhalb beider Gruppen bleibt. */
	static JsonArray openFirst(JsonArray shop) {
		JsonArray open = new JsonArray();
		JsonArray done = new JsonArray();
		for (int i = 0; i < shop.size(); i++) {
			JsonObject e = shop.get(i).getAsJsonObject();
			(e.get("d").getAsBoolean() ? done : open).add(e);
		}
		open.addAll(done);
		return open;
	}
}
