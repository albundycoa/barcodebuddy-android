package de.bulling.barcodebuddyscanner.wear;

import androidx.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Was Zifferblatt und Kachel zeigen: je Reiter (Scans oder Einkaufsliste) die Zeilen und die Zusammenfassung aus dem
 * gespeicherten Stand vom Handy.
 */
final class FaceModel {

	static final int TAB_SCANS = 0;
	static final int TAB_SHOP  = 1;

	/** Eine Zeile der Liste. */
	static final class Row {
		final long    id;
		final String  title;
		final String  sub;
		/** Eintrag der Einkaufsliste ist abgehakt */
		final boolean done;
		/** Eintrag wurde durch einen Scan erledigt und lässt sich nicht von Hand ändern */
		final boolean covered;

		Row(long id, String title, String sub, boolean done, boolean covered) {
			this.id      = id;
			this.title   = title;
			this.sub     = sub;
			this.done    = done;
			this.covered = covered;
		}
	}

	private FaceModel() {
	}

	static List<Row> rows(@Nullable JsonObject state, int tab) {
		List<Row> rows = new ArrayList<>();
		if (tab == TAB_SCANS) {
			JsonArray scans = State.array(state, "scans");
			for (int i = 0; i < scans.size(); i++) {
				JsonObject s = scans.get(i).getAsJsonObject();
				String price = s.has("p") ? " · " + s.get("p").getAsString() : "";
				rows.add(new Row(s.get("id").getAsLong(), s.get("n").getAsString(), "×" + s.get("q").getAsInt() + price, false, false));
			}
		} else {
			JsonArray shop = ListEdits.openFirst(State.array(state, "shop"));
			for (int i = 0; i < shop.size(); i++) {
				JsonObject e = shop.get(i).getAsJsonObject();
				rows.add(new Row(e.get("id").getAsLong(), e.get("n").getAsString(), e.get("a").getAsString(),
						e.get("d").getAsBoolean(), e.has("c") && e.get("c").getAsBoolean()));
			}
		}
		return rows;
	}

	/** Zeile unter dem Reitertitel: "3 · 12,50 €" bei den Scans, "9 offen" bei der Einkaufsliste. */
	static String summary(@Nullable JsonObject state, int tab) {
		if (state == null) {
			return "–";
		}
		if (tab == TAB_SCANS) {
			int count = State.array(state, "scans").size();
			String total = state.has("total") ? state.get("total").getAsString() : "";
			return total.isEmpty() ? String.valueOf(count) : count + " · " + total;
		}
		JsonArray shop = State.array(state, "shop");
		int open = 0;
		for (int i = 0; i < shop.size(); i++) {
			if (!shop.get(i).getAsJsonObject().get("d").getAsBoolean()) {
				open++;
			}
		}
		return open + " offen";
	}

	static String emptyText(@Nullable JsonObject state, int tab) {
		if (state == null) {
			return "Noch keine Daten. Öffne die App auf dem Handy.";
		}
		return tab == TAB_SCANS ? "Noch nichts gescannt." : "Die Einkaufsliste ist leer.";
	}

	/** Gespeicherter Reiter von Zifferblatt und Kachel */
	static int tab(android.content.Context context) {
		return Settings.faceTab(context) == TAB_SHOP ? TAB_SHOP : TAB_SCANS;
	}
}
