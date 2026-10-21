package de.bulling.barcodebuddyscanner.wear;

import androidx.annotation.Nullable;

import com.google.gson.JsonObject;

/** Komplikation "Gescannt": Anzahl der gescannten Artikel und Summe; Tippen öffnet die Scanliste. */
public class ScansComplicationService extends ListComplicationService {

	@Override
	Content content(@Nullable JsonObject state) {
		if (state == null) {
			return new Content("–", "Scans", "Noch keine Daten", "Gescannt");
		}
		int scans = count(state, "scans");
		String total = field(state, "total");
		String longText = scans + (scans == 1 ? " Artikel" : " Artikel") + (scans > 0 && !total.isEmpty() ? " · " + total : "");
		return new Content(String.valueOf(scans), "Scans", longText, "Gescannt");
	}

	@Override
	Content preview() {
		return new Content("3", "Scans", "3 Artikel · 12,50 €", "Gescannt");
	}

	@Override
	Class<?> target() {
		return ScansActivity.class;
	}
}
