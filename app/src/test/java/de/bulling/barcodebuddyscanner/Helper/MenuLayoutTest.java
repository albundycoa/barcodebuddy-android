package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MenuLayoutTest {

	private static final List<String> DEFAULTS = Arrays.asList("history", "products", "inventory", "settings");

	@Test
	public void keepsTheSavedOrder() {
		List<String> saved = Arrays.asList("settings", "inventory", "history", "products");
		assertEquals(saved, MenuLayout.mergeOrder(DEFAULTS, saved));
	}

	@Test
	public void newEntriesComeAtTheEndInDefaultOrder() {
		List<String> saved = Arrays.asList("inventory", "history");
		assertEquals(Arrays.asList("inventory", "history", "products", "settings"),
				MenuLayout.mergeOrder(DEFAULTS, saved));
	}

	@Test
	public void entriesThatNoLongerExistAreDropped() {
		List<String> saved = Arrays.asList("gone", "products", "history", "history");
		assertEquals(Arrays.asList("products", "history", "inventory", "settings"),
				MenuLayout.mergeOrder(DEFAULTS, saved));
	}

	@Test
	public void emptySavedOrderGivesTheDefaults() {
		assertEquals(DEFAULTS, MenuLayout.mergeOrder(DEFAULTS, Collections.<String>emptyList()));
	}

	@Test
	public void splitAndJoinRoundTrip() {
		List<String> keys = Arrays.asList("a", "b", "c");
		assertEquals("a,b,c", MenuLayout.join(keys));
		assertEquals(keys, MenuLayout.split("a, b ,c,,a"));
		assertTrue(MenuLayout.split(null).isEmpty());
		assertTrue(MenuLayout.split("").isEmpty());
	}

	@Test
	public void settingsCannotBeHidden() {
		assertTrue(MenuLayout.isMandatory("action_settings"));
	}
}
