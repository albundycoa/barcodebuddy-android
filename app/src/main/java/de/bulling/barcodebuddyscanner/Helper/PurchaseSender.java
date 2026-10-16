package de.bulling.barcodebuddyscanner.Helper;

import android.text.Html;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Api.BBApi;
import de.bulling.barcodebuddyscanner.Api.BBApiCallback;
import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;

/**
 * Schickt die offene Einkaufsliste als Einkauf an Barcode Buddy: Modus auf
 * "Einkauf" stellen, jeden Barcode so oft senden wie die Stückzahl vorgibt,
 * danach den vorherigen Modus wiederherstellen und die gesendeten Artikel in
 * den Verlauf verschieben.
 * <p>
 * Der Fortschritt wird pro Stück gespeichert. Bricht das Senden ab, wird beim
 * nächsten Versuch nur der Rest gesendet, nichts wird doppelt gebucht.
 * <p>
 * Die Instanz hält keine Activity fest und überlebt eine Drehung des Geräts;
 * eine neue Activity hängt sich mit {@link #attach(Listener)} wieder an.
 */
public class PurchaseSender {

	public interface Listener {
		void onProgress(int done, int total);

		void onFinished(Result result);
	}

	public static class Result {
		public int    unitsTotal;
		public int    unitsSent;
		/** null = es gab keinen Abbruch */
		public String error;
		/** Hinweis, z. B. wenn der Modus nicht zurückgesetzt werden konnte */
		public String warning;
		/** Artikel, die in der Liste geblieben sind */
		public int    remainingItems;
		/** Barcodes, die Barcode Buddy nicht kannte und in seine Liste "Unbekannte Barcodes" gelegt hat */
		public int    newProducts;
	}

	private static class Job {
		final ShoppingItem item;
		/** Preis je Lagereinheit für Barcode Buddy, null = kein Preis bekannt */
		final String       priceParam;
		int                remaining;
		int                sent;
		boolean            isNew;

		Job(ShoppingItem item, int remaining) {
			this.item      = item;
			this.remaining = remaining;
			Double perStock = item.effectivePricePerStockUnit();
			this.priceParam = perStock == null ? null
					: new BigDecimal(perStock).setScale(5, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
		}
	}

	private static final int MODE_PURCHASE = 2;

	private final BBApi              api;
	private final ShoppingRepository repo;
	private final Result             result = new Result();
	private final List<Job>          jobs   = new ArrayList<>();

	private Listener listener;
	private Result   finishedResult;
	private boolean  running;
	private int      done;
	private int      jobIndex;
	private Integer  previousMode;
	private boolean  modeChanged;

	public PurchaseSender(BBApi api, ShoppingRepository repo) {
		this.api  = api;
		this.repo = repo;
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
				if (remaining > 0) {
					jobs.add(new Job(item, remaining));
					result.unitsTotal += remaining;
				}
			}
			if (jobs.isEmpty()) {
				finish();
				return;
			}
			readMode();
		});
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
				abortBeforeSending(errorCode, errorMessage);
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
				abortBeforeSending(errorCode, errorMessage);
			}
		});
	}

	private void abortBeforeSending(int errorCode, String errorMessage) {
		result.error = describe(errorCode, errorMessage);
		finish();
	}

	private void sendNext() {
		while (jobIndex < jobs.size() && jobs.get(jobIndex).remaining <= 0) {
			jobIndex++;
		}
		if (jobIndex >= jobs.size()) {
			restoreMode();
			return;
		}
		final Job job = jobs.get(jobIndex);
		api.postBarcode(job.item.barcode, job.priceParam, new BBApiCallback() {
			@Override
			public void onResult(Object value) {
				if (!(value instanceof String)) {
					skipRest(job, "Unerwartete Antwort vom Server");
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
						job.item.displayName(), job.item.effectivePrice(), job.item.effectiveScanPrice());
				reportProgress();
				sendNext();
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				String text = describe(errorCode, errorMessage);
				if (errorCode == BBApiCallback.ERROR_NETWORK || errorCode == BBApiCallback.ERROR_UNAUTHORIZED) {
					// Der Server ist nicht erreichbar oder lehnt uns ab: alles Weitere würde auch scheitern
					repo.updateProgress(job.item.id, job.item.sentCount + job.sent, job.item.message, text,
							null, null, null);
					result.error = text;
					restoreMode();
				} else {
					skipRest(job, text);
					sendNext();
				}
			}
		});
	}

	/** Überspringt die restlichen Stücke dieses Artikels, die anderen Artikel werden weiter gesendet. */
	private void skipRest(Job job, String error) {
		repo.updateProgress(job.item.id, job.item.sentCount + job.sent, job.item.message, error, null, null, null);
		done += job.remaining;
		job.remaining = 0;
		reportProgress();
	}

	private void restoreMode() {
		if (!modeChanged || previousMode == null) {
			archive();
			return;
		}
		api.setMode(previousMode, new BBApiCallback() {
			@Override
			public void onResult(Object value) {
				archive();
			}

			@Override
			public void onError(int errorCode, String errorMessage, Integer statusCode) {
				result.warning = "Der vorherige Barcode-Buddy-Modus konnte nicht wiederhergestellt werden.";
				archive();
			}
		});
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
				return "Server nicht erreichbar" + (detail.isEmpty() ? "" : " (" + detail + ")");
			case BBApiCallback.ERROR_UNAUTHORIZED:
				return "Ungültiger API-Schlüssel";
			default:
				return detail.isEmpty() ? "Unbekannter Fehler" : detail;
		}
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
