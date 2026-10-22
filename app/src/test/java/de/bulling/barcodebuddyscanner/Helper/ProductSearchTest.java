package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Helper.ProductSearch.Item;

public class ProductSearchTest {

	private static List<Item> items() {
		List<Item> list = new ArrayList<>();
		list.add(new Item(1, "Sirup Orange", "Getränk"));
		list.add(new Item(2, "Orangensaft", "Getränk"));
		list.add(new Item(3, "Äpfel", "Obst"));
		list.add(new Item(4, "Apfel süß-säuerlich", "Obst"));
		list.add(new Item(5, "Kartoffel Püree", "Fertiggericht"));
		return list;
	}

	@Test
	public void startsWithComesBeforeContains() {
		List<Item> result = ProductSearch.search(items(), "orange", 10);
		assertEquals(2, result.size());
		assertEquals("Orangensaft", result.get(0).name);
		assertEquals("Sirup Orange", result.get(1).name);
	}

	@Test
	public void umlautsAndCaseDoNotMatter() {
		List<Item> result = ProductSearch.search(items(), "APFEL", 10);
		assertEquals(2, result.size());
		assertEquals("Äpfel", result.get(0).name);
		assertEquals("Apfel süß-säuerlich", result.get(1).name);
		assertEquals(1, ProductSearch.search(items(), "puree", 10).size());
		assertEquals(1, ProductSearch.search(items(), "suss", 10).size());
	}

	@Test
	public void allWordsMustMatch() {
		assertEquals(1, ProductSearch.search(items(), "sirup orange", 10).size());
		assertEquals(0, ProductSearch.search(items(), "sirup apfel", 10).size());
	}

	@Test
	public void emptyQueryListsAlphabeticallyAndRespectsLimit() {
		List<Item> all = ProductSearch.search(items(), "  ", 3);
		assertEquals(3, all.size());
		assertEquals("Äpfel", all.get(0).name);
		assertTrue(ProductSearch.search(items(), "", 100).size() == 5);
	}

	@Test
	public void noMatchGivesEmptyList() {
		assertEquals(0, ProductSearch.search(items(), "xyz", 10).size());
	}
}
