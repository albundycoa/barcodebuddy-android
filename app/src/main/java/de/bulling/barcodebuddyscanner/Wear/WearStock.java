package de.bulling.barcodebuddyscanner.Wear;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyStock;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Api.StockOverviewLoader;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;
import de.bulling.barcodebuddyscanner.Helper.StockOverview;
import de.bulling.barcodebuddyscanner.Helper.WearStockJson;
import de.bulling.barcodebuddyscanner.R;

/**
 * Bestand für die Uhr: Das Handy holt ihn live aus Grocy und schickt ihn über die Data Layer (Pfad /bbs/stock). Auch die
 * Buchungen der Uhr (verbrauchen, öffnen, rückgängig) laufen hier über das Handy, weil nur es die Zugangsdaten für Grocy
 * hat. Nach jeder Buchung geht der neue Stand mit einer Meldung (und der Nummer zum Rückgängigmachen) an die Uhr.
 */
public final class WearStock {

	private static final String TAG = "WearStock";

	/** Pfad des Datenelements, das die Uhr liest. */
	public static final String PATH_STOCK = "/bbs/stock";

	private static final ExecutorService IO = Executors.newSingleThreadExecutor();

	private WearStock() {
	}

	/** Eine Buchung in Grocy; liefert die Nummer zum Zurücknehmen (null = nicht möglich). */
	private interface Booking {
		String run(GrocyClient client) throws IOException;
	}

	/** Lädt den Bestand und schickt ihn an die Uhr, optional mit der Meldung zur letzten Buchung. */
	public static void refresh(Context context, String message, String transaction) {
		final Context app = context.getApplicationContext();
		final GrocyClient grocy = new SharedPrefHelper(app).initGrocyClient();
		if (grocy == null) {
			send(app, WearStockJson.build(new ArrayList<>(), System.currentTimeMillis(), "%1$s",
					null, null, app.getString(R.string.inventory_no_grocy)));
			return;
		}
		ShoppingRepository.get(app).getProducts("", cached -> IO.execute(() -> {
			String json;
			try {
				List<StockOverview.Entry> entries = StockOverviewLoader.load(grocy, cached);
				json = WearStockJson.build(entries, System.currentTimeMillis(), app.getString(R.string.stock_opened, "%1$s"),
						message, transaction, null);
			} catch (IOException e) {
				json = WearStockJson.build(new ArrayList<>(), System.currentTimeMillis(), "%1$s", null, null,
						app.getString(R.string.grocy_err_unreachable));
			}
			send(app, json);
		}));
	}

	/** Verbraucht die Menge; label ist die Meldung für die Uhr bei Erfolg, z. B. "Cola: 1 Pack verbraucht". */
	public static void consume(final Context context, final long productId, final double amount, final String label) {
		book(context, label, client -> GrocyStock.consumeBooking(client, productId, amount));
	}

	public static void open(final Context context, final long productId, final double amount, final String label) {
		book(context, label, client -> GrocyStock.open(client, productId, amount));
	}

	/** Nimmt eine Buchung zurück (Nummer aus der Meldung, die die Uhr zuletzt bekam). */
	public static void undo(final Context context, final String transaction) {
		final Context app = context.getApplicationContext();
		final GrocyClient grocy = new SharedPrefHelper(app).initGrocyClient();
		if (grocy == null || transaction == null || transaction.isEmpty()) {
			return;
		}
		IO.execute(() -> {
			String message;
			try {
				GrocyStock.undo(grocy, transaction);
				message = app.getString(R.string.stock_undone);
				GrocySync.start(app, null);
			} catch (IOException e) {
				message = app.getString(R.string.stock_err, e.getMessage());
			}
			refresh(app, message, null);
		});
	}

	private static void book(Context context, final String label, final Booking booking) {
		final Context app = context.getApplicationContext();
		final GrocyClient grocy = new SharedPrefHelper(app).initGrocyClient();
		if (grocy == null) {
			refresh(app, null, null);
			return;
		}
		IO.execute(() -> {
			String message;
			String transaction = null;
			try {
				transaction = booking.run(grocy);
				message = label;
				GrocySync.start(app, null);
			} catch (IOException e) {
				int status = e instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) e).statusCode : 0;
				message = status == 400 ? app.getString(R.string.stock_err_rejected)
						: status == 0 ? app.getString(R.string.grocy_err_unreachable)
						: app.getString(R.string.grocy_err_rejected, status);
			}
			refresh(app, message, transaction);
		});
	}

	private static void send(Context app, String json) {
		PutDataMapRequest request = PutDataMapRequest.create(PATH_STOCK);
		request.getDataMap().putString("json", json);
		PutDataRequest put = request.asPutDataRequest().setUrgent();
		try {
			Wearable.getDataClient(app).putDataItem(put)
					.addOnFailureListener(e -> Log.d(TAG, "Bestand nicht an die Uhr übertragbar: " + e.getMessage()));
		} catch (RuntimeException e) {
			Log.d(TAG, "Wear nicht verfügbar", e);
		}
	}
}
