package de.bulling.barcodebuddyscanner.Helper;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Euro-Beträge im deutschen Format ("1,49 €").
 */
public final class Money {

	private Money() {
	}

	public static String format(double amount) {
		NumberFormat format = NumberFormat.getCurrencyInstance(Locale.GERMANY);
		return format.format(amount);
	}

	/** Zahl für Eingabefelder: höchstens 4 Nachkommastellen, ohne Währungszeichen, mit Komma. */
	public static String formatPlain(double amount) {
		NumberFormat format = NumberFormat.getNumberInstance(Locale.GERMANY);
		format.setMinimumFractionDigits(2);
		format.setMaximumFractionDigits(4);
		format.setGroupingUsed(false);
		return format.format(amount);
	}

	/** Liest "1,49" oder "1.49"; leere oder ungültige Eingabe ergibt null. */
	public static Double parse(String text) {
		if (text == null) {
			return null;
		}
		String clean = text.trim().replace("€", "").replace(" ", "");
		// "1.234,56": mit Komma ist der Punkt ein Tausenderpunkt
		if (clean.contains(",")) {
			clean = clean.replace(".", "");
		}
		clean = clean.replace(',', '.');
		if (clean.isEmpty()) {
			return null;
		}
		try {
			double value = Double.parseDouble(clean);
			return value >= 0 && !Double.isNaN(value) && !Double.isInfinite(value) ? value : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
