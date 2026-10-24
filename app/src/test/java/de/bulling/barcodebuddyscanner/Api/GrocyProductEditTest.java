package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.List;

public class GrocyProductEditTest {

	@Test
	public void readsTheEditableFieldsOfAProduct() {
		GrocyProductEdit.Product p = GrocyProductEdit.parseProduct(JsonParser.parseString("{\"id\":\"70\",\"name\":\"Weizenbrötchen\","
				+ "\"product_group_id\":\"4\",\"location_id\":\"7\",\"shopping_location_id\":null,\"qu_id_purchase\":\"3\","
				+ "\"qu_id_stock\":\"2\",\"min_stock_amount\":\"2.5\",\"default_best_before_days\":\"-1\"}").getAsJsonObject());
		assertEquals(70, p.id);
		assertEquals(4, p.groupId);
		assertEquals(0, p.shopId);
		assertEquals(3, p.quPurchase);
		assertEquals(2.5, p.minStock, 0.0001);
		assertEquals(-1, p.bestBeforeDays);
	}

	@Test
	public void emptyGroupAndShopAreSentAsNull() {
		GrocyProductEdit.Product p = new GrocyProductEdit.Product();
		p.name = "X";
		p.locationId = 7;
		p.quPurchase = 3;
		JsonObject body = GrocyProductEdit.productBody(p);
		assertTrue(body.get("product_group_id").isJsonNull());
		assertTrue(body.get("shopping_location_id").isJsonNull());
		assertEquals(7, body.get("location_id").getAsLong());
		assertEquals(3, body.get("qu_id_purchase").getAsLong());
	}

	@Test
	public void readsBarcodesWithShopUnitAndAmount() {
		List<GrocyProductEdit.Barcode> list = GrocyProductEdit.parseBarcodes(JsonParser.parseString("["
				+ "{\"id\":\"1\",\"product_id\":\"70\",\"barcode\":\"4001\",\"shopping_location_id\":\"1\",\"qu_id\":\"3\",\"amount\":\"8\"},"
				+ "{\"id\":\"2\",\"product_id\":\"70\",\"barcode\":\"4002\",\"shopping_location_id\":null,\"qu_id\":null,\"amount\":null}]"));
		assertEquals(2, list.size());
		assertEquals("4001", list.get(0).barcode);
		assertEquals(8.0, list.get(0).amount, 0.0001);
		assertEquals(1, list.get(0).shopId);
		assertNull(list.get(1).amount);
		assertEquals(0, list.get(1).quId);
	}

	@Test
	public void barcodeBodyKeepsEmptyFieldsAsNull() {
		GrocyProductEdit.Barcode b = new GrocyProductEdit.Barcode();
		b.productId = 70;
		b.barcode = "4001";
		JsonObject body = GrocyProductEdit.barcodeBody(b);
		assertEquals("4001", body.get("barcode").getAsString());
		assertTrue(body.get("qu_id").isJsonNull());
		assertTrue(body.get("amount").isJsonNull());
		assertTrue(body.get("shopping_location_id").isJsonNull());
	}
}
