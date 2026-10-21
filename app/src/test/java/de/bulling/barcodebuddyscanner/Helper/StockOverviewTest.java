package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Helper.StockOverview.Entry;
import de.bulling.barcodebuddyscanner.Helper.StockOverview.Product;
import de.bulling.barcodebuddyscanner.Helper.StockOverview.Sort;
import de.bulling.barcodebuddyscanner.Helper.StockOverview.StockRow;

public class StockOverviewTest {

	private static Product product(long id, String name, String group) {
		Product p = new Product();
		p.id    = id;
		p.name  = name;
		p.group = group;
		p.unit  = "Stück";
		return p;
	}

	private static Map<Long, Product> products(Product... list) {
		Map<Long, Product> map = new HashMap<>();
		for (Product p : list) {
			map.put(p.id, p);
		}
		return map;
	}

	@Test
	public void batchesAreSummedPerProduct() {
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(1, 8, false, 0.18625, "2026-11-01"));
		rows.add(new StockRow(1, 8, true, 0.18625, "2026-10-25"));
		rows.add(new StockRow(2, 1, false, 2.99, null));
		List<Entry> entries = StockOverview.build(rows, products(product(1, "Müsliriegel", "Süß"), product(2, "Frikadellen", "Fleisch")));
		assertEquals(2, entries.size());
		Entry bars = entries.get(0);
		assertEquals(16, bars.amount, 1e-9);
		assertEquals(8, bars.opened, 1e-9);
		// 16 × 0,18625 = 2,98 € (Grocy rundet den Wert je Produkt auf Cent)
		assertEquals(2.98, bars.value, 1e-9);
		assertEquals("2026-10-25", bars.nextDue);
		assertEquals(2.98 + 2.99, StockOverview.totalValue(entries), 1e-9);
		assertNull(entries.get(1).nextDue);
	}

	@Test
	public void emptyBatchesAndMissingPricesAreHandled() {
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(1, 0, false, 5.0, null));
		rows.add(new StockRow(2, 3, false, null, null));
		List<Entry> entries = StockOverview.build(rows, products(product(2, "Ohne Preis", null)));
		assertEquals(1, entries.size());
		assertEquals(0, entries.get(0).value, 1e-9);
		assertEquals(3, entries.get(0).amount, 1e-9);
	}

	@Test
	public void unknownProductKeepsItsStock() {
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(42, 2, false, 1.0, null));
		List<Entry> entries = StockOverview.build(rows, products());
		assertEquals("#42", entries.get(0).product.name);
	}

	@Test
	public void listEntriesAreAttachedAndDescribed() {
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(1, 1, false, 1.0, null));
		rows.add(new StockRow(2, 1, false, 1.0, null));
		Map<Long, List<StockOverview.ListRow>> onList = new HashMap<>();
		List<StockOverview.ListRow> forOne = new ArrayList<>();
		forOne.add(new StockOverview.ListRow(10, 2, 3, "Pack"));
		forOne.add(new StockOverview.ListRow(11, 6, 2, "Stück"));
		onList.put(1L, forOne);
		List<Entry> entries = StockOverview.build(rows, products(product(1, "Cola", null), product(2, "Nudeln", null)), onList);
		Entry cola = entries.get(0);
		assertTrue(cola.onList());
		assertEquals("2 Pack, 6 Stück", cola.listText());
		assertEquals(11, cola.listRow(2).id);
		assertNull(cola.listRow(99));
		assertFalse(entries.get(1).onList());
		assertEquals("", entries.get(1).listText());
	}

	@Test
	public void searchMatchesNameAndGroup() {
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(1, 1, false, 1.0, null));
		rows.add(new StockRow(2, 1, false, 1.0, null));
		List<Entry> entries = StockOverview.build(rows, products(product(1, "Cola Zero", "Getränk"), product(2, "Nudeln", "Pasta")));
		assertEquals(1, StockOverview.filter(entries, "cola").size());
		assertEquals(1, StockOverview.filter(entries, "PASTA").size());
		assertEquals(2, StockOverview.filter(entries, "  ").size());
		assertEquals(0, StockOverview.filter(entries, "xyz").size());
	}

	@Test
	public void sorting() {
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(1, 1, false, 5.0, "2026-12-01"));
		rows.add(new StockRow(2, 1, false, 9.0, null));
		rows.add(new StockRow(3, 1, false, 1.0, "2026-11-01"));
		List<Entry> entries = StockOverview.build(rows,
				products(product(1, "B", null), product(2, "A", null), product(3, "C", null)));
		StockOverview.sort(entries, Sort.NAME);
		assertEquals("A", entries.get(0).product.name);
		StockOverview.sort(entries, Sort.VALUE);
		assertEquals("A", entries.get(0).product.name);
		assertEquals("C", entries.get(2).product.name);
		StockOverview.sort(entries, Sort.DUE);
		assertEquals("C", entries.get(0).product.name);
		assertEquals("B", entries.get(1).product.name);
		assertEquals("A", entries.get(2).product.name);
	}

	@Test
	public void buttonRulesFollowGrocy() {
		Product p = product(1, "Cola", null);
		p.consumeAmount = 1;
		p.openAmount    = 1;
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(1, 2, true, 1.0, null));
		Entry e = StockOverview.build(rows, products(p)).get(0);
		assertTrue(e.canConsumeQuick());
		assertTrue(e.canConsumeAll());
		// alles geöffnet: nichts mehr zu öffnen
		assertFalse(e.canOpenNow());

		rows.add(new StockRow(1, 1, false, 1.0, null));
		e = StockOverview.build(rows, products(p)).get(0);
		assertTrue(e.canOpenNow());

		p.canOpen = false;
		assertFalse(StockOverview.build(rows, products(p)).get(0).canOpenNow());
		p.canOpen = true;
		p.tare = true;
		e = StockOverview.build(rows, products(p)).get(0);
		assertFalse(e.canConsumeQuick());
		assertFalse(e.canConsumeAll());
		assertFalse(e.canOpenNow());
	}

	@Test
	public void quickConsumeNeedsEnoughStock() {
		Product p = product(1, "Mehl", null);
		p.consumeAmount = 5;
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(1, 3, false, 1.0, null));
		Entry e = StockOverview.build(rows, products(p)).get(0);
		assertFalse(e.canConsumeQuick());
		assertTrue(e.canConsumeAll());
	}
}
