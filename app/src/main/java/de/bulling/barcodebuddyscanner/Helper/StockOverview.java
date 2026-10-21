package de.bulling.barcodebuddyscanner.Helper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Rechenlogik der Bestandsübersicht (wie Grocys "Stock overview"): Aus den Bestandszeilen (eine je Charge) wird eine Zeile
 * je Produkt mit Menge, davon geöffnet, Wert (Menge mal Einkaufspreis je Lagereinheit) und nächstem Haltbarkeitsdatum.
 * Dazu Suche, Sortierung und die Regeln, wann Verbrauchen und Öffnen möglich sind. Ohne Android, getestet.
 */
public final class StockOverview {

	private StockOverview() {
	}

	/** Eine Charge im Bestand, so wie Grocy sie liefert. */
	public static final class StockRow {
		public long    productId;
		/** Menge in Lagereinheiten */
		public double  amount;
		public boolean open;
		/** Einkaufspreis je Lagereinheit, null = unbekannt */
		public Double  price;
		/** Mindesthaltbarkeitsdatum "yyyy-MM-dd", null = keines oder unbegrenzt */
		public String  due;

		public StockRow(long productId, double amount, boolean open, Double price, String due) {
			this.productId = productId;
			this.amount    = amount;
			this.open      = open;
			this.price     = price;
			this.due       = due;
		}
	}

	/** Was zu einem Produkt gebraucht wird, damit Knöpfe und Anzeige stimmen. */
	public static final class Product {
		public long    id;
		public String  name;
		public String  group;
		/** Name der Lagereinheit, z. B. "Stück" */
		public String  unit;
		/** Menge, die der Knopf "verbrauchen" bucht (Schnellverbrauch, mindestens etwas), in Lagereinheiten */
		public double  consumeAmount = 1;
		/** Menge, die der Knopf "öffnen" bucht, in Lagereinheiten */
		public double  openAmount = 1;
		/** Öffnen ist für dieses Produkt erlaubt */
		public boolean canOpen = true;
		/** Produkt mit Tara-Gewicht: Verbrauchen und Öffnen gehen nur über Grocy selbst */
		public boolean tare;
		/** Nummer der Einkaufseinheit in Grocy (z. B. Pack), 0 = unbekannt */
		public long    quPurchase;
		/** Nummer der Lagereinheit in Grocy (z. B. Stück), 0 = unbekannt */
		public long    quStock;
	}

	/** Ein Eintrag des Produkts auf der Einkaufsliste (Menge in der Einheit des Eintrags). */
	public static final class ListRow {
		public final long   id;
		public final double amount;
		public final long   quId;
		/** Name der Einheit, leer wenn unbekannt */
		public final String unit;

		public ListRow(long id, double amount, long quId, String unit) {
			this.id     = id;
			this.amount = amount;
			this.quId   = quId;
			this.unit   = unit == null ? "" : unit;
		}
	}

	/** Eine Zeile der Übersicht. */
	public static final class Entry {
		public final Product product;
		public double amount;
		public double opened;
		public double value;
		public String nextDue;
		/** Einträge dieses Produkts auf der Einkaufsliste (offene, in der Standardliste), leer = nicht auf der Liste */
		public final List<ListRow> list = new ArrayList<>();

		/** Steht das Produkt auf der Einkaufsliste? */
		public boolean onList() {
			return !list.isEmpty();
		}

		/** Der Eintrag mit dieser Einheit, null wenn das Produkt mit dieser Einheit nicht auf der Liste steht. */
		public ListRow listRow(long quId) {
			for (ListRow row : list) {
				if (row.quId == quId) {
					return row;
				}
			}
			return null;
		}

		/** "2 Pack" oder, bei mehreren Einheiten, "2 Pack, 6 Stück"; leer wenn nicht auf der Liste. */
		public String listText() {
			StringBuilder text = new StringBuilder();
			for (ListRow row : list) {
				if (text.length() > 0) {
					text.append(", ");
				}
				text.append((InventorySession.format(row.amount) + " " + row.unit).trim());
			}
			return text.toString();
		}

		Entry(Product product) {
			this.product = product;
		}

		/** Der Knopf mit der Schnellverbrauchsmenge ist bedienbar (wie in Grocy). */
		public boolean canConsumeQuick() {
			return !product.tare && amount > 0 && amount + 1e-9 >= product.consumeAmount;
		}

		public boolean canConsumeAll() {
			return !product.tare && amount > 0;
		}

		/** Öffnen geht, wenn es erlaubt ist, genug da ist und nicht schon alles geöffnet ist. */
		public boolean canOpenNow() {
			return product.canOpen && !product.tare && amount + 1e-9 >= product.openAmount && opened + 1e-9 < amount;
		}
	}

	public enum Sort { NAME, VALUE, DUE }

	/**
	 * Fasst die Chargen je Produkt zusammen. Produkte ohne Eintrag in {@code products} bekommen einen Platzhalternamen,
	 * damit Bestand nie verschwindet. Chargen mit Menge 0 oder weniger zählen nicht.
	 */
	public static List<Entry> build(List<StockRow> rows, Map<Long, Product> products) {
		return build(rows, products, new HashMap<>());
	}

	/** Wie {@link #build(List, Map)}, dazu die Einträge der Einkaufsliste je Produktnummer. */
	public static List<Entry> build(List<StockRow> rows, Map<Long, Product> products, Map<Long, List<ListRow>> onList) {
		Map<Long, Entry> byProduct = new LinkedHashMap<>();
		for (StockRow row : rows) {
			if (row.amount <= 0) {
				continue;
			}
			Entry entry = byProduct.get(row.productId);
			if (entry == null) {
				Product product = products.get(row.productId);
				if (product == null) {
					product = new Product();
					product.id   = row.productId;
					product.name = "#" + row.productId;
				}
				entry = new Entry(product);
				byProduct.put(row.productId, entry);
			}
			entry.amount += row.amount;
			if (row.open) {
				entry.opened += row.amount;
			}
			if (row.price != null) {
				entry.value += row.amount * row.price;
			}
			if (row.due != null && (entry.nextDue == null || row.due.compareTo(entry.nextDue) < 0)) {
				entry.nextDue = row.due;
			}
		}
		for (Entry entry : byProduct.values()) {
			List<ListRow> listed = onList.get(entry.product.id);
			if (listed != null) {
				entry.list.addAll(listed);
			}
			// Wie Grocy: der Wert je Produkt wird auf Cent gerundet, die Gesamtsumme besteht aus diesen Werten
			entry.value = Math.round(entry.value * 100) / 100.0;
		}
		return new ArrayList<>(byProduct.values());
	}

	public static double totalValue(List<Entry> entries) {
		double sum = 0;
		for (Entry entry : entries) {
			sum += entry.value;
		}
		return sum;
	}

	/** Suche in Produktname und Produktgruppe, ohne Beachtung der Groß-/Kleinschreibung; leere Suche zeigt alles. */
	public static List<Entry> filter(List<Entry> entries, String query) {
		String needle = query == null ? "" : query.trim().toLowerCase(Locale.GERMAN);
		if (needle.isEmpty()) {
			return new ArrayList<>(entries);
		}
		List<Entry> result = new ArrayList<>();
		for (Entry entry : entries) {
			String name  = entry.product.name == null ? "" : entry.product.name.toLowerCase(Locale.GERMAN);
			String group = entry.product.group == null ? "" : entry.product.group.toLowerCase(Locale.GERMAN);
			if (name.contains(needle) || group.contains(needle)) {
				result.add(entry);
			}
		}
		return result;
	}

	public static void sort(List<Entry> entries, Sort sort) {
		// Deutsche Sortierung: "Äpfel" steht bei A, Groß-/Kleinschreibung zählt nicht
		final java.text.Collator collator = java.text.Collator.getInstance(Locale.GERMAN);
		collator.setStrength(java.text.Collator.SECONDARY);
		final Comparator<Entry> byName = (a, b) -> collator.compare(String.valueOf(a.product.name), String.valueOf(b.product.name));
		switch (sort) {
			case VALUE:
				Collections.sort(entries, (a, b) -> {
					int c = Double.compare(b.value, a.value);
					return c != 0 ? c : byName.compare(a, b);
				});
				break;
			case DUE:
				// Früheste zuerst, Produkte ohne Datum ans Ende
				Collections.sort(entries, (a, b) -> {
					if (a.nextDue == null && b.nextDue == null) {
						return byName.compare(a, b);
					}
					if (a.nextDue == null) {
						return 1;
					}
					if (b.nextDue == null) {
						return -1;
					}
					int c = a.nextDue.compareTo(b.nextDue);
					return c != 0 ? c : byName.compare(a, b);
				});
				break;
			default:
				Collections.sort(entries, byName);
		}
	}
}
