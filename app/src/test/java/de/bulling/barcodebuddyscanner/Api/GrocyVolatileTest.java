package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

public class GrocyVolatileTest {

	@Test
	public void readsTheProductNumbersPerState() {
		GrocyStock.Volatile v = GrocyStock.parseVolatile(JsonParser.parseString("{"
				+ "\"due_products\":[{\"product_id\":\"5\"}],"
				+ "\"overdue_products\":[{\"product_id\":\"7\"},{\"product_id\":\"7\"},{\"product_id\":\"8\"}],"
				+ "\"expired_products\":[],"
				+ "\"missing_products\":[{\"id\":\"73\",\"name\":\"Sirup\"}]}"));
		assertTrue(v.dueSoon.contains(5L));
		assertEquals(2, v.overdue.size());
		assertEquals(0, v.expired.size());
		assertTrue(v.belowMin.contains(73L));
	}

	@Test
	public void missingOrBrokenResponseGivesNoStates() {
		assertEquals(0, GrocyStock.parseVolatile(null).overdue.size());
		assertEquals(0, GrocyStock.parseVolatile(JsonParser.parseString("[]")).belowMin.size());
	}
}
