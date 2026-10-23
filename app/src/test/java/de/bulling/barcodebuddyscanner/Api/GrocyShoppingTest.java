package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;

import com.google.gson.JsonParser;

import org.junit.Test;

public class GrocyShoppingTest {

	@Test
	public void noteBodyHasNoProduct() {
		assertEquals("{\"note\":\"Blumen\",\"amount\":2.0,\"shopping_list_id\":1}",
				GrocyShopping.noteBody("Blumen", 2, 1).toString());
	}

	@Test
	public void createdIdIsReadFromTheAnswer() {
		assertEquals(42, GrocyShopping.createdId(JsonParser.parseString("{\"created_object_id\":42}")));
		assertEquals(42, GrocyShopping.createdId(JsonParser.parseString("{\"created_object_id\":\"42\"}")));
	}

	@Test
	public void missingIdIsMinusOne() {
		assertEquals(-1, GrocyShopping.createdId(JsonParser.parseString("{}")));
		assertEquals(-1, GrocyShopping.createdId(JsonParser.parseString("[]")));
		assertEquals(-1, GrocyShopping.createdId(null));
	}
}
