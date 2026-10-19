package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;

public class DepositTest {

	private static ShoppingItem item(boolean known, Long group, double perScan, int quantity) {
		ShoppingItem item = new ShoppingItem();
		item.known        = known;
		item.groupId      = group;
		item.stockPerScan = perScan;
		item.quantity     = quantity;
		return item;
	}

	@Test
	public void roundTrip() {
		Map<Long, Double> rules = new LinkedHashMap<>();
		rules.put(12L, 0.25);
		rules.put(15L, 0.08);
		Map<Long, Double> back = Deposit.parse(Deposit.serialize(rules));
		assertEquals(2, back.size());
		assertEquals(0.25, back.get(12L), 1e-9);
		assertEquals(0.08, back.get(15L), 1e-9);
	}

	@Test
	public void brokenInputGivesEmptyRules() {
		assertTrue(Deposit.parse(null).isEmpty());
		assertTrue(Deposit.parse("").isEmpty());
		assertTrue(Deposit.parse("kein json").isEmpty());
		assertTrue(Deposit.parse("[1,2]").isEmpty());
	}

	@Test
	public void brokenEntryDoesNotSpoilTheOthers() {
		Map<Long, Double> rules = Deposit.parse("{\"abc\":0.5,\"7\":0.25,\"8\":-1,\"9\":0}");
		assertEquals(1, rules.size());
		assertEquals(0.25, rules.get(7L), 1e-9);
	}

	@Test
	public void cokeThreeTimesCostsThreeDeposits() {
		Map<Long, Double> rules = new LinkedHashMap<>();
		rules.put(12L, 0.25);
		ShoppingItem cola = item(true, 12L, 1, 3);
		List<ShoppingItem> items = new ArrayList<>();
		items.add(cola);
		Deposit.apply(items, rules);
		assertEquals(0.25, cola.deposit, 1e-9);
		assertEquals(0.75, cola.depositTotal(), 1e-9);
	}

	@Test
	public void depositScalesWithUnitsPerScan() {
		Map<Long, Double> rules = new LinkedHashMap<>();
		rules.put(12L, 0.25);
		ShoppingItem sixPack = item(true, 12L, 6, 2);
		assertEquals(1.5, Deposit.perScan(sixPack, rules), 1e-9);
		sixPack.deposit = Deposit.perScan(sixPack, rules);
		assertEquals(3.0, sixPack.depositTotal(), 1e-9);
	}

	@Test
	public void noDepositWithoutRuleOrKnownProduct() {
		Map<Long, Double> rules = new LinkedHashMap<>();
		rules.put(12L, 0.25);
		assertNull(Deposit.perScan(item(true, 99L, 1, 1), rules));
		assertNull(Deposit.perScan(item(true, null, 1, 1), rules));
		assertNull(Deposit.perScan(item(false, 12L, 1, 1), rules));
		assertEquals(0.0, item(true, 99L, 1, 4).depositTotal(), 1e-9);
	}
}
