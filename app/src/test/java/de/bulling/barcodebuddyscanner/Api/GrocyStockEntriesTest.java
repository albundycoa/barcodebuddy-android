package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

public class GrocyStockEntriesTest {

	@Test
	public void readsEntriesSortedByDueDate() {
		List<GrocyStockEntries.Entry> list = GrocyStockEntries.parse(JsonParser.parseString("["
				+ "{\"id\":\"206\",\"stock_id\":\"a\",\"product_id\":\"70\",\"amount\":\"3\",\"best_before_date\":\"2999-12-31\","
				+ "\"purchased_date\":\"2026-10-07\",\"price\":\"0.14875\",\"open\":\"0\",\"location_id\":\"7\",\"shopping_location_id\":\"1\"},"
				+ "{\"id\":\"311\",\"stock_id\":\"b\",\"product_id\":\"70\",\"amount\":\"8\",\"best_before_date\":\"2026-10-23\","
				+ "\"price\":null,\"open\":\"1\",\"location_id\":\"7\"},"
				+ "{\"id\":\"5\",\"stock_id\":\"c\",\"product_id\":\"70\",\"amount\":\"0\",\"best_before_date\":\"2026-01-01\"}]"));
		assertEquals(2, list.size());
		assertEquals("b", list.get(0).stockId);
		assertTrue(list.get(0).open);
		assertEquals(null, list.get(0).price);
		assertTrue(list.get(1).hasNoDueDate());
		assertEquals(0.14875, list.get(1).price, 0.000001);
	}

	@Test
	public void editBodyAlwaysSendsAllFieldsBecauseGrocyUsesDefaultsOtherwise() {
		GrocyStockEntries.Entry e = new GrocyStockEntries.Entry();
		e.amount = 3;
		e.due = null;
		e.price = 0.14875;
		e.open = false;
		e.locationId = 7;
		e.shopId = 1;
		e.purchased = "2026-10-07";
		JsonObject body = GrocyStockEntries.editBody(e);
		assertEquals("2999-12-31", body.get("best_before_date").getAsString());
		assertEquals(0.14875, body.get("price").getAsDouble(), 0.000001);
		assertFalse(body.get("open").getAsBoolean());
		assertEquals(7, body.get("location_id").getAsLong());
		assertEquals(1, body.get("shopping_location_id").getAsLong());
		assertEquals("2026-10-07", body.get("purchased_date").getAsString());
	}
}
