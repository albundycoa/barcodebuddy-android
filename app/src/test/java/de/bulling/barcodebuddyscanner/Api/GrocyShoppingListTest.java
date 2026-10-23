package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonParser;

import org.junit.Test;

public class GrocyShoppingListTest {

	@Test
	public void sameUnitEntryIsMerged() {
		// Grocy liefert Zahlen oft als Text
		GrocyStock.ListEntry entry = GrocyStock.findMergeable(JsonParser.parseString(
				"[{\"id\":\"12\",\"product_id\":\"5\",\"amount\":\"2\",\"qu_id\":\"3\"}]"), 3);
		assertNotNull(entry);
		assertEquals(12, entry.id);
		assertEquals(2.0, entry.amount, 1e-9);
	}

	@Test
	public void differentUnitGetsItsOwnRow() {
		assertNull(GrocyStock.findMergeable(JsonParser.parseString(
				"[{\"id\":12,\"amount\":2,\"qu_id\":2}]"), 3));
	}

	@Test
	public void emptyOrBrokenAnswerMeansNothingToMerge() {
		assertNull(GrocyStock.findMergeable(null, 3));
		assertNull(GrocyStock.findMergeable(JsonParser.parseString("[]"), 3));
		assertNull(GrocyStock.findMergeable(JsonParser.parseString("{}"), 3));
		assertNull(GrocyStock.findMergeable(JsonParser.parseString("[5]"), 3));
	}

	@Test
	public void shoppingRowsAreGroupedPerProductWithoutDoneOnes() {
		java.util.Map<Long, String> units = new java.util.HashMap<>();
		units.put(3L, "Pack");
		units.put(2L, "Stück");
		java.util.Map<Long, java.util.List<de.bulling.barcodebuddyscanner.Helper.StockOverview.ListRow>> rows =
				GrocyStock.parseShoppingRows(JsonParser.parseString("["
						+ "{\"id\":\"1\",\"product_id\":\"5\",\"amount\":\"2\",\"qu_id\":\"3\",\"shopping_list_id\":\"1\",\"done\":\"0\"},"
						+ "{\"id\":\"2\",\"product_id\":\"5\",\"amount\":\"6\",\"qu_id\":\"2\",\"shopping_list_id\":\"1\",\"done\":\"0\"},"
						+ "{\"id\":\"3\",\"product_id\":\"7\",\"amount\":\"1\",\"qu_id\":\"3\",\"shopping_list_id\":\"1\",\"done\":\"1\"},"
						+ "{\"id\":\"4\",\"product_id\":\"8\",\"amount\":\"1\",\"qu_id\":\"3\",\"shopping_list_id\":\"2\",\"done\":\"0\"}]"), units, 1);
		assertEquals(1, rows.size());
		assertEquals(2, rows.get(5L).size());
		assertEquals("Pack", rows.get(5L).get(0).unit);
		assertEquals(6.0, rows.get(5L).get(1).amount, 1e-9);
	}

	@Test
	public void newEntryBodyMatchesGrocysForm() {
		assertEquals("{\"product_id\":9,\"amount\":2.5,\"qu_id\":3,\"shopping_list_id\":1}",
				GrocyStock.newListEntryBody(9, 2.5, 3, 1).toString());
	}

	@Test
	public void rowsOfAnotherListAreSeparate() {
		java.util.Map<Long, String> units = new java.util.HashMap<>();
		java.util.Map<Long, java.util.List<de.bulling.barcodebuddyscanner.Helper.StockOverview.ListRow>> rows =
				GrocyStock.parseShoppingRows(JsonParser.parseString("["
						+ "{\"id\":\"1\",\"product_id\":\"5\",\"amount\":\"2\",\"qu_id\":\"3\",\"shopping_list_id\":\"1\",\"done\":\"0\"},"
						+ "{\"id\":\"4\",\"product_id\":\"8\",\"amount\":\"1\",\"qu_id\":\"3\",\"shopping_list_id\":\"2\",\"done\":\"0\"}]"), units, 2);
		assertEquals(1, rows.size());
		assertEquals(4L, rows.get(8L).get(0).id);
	}
}
