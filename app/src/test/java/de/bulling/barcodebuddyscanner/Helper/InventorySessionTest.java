package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.InventoryProduct;
import de.bulling.barcodebuddyscanner.Helper.InventorySession.Entry;
import de.bulling.barcodebuddyscanner.Helper.InventorySession.Status;

public class InventorySessionTest {

	private static InventoryProduct product(long id, String name, double stock, double perScan) {
		InventoryProduct p = new InventoryProduct();
		p.id           = id;
		p.name         = name;
		p.stock        = stock;
		p.stockPerScan = perScan;
		p.stockUnit    = "Stück";
		return p;
	}

	@Test
	public void everyScanAddsTheAmountOfTheBarcode() {
		InventorySession session = new InventorySession(new FakePreferences());
		session.recordProduct(product(1, "Milch", 3, 1));
		session.recordProduct(product(1, "Milch", 3, 1));
		Entry e = session.recordProduct(product(1, "Milch", 3, 1));
		assertEquals(3.0, e.counted, 0.0001);
		assertEquals(Status.MATCH, InventorySession.status(e));
		assertEquals(3, session.scans());
	}

	@Test
	public void packBarcodeCountsSeveralUnitsPerScan() {
		InventorySession session = new InventorySession(new FakePreferences());
		Entry e = session.recordProduct(product(2, "Kartoffel Püree", 12, 3));
		assertEquals(3.0, e.counted, 0.0001);
		assertEquals(Status.LESS, InventorySession.status(e));
	}

	@Test
	public void statusComparesCountedWithStock() {
		Entry e = new Entry();
		e.known = true;
		e.stock = 5;
		e.counted = 7;
		assertEquals(Status.MORE, InventorySession.status(e));
		e.counted = 2;
		assertEquals(Status.LESS, InventorySession.status(e));
		e.counted = 5;
		assertEquals(Status.MATCH, InventorySession.status(e));
		e.known = false;
		assertEquals(Status.UNKNOWN, InventorySession.status(e));
	}

	@Test
	public void adjustingTheLastScanReplacesOnlyThatScan() {
		InventorySession session = new InventorySession(new FakePreferences());
		session.recordProduct(product(2, "Kartoffel Püree", 12, 3));
		Entry e = session.recordProduct(product(2, "Kartoffel Püree", 12, 3));
		assertEquals(6.0, e.counted, 0.0001);

		// Die zweite Packung enthält nur noch 1 Portion
		session.adjustLastScan(e.key, 1);
		assertEquals(4.0, e.counted, 0.0001);

		// Ein zweites Anpassen ersetzt die erste Korrektur, statt sie zu stapeln
		session.adjustLastScan(e.key, 2);
		assertEquals(5.0, e.counted, 0.0001);

		// Der nächste Scan zählt wieder eine volle Packung
		session.recordProduct(product(2, "Kartoffel Püree", 12, 3));
		assertEquals(8.0, e.counted, 0.0001);
	}

	@Test
	public void adjustingNeverGoesBelowZero() {
		InventorySession session = new InventorySession(new FakePreferences());
		Entry e = session.recordProduct(product(3, "Reis", 2, 1));
		session.adjustLastScan(e.key, 0);
		assertEquals(0.0, e.counted, 0.0001);
	}

	@Test
	public void settingTheAmountToZeroRemovesTheEntry() {
		InventorySession session = new InventorySession(new FakePreferences());
		Entry e = session.recordProduct(product(4, "Salz", 1, 1));
		session.setCounted(e.key, 0);
		assertTrue(session.isEmpty());
	}

	@Test
	public void unknownBarcodesAreCountedSeparately() {
		InventorySession session = new InventorySession(new FakePreferences());
		session.recordUnknown("999");
		Entry e = session.recordUnknown("999");
		assertFalse(e.known);
		assertEquals(2.0, e.counted, 0.0001);
		assertEquals(1, session.snapshot().size());
	}

	@Test
	public void countSurvivesRestart() {
		FakePreferences prefs = new FakePreferences();
		InventorySession first = new InventorySession(prefs);
		first.recordProduct(product(5, "Nudeln", 4, 1));
		first.recordProduct(product(5, "Nudeln", 4, 1));
		first.recordUnknown("123");

		InventorySession second = new InventorySession(prefs);
		List<Entry> entries = second.snapshot();
		assertEquals(2, entries.size());
		assertEquals(3, second.scans());
		for (Entry e : entries) {
			if (e.known) {
				assertEquals(2.0, e.counted, 0.0001);
			}
		}
	}

	@Test
	public void keepOnlyKeepsTheGivenEntriesAndResetsWhenEmpty() {
		InventorySession session = new InventorySession(new FakePreferences());
		Entry a = session.recordProduct(product(1, "A", 1, 1));
		session.recordProduct(product(2, "B", 1, 1));
		session.keepOnly(java.util.Collections.singleton(a.key));
		assertEquals(1, session.snapshot().size());
		session.keepOnly(java.util.Collections.<String>emptySet());
		assertTrue(session.isEmpty());
		assertEquals(0, session.scans());
	}

	@Test
	public void formatUsesCommaAndDropsTrailingZeros() {
		assertEquals("8", InventorySession.format(8.0));
		assertEquals("0,5", InventorySession.format(0.5));
		assertEquals("1,333", InventorySession.format(4.0 / 3));
	}
}
