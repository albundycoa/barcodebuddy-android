package de.bulling.barcodebuddyscanner.wear;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/** Komplikation "Einkaufsliste": Anzahl der offenen Einträge und Gesamtwert; Tippen öffnet die Einkaufsliste. */
public class ShoppingComplicationService extends ListComplicationService {

	@Override
	Content content(@Nullable JsonObject state) {
		if (state == null) {
			return new Content("–", "Liste", "Noch keine Daten", "Einkauf");
		}
		int open = openCount(state);
		String value = field(state, "stotal");
		String longText = open + " offen" + (!value.isEmpty() ? " · " + value : "");
		return new Content(String.valueOf(open), "Liste", longText, "Einkauf");
	}

	@Override
	Content preview() {
		return new Content("9", "Liste", "9 offen · 15,62 €", "Einkauf");
	}

	@Override
	Class<?> target() {
		return ShoppingActivity.class;
	}
}
