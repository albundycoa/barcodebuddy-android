package de.bulling.barcodebuddyscanner.Api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import de.bulling.barcodebuddyscanner.BuildConfig;

/**
 * Fragt die offene Produktdatenbank OpenFoodFacts nach einem Barcode und liefert einen Namensvorschlag für
 * unbekannte Produkte. Die Aufrufe blockieren und gehören auf einen Hintergrund-Thread. Übertragen wird nur
 * der Barcode, keine Daten aus Grocy oder Barcode Buddy.
 */
public final class OpenFoodFacts {

	/** Ergebnis einer Abfrage: Name oder "nicht gefunden". */
	public static final class Result {
		/** null = OpenFoodFacts kennt den Barcode nicht (oder ohne brauchbaren Namen) */
		public final String name;

		Result(String name) {
			this.name = name;
		}
	}

	private static final String URL_TEMPLATE = "https://world.openfoodfacts.org/api/v2/product/%s.json"
			+ "?fields=product_name,product_name_de,generic_name,brands,quantity";

	private OpenFoodFacts() {
	}

	/** EAN-8, UPC-A, EAN-13 und GTIN-14: nur so etwas gibt es in der Datenbank, alles andere gar nicht erst anfragen. */
	public static boolean isProductBarcode(String barcode) {
		if (barcode == null) {
			return false;
		}
		int length = barcode.length();
		if (length < 8 || length > 14) {
			return false;
		}
		for (int i = 0; i < length; i++) {
			if (barcode.charAt(i) < '0' || barcode.charAt(i) > '9') {
				return false;
			}
		}
		return true;
	}

	/**
	 * Fragt OpenFoodFacts ab.
	 *
	 * @throws IOException bei Netzwerkfehlern oder wenn der Dienst nicht antwortet; dann gilt der Barcode
	 *                     als "nicht geprüft" und wird später erneut versucht
	 */
	public static Result lookup(String barcode) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(String.format(URL_TEMPLATE, barcode)).openConnection();
		try {
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(5000);
			connection.setReadTimeout(8000);
			// OpenFoodFacts bittet um einen sprechenden User-Agent mit Name und Version der App
			connection.setRequestProperty("User-Agent", "BarcodeBuddyAndroid/" + BuildConfig.VERSION_NAME
					+ " (Android; https://github.com/albundycoa/barcodebuddy-android)");
			connection.setRequestProperty("Accept", "application/json");
			int status = connection.getResponseCode();
			if (status == 404) {
				return new Result(null);
			}
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status);
			}
			try (InputStream in = connection.getInputStream();
				 BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				StringBuilder body = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					body.append(line);
				}
				return new Result(parseName(body.toString()));
			}
		} finally {
			connection.disconnect();
		}
	}

	/** Baut aus der Antwort einen Namen wie "Marke Produkt (500 g)", null wenn nichts Brauchbares dabei ist. */
	public static String parseName(String json) {
		JsonObject root;
		try {
			JsonElement parsed = JsonParser.parseString(json);
			if (!parsed.isJsonObject()) {
				return null;
			}
			root = parsed.getAsJsonObject();
		} catch (RuntimeException e) {
			return null;
		}
		// status 0 = Produkt nicht gefunden
		if (root.has("status") && !root.get("status").isJsonNull() && root.get("status").getAsInt() == 0) {
			return null;
		}
		if (!root.has("product") || !root.get("product").isJsonObject()) {
			return null;
		}
		JsonObject product = root.getAsJsonObject("product");

		String name = firstNonBlank(text(product, "product_name_de"), text(product, "product_name"),
				text(product, "generic_name"));
		if (name == null) {
			return null;
		}
		String brand = text(product, "brands");
		if (brand != null) {
			brand = brand.split(",")[0].trim();
		}
		String quantity = text(product, "quantity");

		StringBuilder result = new StringBuilder();
		if (brand != null && !brand.isEmpty() && !name.toLowerCase().contains(brand.toLowerCase())) {
			result.append(brand).append(' ');
		}
		result.append(name);
		if (quantity != null && !name.toLowerCase().contains(quantity.toLowerCase())) {
			result.append(" (").append(quantity).append(')');
		}
		return result.toString();
	}

	private static String text(JsonObject object, String key) {
		if (!object.has(key) || object.get(key).isJsonNull() || !object.get(key).isJsonPrimitive()) {
			return null;
		}
		String value = object.get(key).getAsString().trim();
		return value.isEmpty() ? null : value;
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null) {
				return value;
			}
		}
		return null;
	}
}
