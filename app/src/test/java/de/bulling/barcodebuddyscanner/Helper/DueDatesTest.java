package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

public class DueDatesTest {

	private static final TimeZone ZONE = TimeZone.getTimeZone("Europe/Berlin");

	private static long at(int year, int month, int day, int hour) {
		Calendar c = Calendar.getInstance(ZONE);
		c.clear();
		c.set(year, month - 1, day, hour, 30);
		return c.getTimeInMillis();
	}

	@Test
	public void countsDaysFromToday() {
		long now = at(2026, 10, 18, 15);
		assertEquals(Integer.valueOf(0), DueDates.daysUntil("2026-10-18", now, ZONE));
		assertEquals(Integer.valueOf(3), DueDates.daysUntil("2026-10-21", now, ZONE));
		assertEquals(Integer.valueOf(-2), DueDates.daysUntil("2026-10-16", now, ZONE));
	}

	@Test
	public void lateEveningStillCountsAsToday() {
		long now = at(2026, 10, 18, 23);
		assertEquals(Integer.valueOf(0), DueDates.daysUntil("2026-10-18", now, ZONE));
		assertEquals(Integer.valueOf(1), DueDates.daysUntil("2026-10-19", now, ZONE));
	}

	@Test
	public void clockChangeDoesNotShiftTheDay() {
		// Zeitumstellung am 25.10.2026: der Tag hat 25 Stunden
		long now = at(2026, 10, 24, 12);
		assertEquals(Integer.valueOf(2), DueDates.daysUntil("2026-10-26", now, ZONE));
	}

	@Test
	public void invalidDatesGiveNull() {
		long now = at(2026, 10, 18, 12);
		assertNull(DueDates.daysUntil(null, now, ZONE));
		assertNull(DueDates.daysUntil("morgen", now, ZONE));
		assertNull(DueDates.daysUntil("2026-13-40", now, ZONE));
	}
}
