package de.bulling.barcodebuddyscanner.wear;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Suche im Produktkatalog des Handys für "Hinzufügen" auf der Uhr: Namen, die mit dem Suchbegriff beginnen, stehen vor denen,
 * die ihn nur enthalten; Groß-/Kleinschreibung und Umlaute spielen keine Rolle, mehrere Wörter müssen alle vorkommen.
 */
final class CatalogSearch {

	private static final int LIMIT = 30;

	private CatalogSearch() {
	}

	static String normalize(String text) {
		String lower = text == null ? "" : text.toLowerCase(Locale.GERMAN);
		return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace("ß", "ss");
	}

	/** Treffer als Objekte des Katalogs ({@code id}, {@code n}, optional {@code g}), beste zuerst. */
	static List<JsonObject> search(JsonArray products, String query) {
		String q = normalize(query).trim();
		String[] words = q.isEmpty() ? new String[0] : q.split("\\s+");
		List<JsonObject> starts = new ArrayList<>();
		List<JsonObject> wordStarts = new ArrayList<>();
		List<JsonObject> contains = new ArrayList<>();
		for (int i = 0; i < products.size(); i++) {
			JsonObject product = products.get(i).getAsJsonObject();
			String name = normalize(product.get("n").getAsString());
			boolean all = true;
			for (String word : words) {
				if (!name.contains(word)) {
					all = false;
					break;
				}
			}
			if (!all) {
				continue;
			}
			if (words.length == 0 || name.startsWith(words[0])) {
				starts.add(product);
			} else if (name.contains(" " + words[0])) {
				wordStarts.add(product);
			} else {
				contains.add(product);
			}
		}
		List<JsonObject> result = new ArrayList<>(starts);
		result.addAll(wordStarts);
		result.addAll(contains);
		return result.size() > LIMIT ? new ArrayList<>(result.subList(0, LIMIT)) : result;
	}
}
