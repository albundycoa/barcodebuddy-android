package de.bulling.barcodebuddyscanner;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.ScanNotifier;

/**
 * Knöpfe der Scan-Benachrichtigung (auch auf der Smartwatch): eine Stückzahl weniger oder den
 * zuletzt gescannten Artikel entfernen.
 */
public class ScanActionReceiver extends BroadcastReceiver {

	public static final String ACTION_MINUS  = "de.bulling.barcodebuddyscanner.SCAN_MINUS";
	public static final String ACTION_REMOVE = "de.bulling.barcodebuddyscanner.SCAN_REMOVE";
	public static final String EXTRA_ITEM_ID  = "item_id";
	public static final String EXTRA_QUANTITY = "quantity";

	@Override
	public void onReceive(Context context, Intent intent) {
		long id = intent.getLongExtra(EXTRA_ITEM_ID, -1);
		int quantity = intent.getIntExtra(EXTRA_QUANTITY, 1);
		if (id < 0 || intent.getAction() == null) {
			return;
		}
		ShoppingRepository repo = ShoppingRepository.get(context);
		if (ACTION_MINUS.equals(intent.getAction()) && quantity > 1) {
			repo.setQuantity(id, quantity - 1);
		} else {
			// "Entfernen", oder "−1" bei einer einzelnen Packung
			repo.deleteItem(id);
		}
		// Läuft nach dem Schreibzugriff auf demselben Thread, zeigt also schon den neuen Stand
		ScanNotifier.show(context);
	}
}
