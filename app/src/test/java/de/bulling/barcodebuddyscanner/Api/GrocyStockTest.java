package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import com.google.gson.JsonParser;

import org.junit.Test;

public class GrocyStockTest {

	@Test
	public void consumeBodyMatchesGrocyApi() {
		assertEquals("{\"amount\":1.0,\"transaction_type\":\"consume\",\"spoiled\":false}",
				GrocyStock.consumeBody(1).toString());
		assertEquals(2.5, GrocyStock.consumeBody(2.5).get("amount").getAsDouble(), 1e-9);
	}

	@Test
	public void openBodyHasOnlyTheAmount() {
		assertEquals("{\"amount\":2.0}", GrocyStock.openBody(2).toString());
		assertFalse(GrocyStock.openBody(1).has("transaction_type"));
	}

	@Test
	public void transactionIdIsTakenFromTheFirstBookedRow() {
		assertEquals("6ad631d698cc9", GrocyStock.transactionId(JsonParser.parseString(
				"[{\"id\":\"1190\",\"transaction_id\":\"6ad631d698cc9\"},{\"id\":\"1191\",\"transaction_id\":\"6ad631d698cc9\"}]")));
	}

	@Test
	public void missingTransactionIdGivesNull() {
		assertNull(GrocyStock.transactionId(null));
		assertNull(GrocyStock.transactionId(JsonParser.parseString("[]")));
		assertNull(GrocyStock.transactionId(JsonParser.parseString("{}")));
		assertNull(GrocyStock.transactionId(JsonParser.parseString("[{\"id\":1}]")));
	}
}
