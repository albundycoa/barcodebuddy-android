package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.bulling.barcodebuddyscanner.Helper.WearCatalogJson.Product;

public class WearCatalogJsonTest {

	@Test
	public void productsAndGroupsAreWritten() {
		List<Product> products = new ArrayList<>();
		products.add(new Product(7, "Cola", "Getränk"));
		products.add(new Product(8, "Nudeln", null));
		JsonObject root = JsonParser.parseString(WearCatalogJson.build(products, Arrays.asList("Fleisch", "Getränk"))).getAsJsonObject();
		assertEquals(2, root.getAsJsonArray("p").size());
		JsonObject cola = root.getAsJsonArray("p").get(0).getAsJsonObject();
		assertEquals(7, cola.get("id").getAsLong());
		assertEquals("Getränk", cola.get("g").getAsString());
		assertFalse(root.getAsJsonArray("p").get(1).getAsJsonObject().has("g"));
		assertEquals("Fleisch", root.getAsJsonArray("g").get(0).getAsString());
	}

	@Test
	public void emptyNamesAndGroupsAreSkipped() {
		List<Product> products = new ArrayList<>();
		products.add(new Product(1, "  ", "X"));
		products.add(new Product(2, null, "X"));
		JsonObject root = JsonParser.parseString(WearCatalogJson.build(products, Arrays.asList("", null, "Eis"))).getAsJsonObject();
		assertEquals(0, root.getAsJsonArray("p").size());
		assertEquals(1, root.getAsJsonArray("g").size());
	}
}
