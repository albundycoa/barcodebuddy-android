package de.bulling.barcodebuddyscanner.Helper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.bulling.barcodebuddyscanner.Helper.InventorySession.Entry;

/**
 * Vergleicht die Zählung der Inventur mit dem Bestand in Grocy und legt fest, was zu ändern ist. Stimmt die Menge
 * eines Produkts, wird nichts übertragen; Produkte, die nicht gescannt wurden, bleiben unberührt.
 */
public final class InventoryPlanner {

	private InventoryPlanner() {
	}

	/** Eine nötige Korrektur: Bestand von before auf after setzen. */
	public static final class Change {
		public final Entry  entry;
		public final double before;
		public final double after;

		public Change(Entry entry, double before, double after) {
			this.entry  = entry;
			this.before = before;
			// Rundung gegen Fließkomma-Reste wie 0,30000000000000004
			this.after  = Math.round(after * 10000) / 10000.0;
		}

		public boolean isIncrease() {
			return after > before;
		}
	}

	public static final class Plan {
		public final List<Change> changes = new ArrayList<>();
		/** Gezählte Produkte, deren Menge schon stimmt */
		public int matched;
		/** Barcodes, die Grocy nicht kennt */
		public int unknown;
		/** Produkte mit Bestand in Grocy, die nicht gescannt wurden */
		public int notScanned;

		/** Anteil der gezählten Produkte, die schon stimmten, in Prozent (0, wenn nichts verglichen wurde) */
		public int accuracyPercent() {
			int compared = matched + changes.size();
			return compared == 0 ? 0 : matched * 100 / compared;
		}
	}

	/**
	 * @param entries die gezählten Einträge
	 * @param live    aktueller Bestand je Produktnummer in Lagereinheiten; Produkte ohne Bestand fehlen
	 */
	public static Plan plan(List<Entry> entries, Map<Long, Double> live) {
		Plan plan = new Plan();
		Set<Long> counted = new HashSet<>();
		for (Entry e : entries) {
			if (!e.known) {
				plan.unknown++;
				continue;
			}
			counted.add(e.productId);
			Double amount = live.get(e.productId);
			double before = amount == null ? 0 : amount;
			if (InventorySession.sameAmount(e.counted, before)) {
				plan.matched++;
			} else {
				plan.changes.add(new Change(e, before, e.counted));
			}
		}
		for (Map.Entry<Long, Double> row : live.entrySet()) {
			if (row.getValue() > 0 && !counted.contains(row.getKey())) {
				plan.notScanned++;
			}
		}
		return plan;
	}
}
