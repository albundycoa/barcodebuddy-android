package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import de.bulling.barcodebuddyscanner.Helper.MarktguruShare.Item;

public class MarktguruShareTest {

	private static final String TEXT = "Einkaufsliste \"Grocy\"\nGesamt: € 5,77\n\n"
			+ "- 1x Speisekartoffel 2kg - € 1,99 26.10. - 31.10.2026 Lidl\nLink: https://marktguru.de/offers/25398077\n\n"
			+ "- 1x Äpfel 1kg - € 1,99 26.10. - 31.10.2026 Lidl\nLink: https://marktguru.de/offers/25397000\n\n"
			+ "- 2x Meica Curry King 0,22kg - € 1,79 26.10. - 31.10.2026 Lidl\nLink: https://marktguru.de/offers/25397587\n\n"
			+ "----------\nMöchtest du auch deine Einkaufsliste erstellen, mit den besten Angeboten?\nAndroid/iOS: https://app.adjust.com/28xw4ni";

	@Test
	public void parsesTheSharedList() {
		List<Item> items = MarktguruShare.parse(TEXT);
		assertEquals(3, items.size());
		assertEquals("Speisekartoffel 2kg", items.get(0).name);
		assertEquals(1, items.get(0).quantity);
		assertEquals("1,99", items.get(0).price);
		assertEquals("Lidl", items.get(0).shop);
		assertEquals("26.10. - 31.10.2026", items.get(0).validity);
		assertEquals("Äpfel 1kg", items.get(1).name);
		assertEquals("Meica Curry King 0,22kg", items.get(2).name);
		assertEquals(2, items.get(2).quantity);
		assertEquals("1,79", items.get(2).price);
	}

	@Test
	public void lineBreaksDoNotMatter() {
		String oneLine = TEXT.replace("\n", " ");
		assertEquals(3, MarktguruShare.parse(oneLine).size());
		assertEquals("Speisekartoffel 2kg", MarktguruShare.parse(oneLine).get(0).name);
	}

	@Test
	public void entryWithoutDatesOrPrice() {
		List<Item> items = MarktguruShare.parse("Einkaufsliste \"X\" Gesamt: € 0,00 - 1x Butter Link: https://marktguru.de/offers/1");
		assertEquals(1, items.size());
		assertEquals("Butter", items.get(0).name);
		assertNull(items.get(0).price);
		assertEquals("", items.get(0).shop);
	}

	@Test
	public void entryWithPriceButNoDates() {
		List<Item> items = MarktguruShare.parse("Kopf - 3x Milch 1l - € 0,95 Aldi Link: https://x.de/1");
		assertEquals(1, items.size());
		assertEquals("Milch 1l", items.get(0).name);
		assertEquals(3, items.get(0).quantity);
		assertEquals("0,95", items.get(0).price);
		assertEquals("Aldi", items.get(0).shop);
	}

	@Test
	public void plainTextBecomesOneEntryPerLine() {
		List<Item> items = MarktguruShare.parse("Milch\n- Brot\n\n* Eier");
		assertEquals(3, items.size());
		assertEquals("Milch", items.get(0).name);
		assertEquals("Brot", items.get(1).name);
		assertEquals("Eier", items.get(2).name);
		assertEquals(1, items.get(2).quantity);
	}

	@Test
	public void emptyTextGivesNothing() {
		assertTrue(MarktguruShare.parse("").isEmpty());
		assertTrue(MarktguruShare.parse("   \n ").isEmpty());
		assertNotNull(MarktguruShare.parse(null));
	}
}
