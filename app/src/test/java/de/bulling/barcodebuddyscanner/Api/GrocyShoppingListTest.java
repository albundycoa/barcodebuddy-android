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
	public void newEntryBodyMatchesGrocysForm() {
		assertEquals("{\"product_id\":9,\"amount\":2.5,\"qu_id\":3,\"shopping_list_id\":1}",
				GrocyStock.newListEntryBody(9, 2.5, 3).toString());
	}
}
