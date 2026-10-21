package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MoneyTest {

	@Test
	public void parsesCommaAndPoint() {
		assertEquals(1.49, Money.parse("1,49"), 0.00001);
		assertEquals(1.49, Money.parse("1.49"), 0.00001);
		assertEquals(2.0, Money.parse(" 2 € "), 0.00001);
	}

	@Test
	public void parsesThousandsSeparatorWithComma() {
		assertEquals(1234.56, Money.parse("1.234,56"), 1e-9);
		assertEquals(2.2, Money.parse("2,20"), 1e-9);
		assertEquals(0.2, Money.parse("0,20 €"), 1e-9);
	}

	@Test
	public void rejectsEmptyAndInvalidInput() {
		assertNull(Money.parse(null));
		assertNull(Money.parse(""));
		assertNull(Money.parse("abc"));
		assertNull(Money.parse("-1"));
	}

	@Test
	public void formatsGermanStyle() {
		assertTrue(Money.format(1.49).startsWith("1,49"));
		assertEquals("1,50", Money.formatPlain(1.5));
	}
}
