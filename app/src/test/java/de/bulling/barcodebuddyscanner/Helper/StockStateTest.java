package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Helper.StockOverview.Entry;
import de.bulling.barcodebuddyscanner.Helper.StockOverview.State;

public class StockStateTest {

	private static Entry entry(String name) {
		StockOverview.Product p = new StockOverview.Product();
		p.name = name;
		return new Entry(p);
	}

	@Test
	public void rowColorFollowsTheOrderOfGrocy() {
		Entry e = entry("A");
		assertNull(StockOverview.mainState(e));
		e.belowMin = true;
		assertEquals(State.BELOW_MIN, StockOverview.mainState(e));
		e.dueSoon = true;
		assertEquals(State.DUE_SOON, StockOverview.mainState(e));
		e.overdue = true;
		assertEquals(State.OVERDUE, StockOverview.mainState(e));
		e.expired = true;
		assertEquals(State.EXPIRED, StockOverview.mainState(e));
	}

	@Test
	public void filterByState() {
		Entry a = entry("A");
		a.overdue = true;
		Entry b = entry("B");
		List<Entry> all = new ArrayList<>();
		all.add(a);
		all.add(b);
		assertEquals(1, StockOverview.withState(all, State.OVERDUE).size());
		assertEquals(0, StockOverview.withState(all, State.EXPIRED).size());
		assertEquals(2, StockOverview.withState(all, null).size());
	}
}
