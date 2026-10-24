package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.bulling.barcodebuddyscanner.Api.OpenFoodFacts;

/**
 * Produktbilder aus OpenFoodFacts (kleines Vorderseitenbild, wenige KB), je Barcode auf dem Gerät gespeichert. Bilder werden erst geladen,
 * wenn sie gebraucht werden, ein Barcode ohne Bild wird erst nach einer Woche wieder abgefragt. Abgeschaltet über
 * "Namen bei OpenFoodFacts suchen" in den Einstellungen.
 */
public final class ProductImages {

	private static final long RETRY_MS = 7L * 24 * 60 * 60 * 1000;
	/** OpenFoodFacts bittet um höchstens etwa 100 Abfragen je Minute */
	private static final long PAUSE_MS = 700;

	private static final LruCache<String, Bitmap> MEMORY = new LruCache<>(80);
	private static final ExecutorService IO = Executors.newSingleThreadExecutor();
	private static final Handler MAIN = new Handler(Looper.getMainLooper());
	private static final Set<String> RUNNING = new HashSet<>();
	private static long lastRequest;

	private ProductImages() {
	}

	private static File dir(Context context) {
		File dir = new File(context.getApplicationContext().getFilesDir(), "product_images");
		if (!dir.exists()) {
			//noinspection ResultOfMethodCallIgnored
			dir.mkdirs();
		}
		return dir;
	}

	private static File image(Context context, String barcode) {
		return new File(dir(context), barcode + ".jpg");
	}

	private static File missing(Context context, String barcode) {
		return new File(dir(context), barcode + ".none");
	}

	/** Das gespeicherte Bild oder null. */
	public static Bitmap cached(Context context, String barcode) {
		if (barcode == null) {
			return null;
		}
		Bitmap bitmap = MEMORY.get(barcode);
		if (bitmap != null) {
			return bitmap;
		}
		File file = image(context, barcode);
		if (!file.exists()) {
			return null;
		}
		bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
		if (bitmap != null) {
			MEMORY.put(barcode, bitmap);
		}
		return bitmap;
	}

	/** Gibt es schon ein Bild, oder wurde in der letzten Woche vergeblich danach gesucht? */
	private static boolean settled(Context context, String barcode) {
		if (image(context, barcode).exists()) {
			return true;
		}
		File none = missing(context, barcode);
		return none.exists() && System.currentTimeMillis() - none.lastModified() < RETRY_MS;
	}

	/**
	 * Zeigt das Bild des Barcodes im ImageView. Ist es noch nicht da, bleibt der ImageView leer (verborgen), wird im Hintergrund geladen
	 * und danach gefüllt, falls der ImageView noch zu diesem Barcode gehört.
	 */
	public static void bind(final ImageView view, final String barcode) {
		final Context context = view.getContext().getApplicationContext();
		view.setTag(barcode);
		Bitmap bitmap = cached(context, barcode);
		if (bitmap != null) {
			view.setImageBitmap(bitmap);
			view.setVisibility(View.VISIBLE);
			return;
		}
		view.setImageDrawable(null);
		view.setVisibility(View.GONE);
		if (barcode == null || !OpenFoodFacts.isProductBarcode(barcode) || !new SharedPrefHelper(context).isOffLookupEnabled()
				|| settled(context, barcode)) {
			return;
		}
		fetch(context, barcode, () -> {
			if (barcode.equals(view.getTag())) {
				Bitmap loaded = cached(context, barcode);
				if (loaded != null) {
					view.setImageBitmap(loaded);
					view.setVisibility(View.VISIBLE);
				}
			}
		});
	}

	/** Lädt das Bild im Hintergrund (einzeln und mit Pause); danach läuft {@code done} auf dem Main-Thread. */
	public static void fetch(final Context context, final String barcode, final Runnable done) {
		synchronized (RUNNING) {
			if (!RUNNING.add(barcode)) {
				// wird schon geladen: Aufrufer trotzdem weiterzählen lassen
				if (done != null) {
					MAIN.post(done);
				}
				return;
			}
		}
		IO.execute(() -> {
			try {
				if (!settled(context, barcode)) {
					long wait = lastRequest + PAUSE_MS - System.currentTimeMillis();
					if (wait > 0) {
						try {
							Thread.sleep(wait);
						} catch (InterruptedException ignored) {
							Thread.currentThread().interrupt();
						}
					}
					lastRequest = System.currentTimeMillis();
					byte[] data = OpenFoodFacts.fetchImage(barcode);
					if (data != null) {
						try (FileOutputStream out = new FileOutputStream(image(context, barcode))) {
							out.write(data);
						}
					} else {
						//noinspection ResultOfMethodCallIgnored
						missing(context, barcode).createNewFile();
						//noinspection ResultOfMethodCallIgnored
						missing(context, barcode).setLastModified(System.currentTimeMillis());
					}
				}
			} catch (IOException | RuntimeException ignored) {
				// ohne Netz oder bei einem Fehler später noch einmal versuchen
			} finally {
				synchronized (RUNNING) {
					RUNNING.remove(barcode);
				}
			}
			if (done != null) {
				MAIN.post(done);
			}
		});
	}

	/** Wie viele der Barcodes schon ein Bild haben. */
	public static int countCached(Context context, Iterable<String> barcodes) {
		int count = 0;
		for (String barcode : barcodes) {
			if (image(context, barcode).exists()) {
				count++;
			}
		}
		return count;
	}

	/** Barcodes, für die noch kein Bild geladen wurde und nicht gerade vergeblich gesucht wurde. */
	public static boolean needsImage(Context context, String barcode) {
		return barcode != null && OpenFoodFacts.isProductBarcode(barcode) && !settled(context, barcode);
	}
}
