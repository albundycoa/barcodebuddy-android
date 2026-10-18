package de.bulling.barcodebuddyscanner.Update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class UpdateInfoTest {

	private static final String SHA = "2ba7e62e7456e16acc0e10d76433d44d4daaf52dde6549ea940d500bafab71ff";

	private static String json(String version, int code, String url, String sha) {
		return "{\"version\":\"" + version + "\",\"version_code\":" + code + ",\"url\":\"" + url
				+ "\",\"size\":7292146,\"sha256\":\"" + sha + "\",\"notes\":\"Neu: Inventur\"}";
	}

	@Test
	public void readsAValidDescription() {
		UpdateInfo info = UpdateInfo.parse(json("0.2.0", 200, "https://github.com/x/y/releases/download/v0.2.0/a.apk", SHA));
		assertEquals("0.2.0", info.version);
		assertEquals(200, info.version_code);
		assertEquals(7292146L, info.size);
		assertEquals("Neu: Inventur", info.notes);
	}

	@Test
	public void onlyAHigherVersionCodeCountsAsNewer() {
		UpdateInfo info = UpdateInfo.parse(json("0.2.0", 200, "https://example.org/a.apk", SHA));
		assertTrue(info.isNewerThan(100));
		assertFalse(info.isNewerThan(200));
		assertFalse(info.isNewerThan(300));
	}

	@Test
	public void checksumIsNormalisedToLowerCase() {
		UpdateInfo info = UpdateInfo.parse(json("0.2.0", 200, "https://example.org/a.apk", SHA.toUpperCase()));
		assertEquals(SHA, info.sha256);
	}

	@Test
	public void rejectsAddressesThatAreNotHttps() {
		assertRejected(json("0.2.0", 200, "http://example.org/a.apk", SHA));
		assertRejected(json("0.2.0", 200, "file:///sdcard/a.apk", SHA));
	}

	@Test
	public void rejectsBrokenChecksumsAndVersions() {
		assertRejected(json("0.2.0", 200, "https://example.org/a.apk", "abc"));
		assertRejected(json("0.2.0", 200, "https://example.org/a.apk", SHA.substring(1)));
		assertRejected(json("", 200, "https://example.org/a.apk", SHA));
		assertRejected(json("0.2.0", 0, "https://example.org/a.apk", SHA));
	}

	@Test
	public void rejectsGarbage() {
		assertRejected("<html>Nicht gefunden</html>");
		assertRejected("");
		assertRejected("null");
	}

	@Test
	public void keepsAJsonRoundTrip() {
		UpdateInfo info = UpdateInfo.parse(json("0.2.0", 200, "https://example.org/a.apk", SHA));
		UpdateInfo again = UpdateInfo.parse(info.toJson());
		assertEquals(info.version, again.version);
		assertEquals(info.sha256, again.sha256);
	}

	private static void assertRejected(String text) {
		try {
			UpdateInfo.parse(text);
			fail("Hätte abgelehnt werden müssen: " + text);
		} catch (IllegalArgumentException expected) {
			// richtig so
		}
	}
}
