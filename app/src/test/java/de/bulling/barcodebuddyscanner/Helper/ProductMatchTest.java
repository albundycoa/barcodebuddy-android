package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Helper.ProductSearch.Item;

public class ProductMatchTest {

	private static List<Item> products() {
		List<Item> list = new ArrayList<>();
		list.add(new Item(1, "Kartoffeln", "Obst / Gemüse"));
		list.add(new Item(2, "Eis", "Eis"));
		list.add(new Item(3, "Eisbergsalat", "Obst / Gemüse"));
		list.add(new Item(4, "Curry", "Gewürze"));
		list.add(new Item(5, "Apfel süß-säuerlich", "Obst"));
		list.add(new Item(6, "Müsli-Riegel Erdnuss", "Süßigkeiten"));
		list.add(new Item(7, "Kartoffel Püree", "Fertiggericht"));
		return list;
	}

	@Test
	public void wordsInsideOtherWordsMatch() {
		Item hit = ProductMatch.best("Speisekartoffel 2kg", products());
		assertNotNull(hit);
		assertEquals(1, hit.id);
	}

	@Test
	public void exactProductWinsOverShorterGuess() {
		Item hit = ProductMatch.best("Eisbergsalat 1 Stück", products());
		assertNotNull(hit);
		assertEquals(3, hit.id);
	}

	@Test
	public void shortWordsDoNotMatchInsideLongOnes() {
		// "Eis" steckt in "Eisbergsalat", ist aber ein anderes Produkt
		Item hit = ProductMatch.best("Eisbergsalat", products());
		assertEquals(3, hit.id);
	}

	@Test
	public void allWordsOfTheProductCount() {
		Item hit = ProductMatch.best("Müsli-Riegel Erdnuss 8er", products());
		assertEquals(6, hit.id);
	}

	@Test
	public void weakMatchesGiveNothing() {
		assertNull(ProductMatch.best("Meica Curry King 0,22kg", java.util.Collections.singletonList(new Item(9, "Königsberger Klopse", null))));
		// Nur ein Wort von mehreren reicht nicht: "Kartoffel Püree" ist nicht "Speisekartoffel"
		assertNull(ProductMatch.best("Speisekartoffel 2kg", java.util.Collections.singletonList(new Item(7, "Kartoffel Püree", null))));
		// und ein längeres Produktwort ist nicht das Wort des Namens ("Apfelecken" ist nicht "Äpfel")
		assertNull(ProductMatch.best("Äpfel 1kg", java.util.Collections.singletonList(new Item(8, "Apfelecken", null))));
		assertNull(ProductMatch.best("Äpfel 1kg", products()));
		// "Curry" ist nur ein Drittel von "Meica Curry King", das Gewürz ist ein anderes Produkt
		assertNull(ProductMatch.best("Meica Curry King 0,22kg", products()));
	}

	@Test
	public void unknownNameGivesNothing() {
		assertNull(ProductMatch.best("Zahnpasta", products()));
		assertNull(ProductMatch.best("", products()));
	}
}
