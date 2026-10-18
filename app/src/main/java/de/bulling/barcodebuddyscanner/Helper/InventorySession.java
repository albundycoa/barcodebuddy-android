package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.InventoryProduct;

/**
 * Die laufende Inventur: Wie oft wurde welches Produkt gezählt. Die Zählung bleibt gespeichert, auch wenn die App
 * zwischendurch beendet wird. Gesendet wird erst am Ende, denn ob ein Produkt zu selten oder zu oft gescannt wurde,
 * lässt sich erst sagen, wenn man alle Packungen durch hat.
 */
public final class InventorySession {

	/** Nimmt Barcodes entgegen, solange die Inventur geöffnet ist (auch die des Bluetooth-Scanner-Dienstes). */
	public interface ScanSink {
		void onBarcode(String code);
	}

	public enum Status {
		/** Gezählt wie im Bestand: Grocy braucht nichts */
		MATCH,
		/** Mehr gezählt als im Bestand */
		MORE,
		/** Weniger gezählt als im Bestand */
		LESS,
		/** Barcode, den Grocy nicht kennt */
		UNKNOWN
	}

	public static final class Entry {
		/** "p" + Produktnummer oder "b" + Barcode */
		public String  key;
		public long    productId;
		public String  barcode;
		public String  name;
		public String  unit;
		public String  group;
		/** Bestand in Lagereinheiten (zuletzt bekannter Stand) */
		public double  stock;
		/** Gezählte Menge in Lagereinheiten */
		public double  counted;
		/** Lagereinheiten, die ein Scan des zuletzt gescannten Barcodes zählt (z. B. 1 Pack = 3 Portion) */
		public double  perScan;
		/** Einheit dieses Barcodes, z. B. "Pack" */
		public String  scanUnit;
		/** Was der letzte Scan gerade zur Zählung beiträgt (nach dem Anpassen von Hand abweichend von perScan) */
		public double  lastAmount;
		public boolean known;
		public Integer bestBeforeDays;
		public long    lastScan;
	}

	private static final class State {
		long        startedAt;
		int         scans;
		List<Entry> entries = new ArrayList<>();
	}

	private static final String PREFS = "inventory";
	private static final String KEY   = "session";
	private static final double EPS   = 0.0001;

	private static InventorySession instance;
	private static volatile ScanSink scanSink;

	public static synchronized InventorySession get(Context context) {
		if (instance == null) {
			instance = new InventorySession(context.getApplicationContext());
		}
		return instance;
	}

	public static void setScanSink(ScanSink sink) {
		scanSink = sink;
	}

	public static ScanSink getScanSink() {
		return scanSink;
	}

	private final SharedPreferences        prefs;
	private final Gson                     gson    = new Gson();
	private final Map<String, Entry>       entries = new LinkedHashMap<>();
	private long                           startedAt;
	private int                            scans;

	private InventorySession(Context appContext) {
		prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		try {
			State state = gson.fromJson(prefs.getString(KEY, null), State.class);
			if (state != null) {
				startedAt = state.startedAt;
				scans     = state.scans;
				for (Entry e : state.entries) {
					entries.put(e.key, e);
				}
			}
		} catch (RuntimeException ignored) {
			// Beschädigter Stand: mit einer leeren Inventur beginnen
		}
	}

	public synchronized boolean isEmpty() {
		return entries.isEmpty();
	}

	public synchronized int scans() {
		return scans;
	}

	public synchronized long startedAt() {
		return startedAt;
	}

	/** Die gezählten Einträge, der zuletzt gescannte zuerst. */
	public synchronized List<Entry> snapshot() {
		List<Entry> list = new ArrayList<>(entries.values());
		Collections.sort(list, (a, b) -> Long.compare(b.lastScan, a.lastScan));
		return list;
	}

	/** Zählt einen Scan dieses Produkts; die Menge richtet sich nach dem Barcode (Packung mit mehreren Stück). */
	public synchronized Entry recordProduct(InventoryProduct p) {
		String key = "p" + p.id;
		Entry e = entries.get(key);
		if (e == null) {
			e = new Entry();
			e.key       = key;
			e.productId = p.id;
			e.known     = true;
			entries.put(key, e);
		}
		e.name           = p.name;
		e.unit           = p.stockUnit;
		e.group          = p.group;
		e.stock          = p.stock;
		e.bestBeforeDays = p.bestBeforeDays;
		e.counted       += p.stockPerScan;
		e.perScan        = p.stockPerScan;
		e.scanUnit       = p.scanUnit;
		e.lastAmount     = p.stockPerScan;
		touch(e);
		return e;
	}

	public synchronized Entry recordUnknown(String barcode) {
		String key = "b" + barcode;
		Entry e = entries.get(key);
		if (e == null) {
			e = new Entry();
			e.key     = key;
			e.barcode = barcode;
			e.name    = barcode;
			entries.put(key, e);
		}
		e.counted++;
		touch(e);
		return e;
	}

	private void touch(Entry e) {
		long now = System.currentTimeMillis();
		if (startedAt == 0) {
			startedAt = now;
		}
		e.lastScan = now;
		scans++;
		save();
	}

	/** Korrigiert die gezählte Menge von Hand; 0 oder weniger entfernt den Eintrag. */
	public synchronized void setCounted(String key, double counted) {
		Entry e = entries.get(key);
		if (e == null) {
			return;
		}
		if (counted <= EPS) {
			entries.remove(key);
		} else {
			e.counted = counted;
		}
		save();
	}

	/**
	 * Korrigiert den letzten Scan: Die Packung war nicht voll, es sind nur actual Lagereinheiten darin
	 * (z. B. 2 von 3 Portionen). Der Rest der Zählung bleibt unverändert.
	 */
	public synchronized void adjustLastScan(String key, double actual) {
		Entry e = entries.get(key);
		if (e == null) {
			return;
		}
		e.counted = Math.max(0, e.counted - e.lastAmount + Math.max(0, actual));
		e.lastAmount = Math.max(0, actual);
		save();
	}

	public synchronized void remove(String key) {
		if (entries.remove(key) != null) {
			save();
		}
	}

	/** Übernimmt den aktuellen Bestand aus Grocy. */
	public synchronized void updateStock(String key, double stock) {
		Entry e = entries.get(key);
		if (e != null) {
			e.stock = stock;
			save();
		}
	}

	public synchronized void clear() {
		entries.clear();
		startedAt = 0;
		scans = 0;
		save();
	}

	/** Entfernt alle Einträge außer den angegebenen (die Inventur geht mit dem Rest weiter). */
	public synchronized void keepOnly(java.util.Set<String> keys) {
		entries.keySet().retainAll(keys);
		if (entries.isEmpty()) {
			startedAt = 0;
			scans = 0;
		}
		save();
	}

	private void save() {
		State state = new State();
		state.startedAt = startedAt;
		state.scans     = scans;
		state.entries   = new ArrayList<>(entries.values());
		prefs.edit().putString(KEY, gson.toJson(state)).apply();
	}

	public static Status status(Entry e) {
		if (!e.known) {
			return Status.UNKNOWN;
		}
		if (Math.abs(e.counted - e.stock) < EPS) {
			return Status.MATCH;
		}
		return e.counted > e.stock ? Status.MORE : Status.LESS;
	}

	public static boolean sameAmount(double a, double b) {
		return Math.abs(a - b) < EPS;
	}

	/** 8.0 -> "8", 0.5 -> "0,5" */
	public static String format(double value) {
		return value == Math.rint(value) ? String.valueOf((long) value)
				: String.valueOf(Math.round(value * 1000) / 1000.0).replace('.', ',');
	}
}
