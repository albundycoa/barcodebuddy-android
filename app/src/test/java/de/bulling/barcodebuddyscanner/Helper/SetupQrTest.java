package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class SetupQrTest {

	@Test
	public void readsTheBarcodeBuddyCode() {
		SetupQr.Credentials c = SetupQr.parseBarcodeBuddy(
				"{\"issetup\":true,\"url\":\"https://bb.example.de/api/\",\"key\":\"abc123\"}");
		assertNotNull(c);
		assertEquals("https://bb.example.de/api/", c.url);
		assertEquals("abc123", c.key);
	}

	@Test
	public void rejectsForeignBarcodeBuddyCodes() {
		assertNull(SetupQr.parseBarcodeBuddy(null));
		assertNull(SetupQr.parseBarcodeBuddy("4006381333931"));
		assertNull(SetupQr.parseBarcodeBuddy("{\"issetup\":true}"));
		assertNull(SetupQr.parseBarcodeBuddy("{\"issetup\":true,\"url\":\"\",\"key\":\"x\"}"));
		assertNull(SetupQr.parseBarcodeBuddy("issetup but not json"));
	}

	@Test
	public void readsTheGrocyCode() {
		SetupQr.Credentials c = SetupQr.parseGrocy("https://g.example.de/api|SECRETKEY");
		assertNotNull(c);
		assertEquals("https://g.example.de/api", c.url);
		assertEquals("SECRETKEY", c.key);
	}

	@Test
	public void grocyCodeUsesTheLastSeparator() {
		SetupQr.Credentials c = SetupQr.parseGrocy("http://host/a|b/api|KEY");
		assertNotNull(c);
		assertEquals("http://host/a|b/api", c.url);
		assertEquals("KEY", c.key);
	}

	@Test
	public void rejectsBrokenGrocyCodes() {
		assertNull(SetupQr.parseGrocy(null));
		assertNull(SetupQr.parseGrocy("keinTrenner"));
		assertNull(SetupQr.parseGrocy("|nurSchluessel"));
		assertNull(SetupQr.parseGrocy("nurAdresse|"));
	}

	@Test
	public void addsTheTrailingSlash() {
		assertEquals("https://bb.example.de/api/", SetupQr.withTrailingSlash("https://bb.example.de/api"));
		assertEquals("https://bb.example.de/api/", SetupQr.withTrailingSlash(" https://bb.example.de/api/ "));
	}

	@Test
	public void findsTheFirstMissingStep() {
		assertEquals(1, SetupQr.firstMissingStep(false, false));
		assertEquals(1, SetupQr.firstMissingStep(false, true));
		assertEquals(2, SetupQr.firstMissingStep(true, false));
		assertEquals(0, SetupQr.firstMissingStep(true, true));
	}
}
