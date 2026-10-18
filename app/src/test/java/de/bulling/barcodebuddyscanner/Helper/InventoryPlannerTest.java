package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Helper.InventoryPlanner.Change;
import de.bulling.barcodebuddyscanner.Helper.InventoryPlanner.Plan;
import de.bulling.barcodebuddyscanner.Helper.InventorySession.Entry;

/** Prüft die Fälle der Inventur aus dem Test mit dem Grocy-Testsystem. */
public class InventoryPlannerTest {

	private static Entry entry(long productId, String name, double counted) {
		Entry e = new Entry();
		e.key       = "p" + productId;
		e.productId = productId;
		e.name      = name;
		e.known     = true;
		e.counted   = counted;
		return e;
	}

	private static Entry unknown(String barcode) {
		Entry e = new Entry();
		e.key     = "b" + barcode;
		e.barcode = barcode;
		e.counted = 1;
		return e;
	}

	@Test
	public void matchingProductsAreLeftAlone() {
		Map<Long, Double> live = new HashMap<>();
		live.put(1L, 3.0);
		List<Entry> entries = new ArrayList<>();
		entries.add(entry(1, "Milch", 3));

		Plan plan = InventoryPlanner.plan(entries, live);
		assertTrue(plan.changes.isEmpty());
		assertEquals(1, plan.matched);
		assertEquals(100, plan.accuracyPercent());
	}

	@Test
	public void moreAndLessThanStockBecomeChanges() {
		Map<Long, Double> live = new HashMap<>();
		live.put(7L, 5.0);   // Fusilli: 7 gezählt
		live.put(95L, 48.0); // Erdnuss-Riegel: 24 gezählt
		List<Entry> entries = new ArrayList<>();
		entries.add(entry(7, "Fusilli", 7));
		entries.add(entry(95, "Erdnuss-Riegel", 24));

		Plan plan = InventoryPlanner.plan(entries, live);
		assertEquals(2, plan.changes.size());
		Change fusilli = plan.changes.get(0);
		assertEquals(5.0, fusilli.before, 0.0001);
		assertEquals(7.0, fusilli.after, 0.0001);
		assertTrue(fusilli.isIncrease());
		Change riegel = plan.changes.get(1);
		assertEquals(24.0, riegel.after, 0.0001);
		assertFalse(riegel.isIncrease());
	}

	@Test
	public void productWithoutStockIsTreatedAsZero() {
		Map<Long, Double> live = new HashMap<>();
		List<Entry> entries = new ArrayList<>();
		entries.add(entry(3, "Paprika rot", 1));

		Plan plan = InventoryPlanner.plan(entries, live);
		assertEquals(1, plan.changes.size());
		assertEquals(0.0, plan.changes.get(0).before, 0.0001);
		assertTrue(plan.changes.get(0).isIncrease());
	}

	@Test
	public void unscannedProductsAreCountedButNeverChanged() {
		Map<Long, Double> live = new HashMap<>();
		live.put(1L, 3.0);
		live.put(9L, 2.0);  // Pfeffer, nicht gescannt
		live.put(10L, 0.0); // ohne Bestand zählt nicht als "nicht gescannt"
		List<Entry> entries = new ArrayList<>();
		entries.add(entry(1, "Milch", 3));

		Plan plan = InventoryPlanner.plan(entries, live);
		assertEquals(1, plan.notScanned);
		assertTrue(plan.changes.isEmpty());
	}

	@Test
	public void unknownBarcodesAreOnlyCounted() {
		List<Entry> entries = new ArrayList<>();
		entries.add(unknown("999"));

		Plan plan = InventoryPlanner.plan(entries, new HashMap<Long, Double>());
		assertEquals(1, plan.unknown);
		assertTrue(plan.changes.isEmpty());
		assertEquals(0, plan.accuracyPercent());
	}

	@Test
	public void floatingPointResiduesDoNotCreateChanges() {
		Map<Long, Double> live = new HashMap<>();
		live.put(1L, 0.3);
		List<Entry> entries = new ArrayList<>();
		entries.add(entry(1, "Mehl", 0.1 + 0.2));

		Plan plan = InventoryPlanner.plan(entries, live);
		assertTrue(plan.changes.isEmpty());
		assertEquals(1, plan.matched);
	}

	@Test
	public void accuracyMatchesTheFirstTestRun() {
		// 4 passen, 3 weichen ab: 57 %
		Map<Long, Double> live = new HashMap<>();
		live.put(21L, 3.0);
		live.put(106L, 8.0);
		live.put(39L, 3.0);
		live.put(30L, 8.0);
		live.put(7L, 5.0);
		live.put(95L, 48.0);
		List<Entry> entries = new ArrayList<>();
		entries.add(entry(21, "Milch", 3));
		entries.add(entry(106, "Aufbackbrötchen", 8));
		entries.add(entry(39, "Kartoffel Püree", 3));
		entries.add(entry(30, "Müsliriegel schoko", 8));
		entries.add(entry(7, "Fusilli", 7));
		entries.add(entry(95, "Erdnuss-Riegel", 24));
		entries.add(entry(3, "Paprika rot", 1));

		Plan plan = InventoryPlanner.plan(entries, live);
		assertEquals(4, plan.matched);
		assertEquals(3, plan.changes.size());
		assertEquals(57, plan.accuracyPercent());
	}
}
