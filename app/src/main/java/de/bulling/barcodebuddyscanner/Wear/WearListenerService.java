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
import de.bulling.barcodebuddyscanner.Helper.ShoppingEdit;
import de.bulling.barcodebuddyscanner.R;

/**
 * Nimmt Befehle der Uhr an: Einkaufsliste abhaken, Stückzahl ändern, Scan entfernen oder den
 * aktuellen Stand anfordern. Danach geht der neue Stand an die Uhr zurück.
 */
public class WearListenerService extends WearableListenerService {

	private static final String TAG = "WearListener";

	private static String text(JsonObject cmd, String key) {
		return cmd.has(key) && !cmd.get(key).isJsonNull() ? cmd.get(key).getAsString() : "";
	}

	@Override
	public void onMessageReceived(MessageEvent event) {
		if (WearUpdater.PATH_VERSION_REPLY.equals(event.getPath())) {
			// Die Uhr nennt ihre Version, bevor das Handy ein Update sendet
			WearUpdater.onVersionReply(event.getData());
			return;
		}
		if (!WearSync.PATH_ACTION.equals(event.getPath())) {
			return;
		}
		final Context app = getApplicationContext();
		Log.d(TAG, "Befehl von der Uhr: " + new String(event.getData(), StandardCharsets.UTF_8));
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
					GrocySync.pushShoppingDone(app);
					ScanNotifier.show(app);
					break;
				case "del":
					repo.deleteItem(cmd.get("id").getAsLong());
					GrocySync.pushShoppingDone(app);
					ScanNotifier.show(app);
					break;
				case "shop_del":
					ShoppingEdit.delete(app, cmd.get("id").getAsLong(), error -> WearSync.push(app));
					break;
				case "shop_add": {
					double delta = cmd.get("x").getAsDouble();
					if (delta > 0 && delta <= 1000) {
						ShoppingEdit.addMore(app, cmd.get("p").getAsLong(), delta, error -> WearSync.push(app));
					}
					break;
				}
				case "shop_note": {
					String text = text(cmd, "t").trim();
					double amount = cmd.has("x") ? cmd.get("x").getAsDouble() : 1;
					if (!text.isEmpty() && text.length() <= 200 && amount > 0 && amount <= 1000) {
						String group = text(cmd, "g");
						ShoppingEdit.addNote(app, text, amount, group.isEmpty() ? null : group, error -> WearSync.push(app));
					}
					break;
				}
				case "stock":
					// Die Uhr öffnet die Bestandsseite: aktuellen Bestand live aus Grocy holen und schicken
					WearStock.refresh(app, null, null);
					break;
				case "consume":
				case "open": {
					long product = cmd.get("p").getAsLong();
					double amount = cmd.get("x").getAsDouble();
					// Die Uhr ist nur eine Fernbedienung: nichts Unsinniges an Grocy weiterreichen
					if (!(amount > 0) || amount > 100000) {
						break;
					}
					boolean consume = "consume".equals(action);
					String label = getString(consume ? R.string.stock_booked_consume : R.string.stock_booked_open,
							text(cmd, "n"), text(cmd, "t"));
					if (consume) {
						WearStock.consume(app, product, amount, label);
					} else {
						WearStock.open(app, product, amount, label);
					}
					break;
				}
				case "undo":
					WearStock.undo(app, text(cmd, "tx"));
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
