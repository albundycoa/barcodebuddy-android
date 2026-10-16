package de.bulling.barcodebuddyscanner.Wear;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.ScanNotifier;

/**
 * Nimmt Befehle der Uhr an: Einkaufsliste abhaken, Stückzahl ändern, Scan entfernen oder den
 * aktuellen Stand anfordern. Danach geht der neue Stand an die Uhr zurück.
 */
public class WearListenerService extends WearableListenerService {

	private static final String TAG = "WearListener";

	@Override
	public void onMessageReceived(MessageEvent event) {
		if (!WearSync.PATH_ACTION.equals(event.getPath())) {
			return;
		}
		final Context app = getApplicationContext();
		try {
			JsonObject cmd = JsonParser.parseString(new String(event.getData(), StandardCharsets.UTF_8)).getAsJsonObject();
			String action = cmd.get("a").getAsString();
			ShoppingRepository repo = ShoppingRepository.get(app);
			switch (action) {
				case "done":
					repo.setShoppingDone(cmd.get("id").getAsLong(), cmd.get("v").getAsBoolean());
					// Läuft hinter dem Schreibzugriff in derselben Warteschlange und sieht daher den neuen Stand
					GrocySync.pushShoppingDone(app);
					break;
				case "qty":
					repo.setQuantity(cmd.get("id").getAsLong(), cmd.get("q").getAsInt());
					ScanNotifier.show(app);
					break;
				case "del":
					repo.deleteItem(cmd.get("id").getAsLong());
					ScanNotifier.show(app);
					break;
				default:
					// "sync": die Uhr möchte nur den aktuellen Stand
					break;
			}
		} catch (RuntimeException e) {
			Log.w(TAG, "Befehl der Uhr nicht verständlich", e);
		}
		WearSync.push(app);
	}
}
