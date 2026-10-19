package de.bulling.barcodebuddyscanner.Helper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Liest die QR-Codes der Einrichtung: den von Barcode Buddy ("Mobile App hinzufügen") und den von Grocy ("API-Schlüssel
 * verwalten"). Beide liefern Adresse und Schlüssel.
 */
public final class SetupQr {

	/** Adresse und Schlüssel eines Servers. */
	public static final class Credentials {
		public final String url;
		public final String key;

		Credentials(String url, String key) {
			this.url = url;
			this.key = key;
		}
	}

	private SetupQr() {
	}

	/** Barcode Buddy: JSON mit "issetup", "url" und "key". Ungültiges ergibt null. */
	public static Credentials parseBarcodeBuddy(String content) {
		if (content == null || !content.contains("issetup")) {
			return null;
		}
		try {
			JsonElement element = JsonParser.parseString(content);
			if (!element.isJsonObject()) {
				return null;
			}
			JsonObject o = element.getAsJsonObject();
			if (!o.has("url") || !o.has("key")) {
				return null;
			}
			String url = o.get("url").getAsString().trim();
			String key = o.get("key").getAsString().trim();
			return url.isEmpty() || key.isEmpty() ? null : new Credentials(url, key);
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Grocy: "Adresse|Schlüssel". Ungültiges ergibt null. */
	public static Credentials parseGrocy(String content) {
		if (content == null) {
			return null;
		}
		int separator = content.lastIndexOf('|');
		if (separator <= 0 || separator == content.length() - 1) {
			return null;
		}
		String url = content.substring(0, separator).trim();
		String key = content.substring(separator + 1).trim();
		return url.isEmpty() || key.isEmpty() ? null : new Credentials(url, key);
	}

	/** Barcode Buddy erwartet die Adresse mit abschließendem Schrägstrich. */
	public static String withTrailingSlash(String url) {
		String trimmed = url == null ? "" : url.trim();
		return trimmed.endsWith("/") ? trimmed : trimmed + "/";
	}

	/** Welcher Schritt der Einrichtung als Erstes fehlt: 1 = Barcode Buddy, 2 = Grocy, 0 = nichts. */
	public static int firstMissingStep(boolean hasBarcodeBuddy, boolean hasGrocy) {
		if (!hasBarcodeBuddy) {
			return 1;
		}
		return hasGrocy ? 0 : 2;
	}
}
