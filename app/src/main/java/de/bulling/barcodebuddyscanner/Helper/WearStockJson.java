package de.bulling.barcodebuddyscanner.Helper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Der Bestand als kompaktes JSON für die Uhr (Data Layer, Pfad /bbs/stock). Kurze Schlüssel, damit auch ein großer Vorrat
 * weit unter der Grenze von 100 KB bleibt. Reine Logik, getestet.
 *
 * <pre>
 * { "ts": Zeitstempel, "n": Anzahl, "v": "86,36 €", "i": [ { "id", "n" Name, "a" Menge-Text, "w" Wert-Text, "am" Menge,
 *   "cq" Schnellverbrauch, "oq" Öffnen-Menge, "t" Einheit, "fc"/"fa"/"fo": Verbrauchen/Alle/Öffnen möglich } ],
 *   "m": Meldung der letzten Buchung, "tx": Nummer zum Rückgängigmachen, "mt": Zeit der Meldung, "err": Fehlertext }
 * </pre>
 */
public final class WearStockJson {

	/** Mehr Zeilen schickt die App nicht an die Uhr; Rest siehe Handy. */
	public static final int MAX_ITEMS = 300;

	private WearStockJson() {
	}

	/**
	 * @param openedFormat Text für "geöffnet" mit Platzhalter für die Menge, z. B. "%1$s geöffnet"
	 * @param message      Meldung zur letzten Buchung, null = keine
	 * @param transaction  Nummer zum Rückgängigmachen, null = nicht möglich
	 * @param error        Fehlertext statt Daten (Grocy nicht erreichbar), null = alles gut
	 */
	public static String build(List<StockOverview.Entry> entries, long now, String openedFormat, String message,
							   String transaction, String error) {
		JsonObject root = new JsonObject();
		root.addProperty("ts", now);
		if (error != null) {
			root.addProperty("err", error);
		}
		List<StockOverview.Entry> sorted = new ArrayList<>(entries);
		StockOverview.sort(sorted, StockOverview.Sort.NAME);
		root.addProperty("n", sorted.size());
		root.addProperty("v", Money.format(StockOverview.totalValue(sorted)));
		JsonArray items = new JsonArray();
		for (StockOverview.Entry e : sorted) {
			if (items.size() >= MAX_ITEMS) {
				break;
			}
			String unit = e.product.unit == null ? "" : e.product.unit;
			String amount = (InventorySession.format(e.amount) + " " + unit).trim();
			if (e.opened > 0) {
				amount += " · " + String.format(Locale.GERMANY, openedFormat, InventorySession.format(e.opened));
			}
			JsonObject o = new JsonObject();
			o.addProperty("id", e.product.id);
			o.addProperty("n", e.product.name);
			o.addProperty("a", amount);
			o.addProperty("w", Money.format(e.value));
			o.addProperty("am", e.amount);
			o.addProperty("cq", e.product.consumeAmount);
			o.addProperty("oq", e.product.openAmount);
			o.addProperty("t", unit);
			o.addProperty("fc", e.canConsumeQuick());
			o.addProperty("fa", e.canConsumeAll());
			o.addProperty("fo", e.canOpenNow());
			items.add(o);
		}
		root.add("i", items);
		if (message != null) {
			root.addProperty("m", message);
			root.addProperty("mt", now);
			if (transaction != null) {
				root.addProperty("tx", transaction);
			}
		}
		return root.toString();
	}
}
