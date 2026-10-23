package de.bulling.barcodebuddyscanner.Helper;

import java.util.ArrayList;
import java.util.List;

/**
 * Schlägt zu einem Namen aus einer anderen App (z. B. "Speisekartoffel 2kg" aus Marktguru) ein Grocy-Produkt vor. Mengenangaben
 * wie "2kg" oder "0,22kg" zählen nicht. Ein Produkt kommt nur in Frage, wenn alle seine Wörter im Namen
 * vorkommen und sie mindestens 40 % der Wörter des Namens abdecken (im Namen darf ein Wort das Produktwort enthalten:
 * "Kartoffel" in "Speisekartoffel"). Lieber ein freier Eintrag als ein falsches Produkt. Reine Logik, getestet.
 */
public final class ProductMatch {

	private ProductMatch() {
	}

	/** Das beste Produkt, oder null, wenn keines gut genug passt. */
	public static ProductSearch.Item best(String name, List<ProductSearch.Item> products) {
		List<String> words = words(name);
		if (words.isEmpty()) {
			return null;
		}
		ProductSearch.Item best = null;
		double bestRatio = 0;
		int bestLength = 0;
		for (ProductSearch.Item product : products) {
			List<String> productWords = words(product.name);
			if (productWords.isEmpty()) {
				continue;
			}
			int matched = 0;
			int length = 0;
			boolean strong = false;
			for (String pw : productWords) {
				for (String w : words) {
					if (matches(pw, w)) {
						matched++;
						length += pw.length();
						strong |= pw.length() >= 4;
						break;
					}
				}
			}
			// Auch der Name muss zu einem guten Teil abgedeckt sein: "Meica Curry King" ist nicht das Gewürz "Curry"
			int covered = 0;
			for (String w : words) {
				for (String pw : productWords) {
					if (matches(pw, w)) {
						covered++;
						break;
					}
				}
			}
			double ratio = (double) matched / productWords.size();
			if (!strong || ratio < 1.0 || (double) covered / words.size() < 0.4) {
				continue;
			}
			boolean better = ratio > bestRatio
					|| (ratio == bestRatio && length > bestLength)
					|| (ratio == bestRatio && length == bestLength && best != null && product.name.length() < best.name.length());
			if (better) {
				best = product;
				bestRatio = ratio;
				bestLength = length;
			}
		}
		return best;
	}

	/** Gleiche Wörter, oder das Wort des Namens enthält das Produktwort (mindestens 4 Zeichen, sonst nur gleich). */
	private static boolean matches(String productWord, String word) {
		return productWord.equals(word) || (productWord.length() >= 4 && word.contains(productWord));
	}

	/** Wörter des Namens ohne Akzente, ohne Mengenangaben und ohne ganz kurze Wörter. */
	static List<String> words(String name) {
		List<String> words = new ArrayList<>();
		for (String raw : ProductSearch.normalize(name).split("[^a-z0-9,.]+")) {
			String w = raw.replaceAll("^[.,]+|[.,]+$", "");
			if (w.length() < 3 || w.matches("[0-9][0-9.,]*(kg|g|mg|l|ml|cl|stk|st|x|er|m|cm|mm)?")) {
				continue;
			}
			words.add(stem(w));
		}
		return words;
	}

	/** Grob ohne Endung ("Kartoffeln" und "Kartoffel", "Äpfel" und "Apfel" werden gleich). */
	private static String stem(String w) {
		if (w.length() > 5 && w.endsWith("en")) {
			return w.substring(0, w.length() - 2);
		}
		if (w.length() > 4 && (w.endsWith("n") || w.endsWith("e") || w.endsWith("s"))) {
			return w.substring(0, w.length() - 1);
		}
		return w;
	}
}
