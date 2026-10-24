package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OpenFoodFactsTest {

	@Test
	public void nameWithBrandAndQuantity() {
		String json = "{\"status\":1,\"product\":{\"product_name\":\"Goldbären\",\"brands\":\"Haribo,Haribo Gmbh\","
				+ "\"quantity\":\"200 g\"}}";
		assertEquals("Haribo Goldbären (200 g)", OpenFoodFacts.parseName(json));
	}

	@Test
	public void germanNameWins() {
		String json = "{\"status\":1,\"product\":{\"product_name\":\"Gummy bears\",\"product_name_de\":\"Fruchtgummi\"}}";
		assertEquals("Fruchtgummi", OpenFoodFacts.parseName(json));
	}

	@Test
	public void brandAlreadyInNameIsNotRepeated() {
		String json = "{\"status\":1,\"product\":{\"product_name\":\"Nutella 450 g\",\"brands\":\"Nutella\","
				+ "\"quantity\":\"450 g\"}}";
		assertEquals("Nutella 450 g", OpenFoodFacts.parseName(json));
	}

	@Test
	public void genericNameIsLastResort() {
		String json = "{\"status\":1,\"product\":{\"product_name\":\"\",\"generic_name\":\"Haferflocken\"}}";
		assertEquals("Haferflocken", OpenFoodFacts.parseName(json));
	}

	@Test
	public void unknownOrUnusableProductGivesNull() {
		assertNull(OpenFoodFacts.parseName("{\"status\":0,\"status_verbose\":\"product not found\"}"));
		assertNull(OpenFoodFacts.parseName("{\"status\":1,\"product\":{\"brands\":\"Haribo\"}}"));
		assertNull(OpenFoodFacts.parseName("{\"status\":1}"));
		assertNull(OpenFoodFacts.parseName("kein json"));
		assertNull(OpenFoodFacts.parseName("[]"));
	}

	@Test
	public void onlyProductBarcodesAreLookedUp() {
		assertTrue(OpenFoodFacts.isProductBarcode("4001724819875"));
		assertTrue(OpenFoodFacts.isProductBarcode("12345670"));
		assertFalse(OpenFoodFacts.isProductBarcode("1234567"));
		assertFalse(OpenFoodFacts.isProductBarcode("123456789012345"));
		assertFalse(OpenFoodFacts.isProductBarcode("https://example.com/x"));
		assertFalse(OpenFoodFacts.isProductBarcode("12345abc90123"));
		assertFalse(OpenFoodFacts.isProductBarcode(null));
	}

	@Test
	public void readsTheSmallFrontImageAddress() {
		assertEquals("https://images.openfoodfacts.org/images/products/807/680/208/5981/front_en.44.200.jpg",
				OpenFoodFacts.parseImageUrl("{\"status\":1,\"product\":{\"image_front_small_url\":"
						+ "\"https://images.openfoodfacts.org/images/products/807/680/208/5981/front_en.44.200.jpg\"}}"));
		// ohne kleines Bild das Vorschaubild, ohne beides nichts; nur https-Adressen
		assertEquals("https://x/t.jpg", OpenFoodFacts.parseImageUrl("{\"product\":{\"image_front_thumb_url\":\"https://x/t.jpg\"}}"));
		org.junit.Assert.assertNull(OpenFoodFacts.parseImageUrl("{\"product\":{}}"));
		org.junit.Assert.assertNull(OpenFoodFacts.parseImageUrl("{\"product\":{\"image_front_small_url\":\"http://x/a.jpg\"}}"));
		org.junit.Assert.assertNull(OpenFoodFacts.parseImageUrl("kaputt"));
	}
}
