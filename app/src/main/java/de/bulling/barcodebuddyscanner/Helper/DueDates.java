package de.bulling.barcodebuddyscanner.Helper;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Rechnen mit Mindesthaltbarkeitsdaten im Format "yyyy-MM-dd". */
public final class DueDates {

	private DueDates() {
	}

	/**
	 * Tage von heute bis zum Datum: 0 = heute, negativ = abgelaufen. Ungültige Angaben ergeben null.
	 *
	 * @param nowMillis aktueller Zeitpunkt, zur Zeitzone des Geräts
	 */
	public static Integer daysUntil(String isoDate, long nowMillis, TimeZone zone) {
		if (isoDate == null) {
			return null;
		}
		SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
		parser.setTimeZone(zone);
		parser.setLenient(false);
		Date due;
		try {
			due = parser.parse(isoDate);
		} catch (ParseException e) {
			return null;
		}
		Calendar today = Calendar.getInstance(zone);
		today.setTimeInMillis(nowMillis);
		today.set(Calendar.HOUR_OF_DAY, 0);
		today.set(Calendar.MINUTE, 0);
		today.set(Calendar.SECOND, 0);
		today.set(Calendar.MILLISECOND, 0);
		// Gerundet, weil bei der Zeitumstellung ein Tag 23 oder 25 Stunden hat
		return (int) Math.round((due.getTime() - today.getTimeInMillis()) / 86_400_000.0);
	}

	public static Integer daysUntil(String isoDate) {
		return daysUntil(isoDate, System.currentTimeMillis(), TimeZone.getDefault());
	}
}
