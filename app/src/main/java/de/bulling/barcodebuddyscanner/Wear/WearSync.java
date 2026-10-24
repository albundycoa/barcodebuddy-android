package de.bulling.barcodebuddyscanner.Wear;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.ReceiptPdf;

/**
 * Schickt den aktuellen Stand (Einkaufsliste aus Grocy und gescannte Artikel) an die Wear-OS-Uhr.
 * Die Uhr bekommt ihn über die Data Layer; ist keine Uhr gekoppelt oder fehlt Google Play Services,
 * passiert nichts. Unveränderte Daten löst die Data Layer nicht erneut aus.
 */
public final class WearSync {

	private static final String TAG = "WearSync";

	/** Pfad des Datenelements, das die Uhr liest. */
	public static final String PATH_STATE  = "/bbs/state";
	/** Pfad der Befehle, die die Uhr schickt. */
	public static final String PATH_ACTION = "/bbs/action";

	private static final long DEBOUNCE_MS = 400;

	private static final Handler main = new Handler(Looper.getMainLooper());
	private static Runnable pending;

	private WearSync() {
	}

	/** Gleich mehrere Änderungen kurz hintereinander werden zu einer Übertragung zusammengefasst. */
	public static void push(Context context) {
		final Context app = context.getApplicationContext();
		main.removeCallbacks(pending != null ? pending : () -> {});
		pending = () -> load(app);
		main.postDelayed(pending, DEBOUNCE_MS);
	}

	private static void load(Context app) {
		final ShoppingRepository repo = ShoppingRepository.get(app);
		repo.getOpenList(list -> repo.getShoppingList(shopping -> send(app, list, shopping)));
		sendCatalog(app, repo);
	}

	/** Pfad des Katalogs aus Produkten und Gruppen, mit dem die Uhr Einträge zur Einkaufsliste hinzufügt. */
	public static final String PATH_CATALOG = "/bbs/catalog";

	private static String lastCatalog = "";

	private static void sendCatalog(final Context app, final ShoppingRepository repo) {
		repo.getProducts("", products -> repo.getGroups(groups -> {
			java.util.List<de.bulling.barcodebuddyscanner.Helper.WearCatalogJson.Product> list = new ArrayList<>();
			for (ShoppingRepository.ProductRow p : products) {
				list.add(new de.bulling.barcodebuddyscanner.Helper.WearCatalogJson.Product(p.id, p.name, p.group));
			}
			String json = de.bulling.barcodebuddyscanner.Helper.WearCatalogJson.build(list, groups);
			if (json.equals(lastCatalog)) {
				return;
			}
			lastCatalog = json;
			PutDataMapRequest request = PutDataMapRequest.create(PATH_CATALOG);
			request.getDataMap().putString("json", json);
			try {
				Wearable.getDataClient(app).putDataItem(request.asPutDataRequest())
						.addOnFailureListener(e -> Log.d(TAG, "Katalog nicht an die Uhr übertragbar: " + e.getMessage()));
			} catch (RuntimeException e) {
				Log.d(TAG, "Wear nicht verfügbar", e);
			}
		}));
	}

	private static void send(Context app, ShoppingRepository.OpenList list, ShoppingRepository.ShoppingData shopping) {
		JsonObject root = new JsonObject();

		// Einkaufsliste wie auf dem Handy: offene Einträge nach Produktgruppe, erledigte unten
		List<ShoppingRepository.ShoppingEntry> entries = new ArrayList<>();
		List<ShoppingRepository.ShoppingEntry> doneEntries = new ArrayList<>();
		for (ShoppingRepository.ShoppingEntry e : shopping.entries) {
			(e.isDone() ? doneEntries : entries).add(e);
		}
		Collections.sort(entries, shopping.openOrder());
		Collections.sort(doneEntries, (a, b) -> a.name.compareToIgnoreCase(b.name));
		entries.addAll(doneEntries);
		JsonArray shop = new JsonArray();
		for (ShoppingRepository.ShoppingEntry e : entries) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.id);
			o.addProperty("n", e.name);
			o.addProperty("a", amountText(e));
			o.addProperty("d", e.isDone());
			o.addProperty("c", e.isCoveredByScans());
			if (e.group != null) {
				o.addProperty("g", e.group);
			}
			shop.add(o);
		}
		root.add("shop", shop);
		root.addProperty("stotal", Money.format(shopping.totalValue()));

		// Gescannte Artikel, der zuletzt gescannte zuerst
		JsonArray scans = new JsonArray();
		for (ShoppingItem item : list.items) {
			JsonObject o = new JsonObject();
			o.addProperty("id", item.id);
			// Wie in der App: Name aus Grocy, sonst der Vorschlag aus Open Food Facts, sonst der Barcode
			o.addProperty("n", item.displayName() != null ? item.displayName()
					: item.suggestion != null ? item.suggestion : item.barcode);
			o.addProperty("q", item.quantity);
			Double line = ReceiptPdf.lineTotal(item);
			if (line != null) {
				o.addProperty("p", Money.format(line));
			}
			scans.add(o);
		}
		root.add("scans", scans);
		root.addProperty("total", Money.format(ReceiptPdf.totals(list.items).sum));
		int battery = de.bulling.barcodebuddyscanner.ScannerService.getBatteryLevel();
		if (battery >= 0) {
			root.addProperty("bat", battery);
		}

		PutDataMapRequest request = PutDataMapRequest.create(PATH_STATE);
		request.getDataMap().putString("json", root.toString());
		PutDataRequest put = request.asPutDataRequest().setUrgent();
		try {
			Wearable.getDataClient(app).putDataItem(put)
					.addOnFailureListener(e -> Log.d(TAG, "Übertragung an die Uhr nicht möglich: " + e.getMessage()));
		} catch (RuntimeException e) {
			Log.d(TAG, "Wear nicht verfügbar", e);
		}
	}

	private static String amountText(ShoppingRepository.ShoppingEntry e) {
		String amount = de.bulling.barcodebuddyscanner.Helper.InventorySession.format(e.amount);
		return e.unit != null ? amount + " " + e.unit : amount;
	}
}
