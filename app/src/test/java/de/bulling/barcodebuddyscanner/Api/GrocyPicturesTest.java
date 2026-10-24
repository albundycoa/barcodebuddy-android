package de.bulling.barcodebuddyscanner.Api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonParser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public class GrocyPicturesTest {

	@Test
	public void findsProductsWithoutPicture() {
		Set<Long> ids = GrocyPictures.withoutPicture(JsonParser.parseString("["
				+ "{\"id\":\"1\",\"picture_file_name\":null},"
				+ "{\"id\":\"2\",\"picture_file_name\":\"x.jpg\"},"
				+ "{\"id\":\"3\",\"picture_file_name\":\"\"},"
				+ "{\"id\":\"4\"}]"));
		assertEquals(3, ids.size());
		assertTrue(ids.contains(1L));
		assertTrue(ids.contains(3L));
		assertTrue(ids.contains(4L));
		assertFalse(ids.contains(2L));
	}

	@Test
	public void fileNameEncodesWithoutPlusOrSlash() {
		for (String barcode : new String[]{"4056489186397", "8076802085981", "20005603", "4001724819981", "4000000000007"}) {
			String name = GrocyPictures.safeFileName(barcode);
			String encoded = java.util.Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8));
			assertFalse(encoded.contains("+"));
			assertFalse(encoded.contains("/"));
			assertTrue(name.endsWith(".jpg"));
		}
	}
}
