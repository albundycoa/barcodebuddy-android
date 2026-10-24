package de.bulling.barcodebuddyscanner.Api;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.ProductImages;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Bringt die Produktbilder der App auch in Grocy: Hat ein Produkt dort noch kein Bild, wird das gespeicherte OpenFoodFacts-Bild
 * hochgeladen und dem Produkt zugeordnet. Vorhandene Bilder in Grocy bleiben unverändert. Dann zeigen auch Grocys Weboberfläche
 * und andere Apps das Bild.
 */
public final class GrocyPictures {

	public interface Done {
		/** @param uploaded Anzahl hochgeladener Bilder; error null = alles geklappt */
		void onDone(int uploaded, String error);
	}

	private static final ExecutorService IO   = Executors.newSingleThreadExecutor();
	private static Handler                main;
	private static boolean running;

	private GrocyPictures() {
	}

	/** Dateiname in Grocy für das Bild dieses Barcodes. */
	static String fileName(String barcode) {
		return "bbs-" + barcode + ".jpg";
	}

	/**
	 * Grocy erwartet den Dateinamen Base64-codiert im Pfad. Enthält das Ergebnis "+" oder "/" (die im Pfad Ärger machen), wird
	 * der Name mit einem Buchstaben ergänzt, bis es passt.
	 */
	static String safeFileName(String barcode) {
		String name = fileName(barcode);
		for (int i = 0; i < 20; i++) {
			String encoded = java.util.Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
			if (encoded.indexOf('+') < 0 && encoded.indexOf('/') < 0) {
				return name;
			}
			name = "bbs-" + barcode + "-" + (char) ('a' + i) + ".jpg";
		}
		return name;
	}

	/** Produkte ohne Bild in Grocy: Nummern aus "objects/products". */
	static Set<Long> withoutPicture(JsonElement products) {
		Set<Long> ids = new HashSet<>();
		if (products == null || !products.isJsonArray()) {
			return ids;
		}
		for (JsonElement e : products.getAsJsonArray()) {
			if (!e.isJsonObject()) {
				continue;
			}
			JsonObject o = e.getAsJsonObject();
			JsonElement picture = o.get("picture_file_name");
			boolean none = picture == null || picture.isJsonNull() || picture.getAsString().trim().isEmpty();
			JsonElement id = o.get("id");
			if (none && id != null && !id.isJsonNull()) {
				try {
					ids.add(Long.parseLong(id.getAsString()));
				} catch (NumberFormatException ignored) {
					// ohne gültige Nummer überspringen
				}
			}
		}
		return ids;
	}

	/** Lädt das Bild hoch und ordnet es dem Produkt zu. */
	static void upload(GrocyClient client, long productId, String barcode, byte[] jpeg) throws IOException {
		String name = safeFileName(barcode);
		String encoded = java.util.Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
		client.putFile("files/productpictures/" + encoded.replace("=", "%3D"), jpeg, "application/octet-stream");
		JsonObject body = new JsonObject();
		body.addProperty("picture_file_name", name);
		client.put("objects/products/" + productId, body.toString());
	}

	/**
	 * Sendet alle gespeicherten Bilder, deren Produkt in Grocy noch kein Bild hat. {@code done} läuft auf dem Main-Thread.
	 */
	public static void syncAll(Context context, final Done done) {
		final Context app = context.getApplicationContext();
		final SharedPrefHelper prefs = new SharedPrefHelper(app);
		final GrocyClient client = prefs.initGrocyClient();
		if (client == null) {
			post(done, 0, null);
			return;
		}
		synchronized (GrocyPictures.class) {
			if (running) {
				post(done, 0, null);
				return;
			}
			running = true;
		}
		ShoppingRepository.get(app).getProductBarcodes((Map<Long, String> map) -> IO.execute(() -> {
			int uploaded = 0;
			String error = null;
			try {
				Set<Long> missing = withoutPicture(client.get("objects/products"));
				for (Map.Entry<Long, String> entry : map.entrySet()) {
					if (!missing.contains(entry.getKey())) {
						continue;
					}
					byte[] jpeg = ProductImages.bytes(app, entry.getValue());
					if (jpeg == null) {
						continue;
					}
					upload(client, entry.getKey(), entry.getValue(), jpeg);
					uploaded++;
				}
			} catch (IOException e) {
				error = e.getMessage();
			} finally {
				synchronized (GrocyPictures.class) {
					running = false;
				}
			}
			post(done, uploaded, error);
		}));
	}

	private static void post(final Done done, final int uploaded, final String error) {
		if (done != null) {
			synchronized (GrocyPictures.class) {
				if (main == null) {
					main = new Handler(Looper.getMainLooper());
				}
			}
			main.post(() -> done.onDone(uploaded, error));
		}
	}
}
