package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;

public class ReceiptTotalsTest {

	private static ShoppingItem item(double scanPrice, int quantity, Double deposit) {
		ShoppingItem item = new ShoppingItem();
		item.scanPrice = scanPrice;
		item.quantity  = quantity;
		item.deposit   = deposit;
		return item;
	}

	@Test
	public void depositIsAddedAndReturnedDepositIsSubtracted() {
		List<ShoppingItem> items = new ArrayList<>();
		items.add(item(1.29, 3, 0.25));   // 3 × Cola: 3,87 € + 0,75 € Pfand
		items.add(item(2.00, 1, null));   // 2,00 € ohne Pfand
		ReceiptPdf.Totals totals = ReceiptPdf.totals(items, 1.50);
		assertEquals(0.75, totals.deposit, 1e-9);
		assertEquals(1.50, totals.depositReturn, 1e-9);
		assertEquals(3.87 + 0.75 + 2.00 - 1.50, totals.sum, 1e-9);
	}

	@Test
	public void withoutReturnTheSumIsUnchanged() {
		List<ShoppingItem> items = new ArrayList<>();
		items.add(item(1.00, 2, 0.10));
		assertEquals(2.20, ReceiptPdf.totals(items).sum, 1e-9);
		assertEquals(2.20, ReceiptPdf.totals(items, 0).sum, 1e-9);
	}

	@Test
	public void negativeReturnCountsAsNothing() {
		List<ShoppingItem> items = new ArrayList<>();
		items.add(item(1.00, 1, null));
		assertEquals(1.00, ReceiptPdf.totals(items, -5).sum, 1e-9);
	}

	@Test
	public void returnCanExceedTheShoppingSum() {
		// Nur Leergut zurückgebracht: Die Summe wird negativ (Guthaben)
		assertEquals(-3.75, ReceiptPdf.totals(new ArrayList<>(), 3.75).sum, 1e-9);
	}
}
