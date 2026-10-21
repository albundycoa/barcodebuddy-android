package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Helper.StockOverview.Entry;
import de.bulling.barcodebuddyscanner.Helper.StockOverview.Product;
import de.bulling.barcodebuddyscanner.Helper.StockOverview.StockRow;

public class WearStockJsonTest {

	private static List<Entry> entries() {
		Product cola = new Product();
		cola.id = 7;
		cola.name = "Cola";
		cola.unit = "Stück";
		Product mehl = new Product();
		mehl.id = 3;
		mehl.name = "Äpfel";
		mehl.unit = "Pack";
		mehl.consumeAmount = 2;
		Map<Long, Product> products = new HashMap<>();
		products.put(7L, cola);
		products.put(3L, mehl);
		List<StockRow> rows = new ArrayList<>();
		rows.add(new StockRow(7, 4, false, 0.5, null));
		rows.add(new StockRow(7, 2, true, 0.5, null));
		rows.add(new StockRow(3, 1, false, 2.0, null));
		return StockOverview.build(rows, products);
	}

	@Test
	public void buildsCompactItemsSortedByName() {
		JsonObject root = JsonParser.parseString(
				WearStockJson.build(entries(), 1000L, "%1$s geöffnet", null, null, null)).getAsJsonObject();
		assertEquals(2, root.get("n").getAsInt());
		assertTrue(root.get("v").getAsString().startsWith("5,00"));
		JsonArray items = root.getAsJsonArray("i");
		JsonObject first = items.get(0).getAsJsonObject();
		assertEquals("Äpfel", first.get("n").getAsString());
		assertEquals(2.0, first.get("cq").getAsDouble(), 1e-9);
		// 1 Pack, davon nichts geöffnet, Schnellverbrauch 2: reicht nicht
		assertFalse(first.get("fc").getAsBoolean());
		assertTrue(first.get("fa").getAsBoolean());
		JsonObject cola = items.get(1).getAsJsonObject();
		assertEquals("6 Stück · 2 geöffnet", cola.get("a").getAsString());
		assertEquals(6.0, cola.get("am").getAsDouble(), 1e-9);
		assertTrue(cola.get("fo").getAsBoolean());
		assertFalse(root.has("m"));
		assertFalse(root.has("err"));
	}

	@Test
	public void messageAndUndoNumberAreOnlyPresentWhenGiven() {
		JsonObject root = JsonParser.parseString(
				WearStockJson.build(entries(), 5000L, "%1$s geöffnet", "Cola: 1 Stück verbraucht", "abc123", null)).getAsJsonObject();
		assertEquals("Cola: 1 Stück verbraucht", root.get("m").getAsString());
		assertEquals("abc123", root.get("tx").getAsString());
		assertEquals(5000L, root.get("mt").getAsLong());

		JsonObject noTx = JsonParser.parseString(
				WearStockJson.build(entries(), 5000L, "%1$s geöffnet", "Fehler", null, null)).getAsJsonObject();
		assertTrue(noTx.has("m"));
		assertFalse(noTx.has("tx"));
	}

	@Test
	public void errorIsCarried() {
		JsonObject root = JsonParser.parseString(
				WearStockJson.build(new ArrayList<>(), 1L, "%1$s geöffnet", null, null, "Grocy nicht erreichbar")).getAsJsonObject();
		assertEquals("Grocy nicht erreichbar", root.get("err").getAsString());
		assertEquals(0, root.getAsJsonArray("i").size());
	}
}
