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
		addProduct(context, productId, amount, quId, SharedPrefHelper.getShoppingMainList(context), result);
	}

	/** Wie oben, auf der Einkaufsliste mit der Nummer listId. */
	public static void addProduct(Context context, final long productId, final double amount, final long quId, final long listId,
								  final Result result) {
		run(context, result, (client, app) -> {
			if (quId > 0) {
				GrocyStock.addToShoppingList(client, productId, amount, quId, listId);
			} else {
				GrocyStock.addProductToList(client, productId, amount, listId);
			}
			GrocySync.start(app, null);
		});
	}

	/** Schiebt einen Eintrag auf eine andere Einkaufsliste (steht das Produkt dort schon, werden die Mengen zusammengezählt). */
	public static void move(Context context, final long entryId, final Long productId, final double amount, final long quId,
							final long targetListId, final Result result) {
		run(context, result, (client, app) -> {
			GrocyStock.moveToList(client, entryId, productId == null ? 0 : productId, amount, quId, targetListId);
			ShoppingRepository.get(app).setShoppingEntryList(entryId, targetListId);
			GrocySync.start(app, null);
		});
	}

	/**
	 * Für die Uhr: noch {@code delta} von dem Produkt auf die Liste, in der Einkaufseinheit des Produkts (z. B. Pack), zu einem
	 * vorhandenen Eintrag dieser Einheit dazugezählt.
	 */
	public static void addMore(Context context, final long productId, final double delta, final Result result) {
		addMore(context, productId, delta, null, result);
	}

	/**
	 * Wie {@link #addMore(Context, long, double, Result)}, mit einem Angebot: Der Preis wird für die Berechnung der Liste und beim Einkauf
	 * verwendet, und eine Notiz mit dem Angebot steht am Eintrag in Grocy.
	 */
	public static void addMore(Context context, final long productId, final double delta, final Offer offer, final Result result) {
		run(context, result, (client, app) -> {
			de.bulling.barcodebuddyscanner.Helper.StockOverview.Product setup = GrocyShopping.loadProduct(client, productId);
			long quId = setup == null ? 0 : setup.quPurchase;
			long listId = SharedPrefHelper.getShoppingMainList(app);
			if (quId > 0) {
				GrocyStock.addMoreToShoppingList(client, productId, delta, quId, listId, offer == null ? null : offer.note());
			} else {
				GrocyStock.addProductToList(client, productId, delta, listId);
			}
			if (offer != null) {
				ShoppingRepository.get(app).setOffer(productId, null, offer.price, offer.validUntil, offer.shop);
			}
			GrocySync.start(app, null);
		});
	}

	/** Ein Angebot: Preis je Stück, Ende der Gültigkeit (yyyy-MM-dd oder null) und Geschäft (oder null). */
	public static final class Offer {
		public final double price;
		public final String validUntil;
		public final String shop;

		public Offer(double price, String validUntil, String shop) {
			this.price      = price;
			this.validUntil = validUntil;
			this.shop       = shop;
		}

		/** Text für die Notiz in Grocy, z. B. "Angebot 1,99 € bei Lidl bis 31.10.2026". */
		String note() {
			StringBuilder note = new StringBuilder("Angebot ").append(Money.format(price));
			if (shop != null && !shop.isEmpty()) {
				note.append(" bei ").append(shop);
			}
			if (validUntil != null && validUntil.length() == 10) {
				note.append(" bis ").append(validUntil, 8, 10).append('.').append(validUntil, 5, 7).append('.').append(validUntil, 0, 4);
			}
			return note.toString();
		}
	}

	/** Legt einen freien Eintrag an, optional mit Produktgruppe (nur in der App, damit er richtig einsortiert wird). */
	public static void addNote(Context context, final String text, final double amount, final String groupName, final Result result) {
		addNote(context, text, amount, groupName, null, result);
	}

	/** Wie oben, mit einem Angebot (Preis für die Berechnung der Liste, Text mit dem Angebot im Namen des Eintrags in Grocy). */
	public static void addNote(Context context, final String text, final double amount, final String groupName, final Offer offer,
							   final Result result) {
		addNote(context, text, amount, groupName, offer, SharedPrefHelper.getShoppingMainList(context), result);
	}

	/** Wie oben, auf der Einkaufsliste mit der Nummer listId. */
	public static void addNote(Context context, final String text, final double amount, final String groupName, final Offer offer,
							   final long listId, final Result result) {
		run(context, result, (client, app) -> {
			String body = offer == null ? text.trim() : text.trim() + " (" + offer.note() + ")";
			long id = GrocyShopping.addNote(client, body, amount, listId);
			// Die Gruppe zuerst merken: Der Abgleich danach holt den neuen Eintrag samt Gruppe in die Liste
			ShoppingRepository.get(app).setNoteGroup(id, groupName);
			if (offer != null) {
				ShoppingRepository.get(app).setOffer(null, id, offer.price, offer.validUntil, offer.shop);
			}
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
