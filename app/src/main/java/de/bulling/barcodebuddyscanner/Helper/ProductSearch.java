package de.bulling.barcodebuddyscanner.Helper;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Produktsuche für "Auf die Einkaufsliste": Namen, die mit dem Suchbegriff beginnen, stehen vor denen, die ihn nur enthalten;
 * Groß-/Kleinschreibung und Umlaute spielen keine Rolle ("apfel" findet "Äpfel"). Mehrere Wörter müssen alle vorkommen.
 * Reine Logik, getestet.
 */
public final class ProductSearch {

	/** Ein durchsuchbarer Eintrag (Produkt) mit Name und Gruppe. */
	public static final class Item {
		public final long   id;
		public final String name;
		public final String group;

		public Item(long id, String name, String group) {
			this.id    = id;
			this.name  = name == null ? "" : name;
			this.group = group;
		}
	}

	private ProductSearch() {
	}

	/** Kleinbuchstaben ohne Akzente und Umlaut-Punkte: "Äpfel" -> "apfel". */
	static String normalize(String text) {
		String lower = text == null ? "" : text.toLowerCase(Locale.GERMAN);
		return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "").replace("ß", "ss");
	}

	/**
	 * Treffer für den Suchbegriff, beste zuerst (Anfang des Namens, dann Anfang eines Wortes, dann irgendwo), höchstens
	 * {@code limit}. Ein leerer Begriff liefert die ersten Einträge in alphabetischer Reihenfolge.
	 */
	public static List<Item> search(List<Item> items, String query, int limit) {
		final String[] words = normalize(query).trim().isEmpty() ? new String[0] : normalize(query).trim().split("\\s+");
		List<Item> sorted = new ArrayList<>(items);
		java.util.Collections.sort(sorted, (a, b) -> normalize(a.name).compareTo(normalize(b.name)));
		if (words.length == 0) {
			return sorted.size() > limit ? new ArrayList<>(sorted.subList(0, limit)) : sorted;
		}
		List<Item> starts = new ArrayList<>();
		List<Item> wordStarts = new ArrayList<>();
		List<Item> contains = new ArrayList<>();
		for (Item item : sorted) {
			String name = normalize(item.name);
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
			if (name.startsWith(words[0])) {
				starts.add(item);
			} else if (name.contains(" " + words[0])) {
				wordStarts.add(item);
			} else {
				contains.add(item);
			}
		}
		List<Item> result = new ArrayList<>(starts);
		result.addAll(wordStarts);
		result.addAll(contains);
		return result.size() > limit ? new ArrayList<>(result.subList(0, limit)) : result;
	}
}
