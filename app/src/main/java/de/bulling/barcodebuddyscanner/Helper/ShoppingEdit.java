package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyShopping;
import de.bulling.barcodebuddyscanner.Api.GrocyStock;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.R;

/**
 * Die Einkaufsliste in Grocy ändern: Einträge löschen, Produkte und freie Einträge (mit Produktgruppe) hinzufügen. Handy-Seiten und
 * die Befehle der Uhr nutzen denselben Weg. Die Schreibzugriffe laufen im Hintergrund, das Ergebnis kommt auf dem Main-Thread.
 */
public final class ShoppingEdit {

	/** Ergebnis: null = geklappt, sonst ein Text für den Nutzer. */
	public interface Result {
		void onDone(String error);
	}

	private static final ExecutorService IO   = Executors.newSingleThreadExecutor();
	private static final Handler         MAIN = new Handler(Looper.getMainLooper());

	private ShoppingEdit() {
	}

	/** Nimmt einen Eintrag von der Einkaufsliste (in Grocy und in der lokalen Kopie). */
	public static void delete(Context context, final long entryId, final Result result) {
		run(context, result, (client, app) -> {
			GrocyShopping.deleteEntry(client, entryId);
			ShoppingRepository.get(app).removeShoppingEntry(entryId);
		});
	}

	/**
	 * Setzt ein Produkt mit Menge in der Einheit quId (0 = Grocys Standard) auf die Liste. Steht es mit dieser Einheit schon
	 * darauf, wird die Menge auf den neuen Wert gesetzt.
	 */
	public static void addProduct(Context context, final long productId, final double amount, final long quId, final Result result) {
		run(context, result, (client, app) -> {
			if (quId > 0) {
				GrocyStock.addToShoppingList(client, productId, amount, quId);
			} else {
				GrocyStock.addToShoppingList(client, productId, amount);
			}
			GrocySync.start(app, null);
		});
	}

	/**
	 * Für die Uhr: noch {@code delta} von dem Produkt auf die Liste, in der Einkaufseinheit des Produkts (z. B. Pack), zu einem
	 * vorhandenen Eintrag dieser Einheit dazugezählt.
	 */
	public static void addMore(Context context, final long productId, final double delta, final Result result) {
		run(context, result, (client, app) -> {
			de.bulling.barcodebuddyscanner.Helper.StockOverview.Product setup = GrocyShopping.loadProduct(client, productId);
			long quId = setup == null ? 0 : setup.quPurchase;
			if (quId > 0) {
				GrocyStock.addMoreToShoppingList(client, productId, delta, quId);
			} else {
				GrocyStock.addToShoppingList(client, productId, delta);
			}
			GrocySync.start(app, null);
		});
	}

	/** Legt einen freien Eintrag an, optional mit Produktgruppe (nur in der App, damit er richtig einsortiert wird). */
	public static void addNote(Context context, final String text, final double amount, final String groupName, final Result result) {
		run(context, result, (client, app) -> {
			long id = GrocyShopping.addNote(client, text.trim(), amount);
			// Die Gruppe zuerst merken: Der Abgleich danach holt den neuen Eintrag samt Gruppe in die Liste
			ShoppingRepository.get(app).setNoteGroup(id, groupName);
			GrocySync.start(app, null);
		});
	}

	private interface Work {
		void run(GrocyClient client, Context app) throws IOException;
	}

	private static void run(Context context, final Result result, final Work work) {
		final Context app = context.getApplicationContext();
		final GrocyClient client = new SharedPrefHelper(app).initGrocyClient();
		if (client == null) {
			finish(result, app.getString(R.string.product_no_grocy));
			return;
		}
		IO.execute(() -> {
			String error = null;
			try {
				work.run(client, app);
			} catch (IOException e) {
				error = describe(app, e);
			}
			finish(result, error);
		});
	}

	private static void finish(final Result result, final String error) {
		if (result != null) {
			MAIN.post(() -> result.onDone(error));
		}
	}

	static String describe(Context app, IOException e) {
		int status = e instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) e).statusCode : 0;
		return status == 0 ? app.getString(R.string.grocy_err_unreachable) : app.getString(R.string.grocy_err_rejected, status);
	}
}
