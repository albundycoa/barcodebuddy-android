package de.bulling.barcodebuddyscanner.Helper;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.MainActivity;
import de.bulling.barcodebuddyscanner.R;
import de.bulling.barcodebuddyscanner.ScanActionReceiver;

/**
 * Zeigt nach jedem Scan eine Benachrichtigung mit dem zuletzt gescannten Produkt und dem
 * voraussichtlichen Gesamtbetrag des Einkaufs. Android spiegelt sie auf gekoppelte Smartwatches, die
 * Knöpfe "−1" und "Entfernen" funktionieren dort ebenfalls.
 */
public final class ScanNotifier {

	/** Neuer Kanalname: die Einstellungen eines bestehenden Kanals lassen sich nicht mehr ändern. */
	private static final String CHANNEL_ID      = "scan_info_alert";
	private static final String OLD_CHANNEL_ID  = "scan_info";
	private static final int    NOTIFICATION_ID = 3;

	private ScanNotifier() {
	}

	/** Lädt die aktuelle Liste und zeigt (oder entfernt) die Benachrichtigung. */
	public static void show(Context context) {
		final Context app = context.getApplicationContext();
		if (!SharedPrefHelper.isScanNotificationEnabled(app)
				|| !NotificationManagerCompat.from(app).areNotificationsEnabled()) {
			return;
		}
		ShoppingRepository.get(app).getOpenList(list -> post(app, list));
	}

	private static void post(Context app, ShoppingRepository.OpenList list) {
		NotificationManagerCompat manager = NotificationManagerCompat.from(app);
		if (list.items.isEmpty()) {
			manager.cancel(NOTIFICATION_ID);
			return;
		}
		ensureChannel(app);

		// Die Liste ist nach dem letzten Scan sortiert: der erste Eintrag ist das zuletzt gescannte Produkt
		ShoppingItem item = list.items.get(0);
		ReceiptPdf.Totals totals = ReceiptPdf.totals(list.items);

		String name = item.displayName() != null ? item.displayName() : item.barcode;
		String title = item.quantity > 1 ? app.getString(R.string.notif_title_qty, name, item.quantity) : name;

		Double scanPrice = item.effectiveScanPrice();
		String priceLine;
		if (scanPrice != null) {
			String unit = item.priceUnitName != null ? item.priceUnitName : app.getString(R.string.price_unit_scan);
			priceLine = Money.format(scanPrice * item.quantity)
					+ (item.quantity > 1 ? " (" + Money.format(scanPrice) + " / " + unit + ")" : "");
		} else {
			priceLine = app.getString(R.string.notif_no_price);
		}
		String total = app.getString(R.string.notif_total, Money.format(totals.sum))
				+ (totals.withoutPrice > 0 ? " " + app.getString(R.string.notif_without_price, totals.withoutPrice) : "");

		StringBuilder details = new StringBuilder();
		if (!item.known && !list.items.isEmpty() && list.cacheAvailable) {
			details.append(app.getString(R.string.notif_new_product)).append('\n');
		}
		if (item.shopName != null) {
			details.append(item.shopName).append('\n');
		}
		if (item.known && item.stock != null && item.stockUnitName != null) {
			details.append(app.getString(R.string.item_stock, trim(item.stock), item.stockUnitName)).append('\n');
		}
		details.append(priceLine).append('\n').append(total);

		PendingIntent open = PendingIntent.getActivity(app, 0,
				new Intent(app, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

		NotificationCompat.Builder builder = new NotificationCompat.Builder(app, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_scanner)
				.setContentTitle(title)
				.setContentText(priceLine + " · " + total)
				.setStyle(new NotificationCompat.BigTextStyle().bigText(details.toString()))
				.setContentIntent(open)
				.setOnlyAlertOnce(false)
				.setAutoCancel(false)
				.setCategory(NotificationCompat.CATEGORY_STATUS)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.setShowWhen(true)
				.addAction(0, app.getString(R.string.notif_action_minus),
						action(app, ScanActionReceiver.ACTION_MINUS, item))
				.addAction(0, app.getString(R.string.notif_action_remove),
						action(app, ScanActionReceiver.ACTION_REMOVE, item));
		try {
			manager.notify(NOTIFICATION_ID, builder.build());
		} catch (SecurityException ignored) {
			// Benachrichtigungen nicht erlaubt
		}
	}

	private static PendingIntent action(Context app, String action, ShoppingItem item) {
		Intent intent = new Intent(app, ScanActionReceiver.class)
				.setAction(action)
				.putExtra(ScanActionReceiver.EXTRA_ITEM_ID, item.id)
				.putExtra(ScanActionReceiver.EXTRA_QUANTITY, item.quantity);
		int requestCode = ScanActionReceiver.ACTION_MINUS.equals(action) ? 1 : 2;
		return PendingIntent.getBroadcast(app, requestCode, intent,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
	}

	private static void ensureChannel(Context app) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager manager = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
			manager.deleteNotificationChannel(OLD_CHANNEL_ID);
			NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
					app.getString(R.string.notif_channel_scan), NotificationManager.IMPORTANCE_DEFAULT);
			channel.setDescription(app.getString(R.string.notif_channel_scan_description));
			// Ohne Ton, aber mit kurzer Vibration: Stumme Benachrichtigungen zeigt die Samsung-Uhr nur leise
			// in der Liste an. Eine Vibration macht daraus einen Alarm, der auch auf der Uhr auffällt.
			channel.setSound(null, null);
			channel.enableVibration(true);
			channel.setVibrationPattern(new long[]{0, 120});
			manager.createNotificationChannel(channel);
		}
	}

	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', ',');
	}
}
