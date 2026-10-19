package de.bulling.barcodebuddyscanner.Helper;

import de.bulling.barcodebuddyscanner.R;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.BBApi;
import de.bulling.barcodebuddyscanner.Api.BBApiCallback;
import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ShoppingEntry;

/**
 * Schickt die offene Einkaufsliste als Einkauf los. Produkte, die Grocy kennt und deren
 * Menge die App sicher weiß, werden direkt in Grocy gebucht. Alles andere (unbekannte
 * Barcodes, Tara-Produkte, Barcodes ohne eigene Menge) geht über Barcode Buddy: Modus auf
 * "Einkauf" stellen, jeden Barcode so oft senden wie die Stückzahl vorgibt, danach den
 * vorherigen Modus wiederherstellen.
 * <p>
 * Der Fortschritt wird gespeichert. Bricht das Senden ab, wird beim nächsten Versuch nur der
 * Rest gesendet, nichts wird doppelt gebucht. Gesendete Artikel wandern in den Verlauf.
 * <p>
 * Die Instanz hält keine Activity fest und überlebt eine Drehung des Geräts; eine neue
 * Activity hängt sich mit {@link #attach(Listener)} wieder an.
 */
public class PurchaseSender {

	public interface Listener {
		void onProgress(int done, int total);

		void onFinished(Result result);
	}

	public static class Result {
		public int    unitsTotal;
		public int    unitsSent;
		/** Davon direkt in Grocy gebucht */
		public int    directUnits;
		/** null = es gab keinen Abbruch */
		public String error;
		/** Hinweis, z. B. wenn der Modus nicht zurückgesetzt werden konnte */
		public String warning;
		/** Artikel, die in der Liste geblieben sind */
		public int    remainingItems;
		/** Barcodes, die Barcode Buddy nicht kannte und in seine Liste "Unbekannte Barcodes" gelegt hat */
		public int    newProducts;
		/** Von Hand abgehakte Einträge der Einkaufsliste, die an Grocy übertragen wurden */
		public int    manualUnits;
	}

	private static class Job {
		final ShoppingItem item;
		/** Preis je Lagereinheit, null = kein Preis bekannt */
		final Double       pricePerStock;
		int                remaining;
		int                sent;
		boolean            isNew;

		Job(ShoppingItem item, int remaining) {
			this.item          = item;
			this.remaining     = remaining;
			this.pricePerStock = item.effectivePricePerStockUnit();
		}

		/** Preis für Barcode Buddy als Text mit Punkt */
		String priceParam() {
			return pricePerStock == null ? null
					: new BigDecimal(pricePerStock).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
		}
	}

	private static final int MODE_PURCHASE = 2;

	private static final ExecutorService GROCY_IO = Executors.newSingleThreadExecutor();

	private final BBApi               api;
	private final GrocyClient         grocy;
	private final ShoppingRepository  repo;
	private final Handler             main   = new Handler(Looper.getMainLooper());
	private final Result              result = new Result();
	private final List<Job>           directJobs = new ArrayList<>();
	private final List<Job>           bbJobs     = new ArrayList<>();
	private final List<ShoppingEntry> manualJobs = new ArrayList<>();
	private final boolean             bookManual;
	private int                       manualIndex;
	/** Stück, die nur über Barcode Buddy gebucht werden könnten, aber Barcode Buddy ist nicht gekoppelt */
	private int                       skippedNoBb;

	private Listener listener;
	private Result   finishedResult;
	private boolean  running;
	private int      done;
	private int      directIndex;
	private int      bbIndex;
	private Integer  previousMode;
	private boolean  modeChanged;

	/**
	 * @param grocy Zugriff auf Grocy für direkte Buchungen, oder null: dann läuft alles über Barcode Buddy
	 */
	/**
	 * @param bookManual von Hand abgehakte Produkte der Einkaufsliste einbuchen (sonst nur in Grocy abhaken)
	 */
	public PurchaseSender(BBApi api, GrocyClient grocy, boolean bookManual, ShoppingRepository repo) {
		this.api        = api;
		this.grocy      = grocy;
		this.bookManual = bookManual;
		this.repo       = repo;
	}

	public boolean isRunning() {
		return running;
	}

	/** Hängt einen Listener an und liefert ihm sofort den aktuellen Stand. */
	public void attach(Listener l) {
		listener = l;
		if (l == null) {
			return;
		}
		if (running) {
			l.onProgress(done, result.unitsTotal);
		} else if (finishedResult != null) {
			Result r = finishedResult;
			finishedResult = null;
			l.onFinished(r);
		}
	}

	public void detach() {
		listener = null;
	}

	public void start() {
		if (running) {
			return;
		}
		running = true;
		repo.getOpenList(openList -> {
			for (ShoppingItem item : openList.items) {
				int remaining = item.quantity - item.sentCount;
				if (remaining <= 0) {
					continue;
				}
				Job job = new Job(item, remaining);
				if (grocy != null && item.canBookDirectly()) {
					directJobs.add(job);
				} else if (api == null) {
					// Ohne Barcode Buddy lassen sich unbekannte Barcodes nicht buchen: sie bleiben in der Liste
					skippedNoBb += remaining;
					continue;
				} else {
					bbJobs.add(job);
				}
				result.unitsTotal += remaining;
			}
			if (grocy == null) {
				finishLoading();
				return;
			}
			repo.getShoppingList(shopping -> {
				for (ShoppingEntry e : shopping.entries) {
					// Nur, was von Hand abgehakt wurde: Einträge, die Grocy schon erledigt hatte, und durch Scans erledigte laufen anders
					if (e.needsBooking()) {
						manualJobs.add(e);
						result.unitsTotal++;
					}
				}
				finishLoading();
			});
		});
	}

	private void finishLoading() {
		if (skippedNoBb > 0) {
			result.warning = Texts.get(R.string.send_warn_no_bb, skippedNoBb);
		}
		if (directJobs.isEmpty() && bbJobs.isEmpty() && manualJobs.isEmpty()) {
			if (skippedNoBb > 0) {
				result.error = Texts.get(R.string.send_err_no_bb);
			}
			finish();
			return;
		}
		preflight();
	}

	// ---------------------------------------------------------------- Vorabprüfung

	/**
	 * Prüft vor der ersten Buchung, ob Grocy und Barcode Buddy antworten, damit der Einkauf nicht halb
	 * gesendet wird. Fällt die Prüfung durch, bleibt alles unverändert in der Liste.
	 */
	private void preflight() {
		final boolean needGrocy = grocy != null && (!directJobs.isEmpty() || !manualJobs.isEmpty());
		if (!needGrocy) {
			preflightBarcodeBuddy();
			return;
		}
		GROCY_IO.execute(() -> {
			IOException failure = null;
			try {
				grocy.get("system/info");
			} catch (IOException e) {
				failure = e;
			}
			final IOException error = failure;
			main.post(() -> {
				if (error == null) {
					preflightBarcodeBuddy();
					return;
				}
				int status = error instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) error).statusCode : 0;
				abortBeforeStart(status == 401 || status == 403 ? Texts.get(R.string.grocy_err_key)
						: status == 0 ? Texts.get(R.string.grocy_err_unreachable) : Texts.get(R.string.grocy_err_bad_answer, status));
			});
		});
	}

	private void preflightBarcodeBuddy() {
		if (bbJobs.isEmpty()) {
			bookDirectNext();
			return;
		}
		api.getMode(new BBApiCallback() {
			@Override
			public void onResult(Object value) {
				bookDirectNext();
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				abortBeforeStart(Texts.get(R.string.send_err_barcode_buddy, describe(errorCode, errorMessage)));
			}
		});
	}

	private void abortBeforeStart(String reason) {
		result.error = reason;
		finish();
	}

	// ---------------------------------------------------------------- Direkt in Grocy

	private void bookDirectNext() {
		while (directIndex < directJobs.size() && directJobs.get(directIndex).remaining <= 0) {
			directIndex++;
		}
		if (directIndex >= directJobs.size()) {
			startBarcodeBuddy();
			return;
		}
		final Job job = directJobs.get(directIndex);
		final ShoppingItem item = job.item;
		final double amount = item.stockPerScan * job.remaining;
		final JsonObject body = purchaseBody(amount, job.pricePerStock, item.bestBeforeDays, item.shopId);
		GROCY_IO.execute(() -> {
			IOException failure = null;
			try {
				grocy.post("stock/products/" + item.productId + "/add", body.toString());
				removeFromShoppingList(item.productId, amount);
			} catch (IOException e) {
				failure = e;
			}
			final IOException error = failure;
			main.post(() -> onDirectResult(job, amount, error));
		});
	}

	private static JsonObject purchaseBody(double amount, Double pricePerStock, Integer bestBeforeDays, Long shopId) {
		JsonObject body = new JsonObject();
		body.addProperty("amount", amount);
		body.addProperty("transaction_type", "purchase");
		body.addProperty("best_before_date", bestBeforeDate(bestBeforeDays));
		if (pricePerStock != null) {
			body.addProperty("price", new BigDecimal(pricePerStock).setScale(5, RoundingMode.HALF_UP));
		}
		if (shopId != null) {
			body.addProperty("shopping_location_id", shopId);
		}
		return body;
	}

	/** Wie Barcode Buddy: heute plus Standard-Haltbarkeit, -1 bedeutet unbegrenzt. */
	public static String bestBeforeDate(Integer days) {
		if (days != null && days == -1) {
			return "2999-12-31";
		}
		Calendar calendar = Calendar.getInstance();
		calendar.add(Calendar.DAY_OF_YEAR, days == null ? 0 : days);
		return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
	}

	/** Wie Barcode Buddy: gekaufte Menge von der Einkaufsliste in Grocy abziehen; Fehler sind unwichtig. */
	private void removeFromShoppingList(long productId, double amount) {
		JsonObject body = new JsonObject();
		body.addProperty("product_id", productId);
		body.addProperty("product_amount", amount);
		try {
			grocy.post("stock/shoppinglist/remove-product", body.toString());
		} catch (IOException ignored) {
			// Das Produkt stand nicht auf der Einkaufsliste
		}
	}

	private void onDirectResult(Job job, double amount, IOException error) {
		ShoppingItem item = job.item;
		if (error == null) {
			int count = job.remaining;
			job.sent += count;
			job.remaining = 0;
			done += count;
			result.unitsSent += count;
			result.directUnits += count;
			String text = Texts.get(R.string.send_grocy_booked, trim(amount),
					item.stockUnitName != null ? item.stockUnitName : "", item.displayName());
			repo.updateProgress(item.id, item.sentCount + job.sent, text.replaceAll("\\s+", " ").trim(), null,
					item.displayName(), item.effectivePrice(), item.effectiveScanPrice(), item.shopName);
			reportProgress();
			bookDirectNext();
			return;
		}
		int status = error instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) error).statusCode : 0;
		if (status == 401 || status == 403) {
			abortAll(item, job, Texts.get(R.string.grocy_err_key));
		} else if (status == 0) {
			abortAll(item, job, Texts.get(R.string.grocy_err_unreachable));
		} else {
			// Grocy hat diese Buchung abgelehnt, die übrigen Artikel werden trotzdem gebucht
			skipRest(job, Texts.get(R.string.grocy_err_rejected, status));
			bookDirectNext();
		}
	}

	/** Grocy nicht erreichbar oder Schlüssel ungültig: weitere Buchungen würden ebenfalls scheitern. */
	private void abortAll(ShoppingItem item, Job job, String reason) {
		repo.updateProgress(item.id, item.sentCount + job.sent, item.message, reason, null, null, null, null);
		result.error = reason;
		archive();
	}

	// ---------------------------------------------------------------- Über Barcode Buddy

	private void startBarcodeBuddy() {
		if (bbJobs.isEmpty()) {
			afterBarcodeBuddy();
			return;
		}
		readMode();
	}

	/** Nach den Buchungen: von Hand abgehakte Einträge übertragen, außer es gab einen Abbruch. */
	private void afterBarcodeBuddy() {
		if (result.error != null || manualJobs.isEmpty()) {
			archive();
			return;
		}
		transferManualNext();
	}

	private void transferManualNext() {
		if (manualIndex >= manualJobs.size()) {
			archive();
			return;
		}
		final ShoppingEntry entry = manualJobs.get(manualIndex++);
		GROCY_IO.execute(() -> {
			IOException failure = null;
			try {
				if (entry.isNote || !bookManual) {
					// Notizen und "nur abhaken": den Eintrag in Grocy als erledigt markieren
					grocy.put("objects/shopping_list/" + entry.id, "{\"done\":1}");
				} else {
					double remaining = Math.max(0, entry.stockAmount - entry.scanned);
					if (remaining > 0) {
						grocy.post("stock/products/" + entry.productId + "/add",
								purchaseBody(remaining, entry.lastPrice, entry.bestBeforeDays, entry.shopId).toString());
					}
					// Gekauft: der Eintrag verschwindet von der Einkaufsliste
					grocy.delete("objects/shopping_list/" + entry.id);
				}
			} catch (IOException e) {
				failure = e;
			}
			final IOException error = failure;
			main.post(() -> onManualResult(entry, error));
		});
	}

	private void onManualResult(ShoppingEntry entry, IOException error) {
		if (error == null) {
			repo.clearShoppingManual(entry.id);
			done++;
			result.unitsSent++;
			result.manualUnits++;
			reportProgress();
			transferManualNext();
			return;
		}
		int status = error instanceof GrocyClient.HttpException ? ((GrocyClient.HttpException) error).statusCode : 0;
		if (status == 401 || status == 403) {
			result.error = Texts.get(R.string.grocy_err_key);
			archive();
		} else if (status == 0) {
			result.error = Texts.get(R.string.grocy_err_unreachable);
			archive();
		} else {
			// Dieser Eintrag ging nicht durch, die übrigen werden trotzdem übertragen
			done++;
			result.warning = Texts.get(R.string.send_err_entry_failed, entry.name, status);
			reportProgress();
			transferManualNext();
		}
	}

	private void readMode() {
		api.getMode(new BBApiCallback() {
			@Override
			public void onResult(Object value) {
				if (value instanceof Integer) {
					previousMode = (Integer) value;
				}
				switchToPurchase();
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				result.error = describe(errorCode, errorMessage);
				archive();
			}
		});
	}

	private void switchToPurchase() {
		if (previousMode != null && previousMode == MODE_PURCHASE) {
			sendNext();
			return;
		}
		api.setMode(MODE_PURCHASE, new BBApiCallback() {
			@Override
			public void onResult(Object value) {
				modeChanged = true;
				sendNext();
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				result.error = describe(errorCode, errorMessage);
				archive();
			}
		});
	}

	private void sendNext() {
		while (bbIndex < bbJobs.size() && bbJobs.get(bbIndex).remaining <= 0) {
			bbIndex++;
		}
		if (bbIndex >= bbJobs.size()) {
			restoreMode();
			return;
		}
		final Job job = bbJobs.get(bbIndex);
		api.postBarcode(job.item.barcode, job.priceParam(), new BBApiCallback() {
			@Override
			public void onResult(Object value) {
				if (!(value instanceof String)) {
					skipRest(job, Texts.get(R.string.send_err_unexpected_server));
					sendNext();
					return;
				}
				job.sent++;
				job.remaining--;
				done++;
				result.unitsSent++;
				String text = cleanMessage((String) value);
				// Barcode Buddy meldet unbekannte Barcodes mit festen englischen Texten
				if (!job.isNew && (text.contains("Unknown barcode") || text.contains("Unknown product"))) {
					job.isNew = true;
					result.newProducts++;
				}
				repo.updateProgress(job.item.id, job.item.sentCount + job.sent, text, null,
						job.item.displayName(), job.item.effectivePrice(), job.item.effectiveScanPrice(),
						job.item.shopName);
				reportProgress();
				sendNext();
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				String text = describe(errorCode, errorMessage);
				if (errorCode == BBApiCallback.ERROR_NETWORK || errorCode == BBApiCallback.ERROR_UNAUTHORIZED) {
					// Der Server ist nicht erreichbar oder lehnt uns ab: alles Weitere würde auch scheitern
					repo.updateProgress(job.item.id, job.item.sentCount + job.sent, job.item.message, text,
							null, null, null, null);
					result.error = text;
					restoreMode();
				} else {
					skipRest(job, text);
					sendNext();
				}
			}
		});
	}

	private void restoreMode() {
		if (!modeChanged || previousMode == null) {
			afterBarcodeBuddy();
			return;
		}
		api.setMode(previousMode, new BBApiCallback() {
			@Override
			public void onResult(Object value) {
				afterBarcodeBuddy();
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				result.warning = Texts.get(R.string.send_warn_mode_restore);
				afterBarcodeBuddy();
			}
		});
	}

	// ---------------------------------------------------------------- Abschluss

	/** Überspringt die restlichen Stücke dieses Artikels, die anderen Artikel werden weiter gesendet. */
	private void skipRest(Job job, String error) {
		repo.updateProgress(job.item.id, job.item.sentCount + job.sent, job.item.message, error,
				null, null, null, null);
		done += job.remaining;
		job.remaining = 0;
		reportProgress();
	}

	private void archive() {
		repo.finishSend(archive -> {
			result.remainingItems = archive.remainingItems;
			finish();
		});
	}

	private void finish() {
		running = false;
		if (listener != null) {
			listener.onFinished(result);
		} else {
			finishedResult = result;
		}
	}

	private void reportProgress() {
		if (listener != null) {
			listener.onProgress(done, result.unitsTotal);
		}
	}

	private static String describe(int errorCode, String errorMessage) {
		String detail = errorMessage == null ? "" : errorMessage;
		switch (errorCode) {
			case BBApiCallback.ERROR_NETWORK:
				return detail.isEmpty() ? Texts.get(R.string.bb_err_server_unreachable) : Texts.get(R.string.bb_err_server_unreachable_detail, detail);
			case BBApiCallback.ERROR_UNAUTHORIZED:
				return Texts.get(R.string.bb_err_key);
			default:
				return detail.isEmpty() ? Texts.get(R.string.bb_err_unknown) : detail;
		}
	}

	/** 8.0 -> "8", 0.5 -> "0,5" */
	private static String trim(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value).replace('.', ',');
	}

	/**
	 * Barcode Buddy schickt den Text HTML-maskiert und teils mit HTML-Hinweisen
	 * (z. B. orangefarbene Warnungen) zurück.
	 */
	private static String cleanMessage(String raw) {
		String text = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString();
		return text.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
	}
}
