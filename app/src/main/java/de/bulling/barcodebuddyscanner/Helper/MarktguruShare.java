package de.bulling.barcodebuddyscanner.Helper;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Liest den Text, den die Marktguru-App beim Teilen einer Einkaufsliste schickt. Er sieht etwa so aus (die Zeilenumbrüche
 * spielen keine Rolle, alles wird zu einer Zeile zusammengezogen):
 *
 * <pre>
 * Einkaufsliste "Grocy"
 * Gesamt: € 5,77
 * - 1x Speisekartoffel 2kg - € 1,99 26.10. - 31.10.2026 Lidl Link: https://marktguru.de/offers/25398077
 * - 1x Äpfel 1kg - € 1,99 26.10. - 31.10.2026 Lidl Link: https://marktguru.de/offers/25397587
 * ----------
 * Werbetext
 * </pre>
 *
 * Reine Logik, getestet. Ohne erkennbare Marktguru-Zeilen wird jede nichtleere Zeile zu einem Eintrag.
 */
public final class MarktguruShare {

	/** Ein erkannter Eintrag. */
	public static final class Item {
		public final int    quantity;
		public final String name;
		/** Angebotspreis wie im Text ("1,99"), oder null */
		public final String price;
		/** Geschäft, oder leer */
		public final String shop;
		/** Gültigkeit ("26.10. - 31.10.2026"), oder leer */
		public final String validity;

		Item(int quantity, String name, String price, String shop, String validity) {
			this.quantity = quantity;
			this.name     = name;
			this.price    = price;
			this.shop     = shop;
			this.validity = validity;
		}

		/** Angebotspreis als Zahl, oder null */
		public Double priceValue() {
			return price == null ? null : Money.parse(price);
		}

		/** Ende der Gültigkeit als yyyy-MM-dd ("26.10. - 31.10.2026" -> "2026-10-31"), oder null */
		public String validUntil() {
			Matcher m = Pattern.compile("(\\d{1,2})\\.(\\d{1,2})\\.(\\d{4})$").matcher(validity == null ? "" : validity.trim());
			if (!m.find()) {
				return null;
			}
			return String.format(java.util.Locale.ROOT, "%s-%02d-%02d", m.group(3), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
		}
	}

	private static final Pattern SPLIT    = Pattern.compile("\\s-\\s+(?=\\d+x\\s)");
	private static final Pattern FOOTER   = Pattern.compile("\\s*-{5,}.*$");
	private static final Pattern LINK     = Pattern.compile("\\s*Link:\\s*\\S+");
	private static final Pattern QUANTITY = Pattern.compile("^(\\d+)x\\s+(.*)$");
	private static final Pattern VALIDITY = Pattern.compile("(\\d{1,2}\\.\\d{1,2}\\.)\\s*-\\s*(\\d{1,2}\\.\\d{1,2}\\.\\d{4})");
	private static final Pattern PRICE    = Pattern.compile("\\s*-\\s*€\\s*([0-9]+(?:[.,][0-9]+)?)");

	private MarktguruShare() {
	}

	/** Alle Einträge im Text; leer, wenn nichts zu erkennen ist. */
	public static List<Item> parse(String text) {
		List<Item> items = new ArrayList<>();
		if (text == null || text.trim().isEmpty()) {
			return items;
		}
		String flat = FOOTER.matcher(text.replaceAll("\\s+", " ").trim()).replaceAll("");
		String[] chunks = SPLIT.split(flat);
		// Das erste Stück ist der Kopf ("Einkaufsliste ... Gesamt: ..."), wenn der Text mit einem Eintrag weitergeht
		for (int i = 1; i < chunks.length; i++) {
			Item item = parseChunk(chunks[i]);
			if (item != null) {
				items.add(item);
			}
		}
		if (items.isEmpty() && chunks.length > 0 && QUANTITY.matcher(chunks[0].trim()).matches()) {
			Item item = parseChunk(chunks[0].trim());
			if (item != null) {
				items.add(item);
			}
		}
		if (items.isEmpty()) {
			// Kein Marktguru-Text: jede Zeile ein Eintrag
			for (String line : text.split("\\r?\\n")) {
				String name = line.replaceFirst("^[\\s\\-*•]+", "").trim();
				if (!name.isEmpty()) {
					items.add(new Item(1, name, null, "", ""));
				}
			}
		}
		return items;
	}

	private static Item parseChunk(String chunk) {
		String s = LINK.matcher(chunk).replaceAll("").trim();
		Matcher q = QUANTITY.matcher(s);
		if (!q.matches()) {
			return null;
		}
		int quantity = Math.max(1, Integer.parseInt(q.group(1)));
		s = q.group(2).trim();

		String validity = "";
		String shop = "";
		Matcher v = VALIDITY.matcher(s);
		int validityAt = -1;
		while (v.find()) {
			validityAt = v.start();
			validity = v.group(1) + " - " + v.group(2);
			shop = s.substring(v.end()).trim();
		}
		String before = validityAt >= 0 ? s.substring(0, validityAt).trim() : s;

		String price = null;
		Matcher p = PRICE.matcher(before);
		int priceAt = -1;
		int priceEnd = -1;
		while (p.find()) {
			priceAt = p.start();
			priceEnd = p.end();
			price = p.group(1);
		}
		String name = priceAt >= 0 ? before.substring(0, priceAt).trim() : before;
		if (validityAt < 0 && priceAt >= 0) {
			// Ohne Gültigkeit steht ein Geschäft direkt hinter dem Preis
			shop = before.substring(priceEnd).trim();
		}
		return name.isEmpty() ? null : new Item(quantity, name, price, shop, validity);
	}
}
