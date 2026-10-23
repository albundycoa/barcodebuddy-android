package de.bulling.barcodebuddyscanner.Api;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.StockOverview;

/**
 * Lädt die Bestandsübersicht live aus Grocy (Handy-Seite und Uhr nutzen denselben Weg). Blockiert, gehört also auf einen
 * Hintergrund-Thread. Name, Gruppe und Einheit der Produkte kommen aus der lokalen Kopie der Grocy-Daten.
 */
public final class StockOverviewLoader {

	private StockOverviewLoader() {
	}

	/** @throws IOException wenn Grocy den Bestand nicht liefert; fehlen nur die Produkteinstellungen, gelten Standardwerte */
	public static List<StockOverview.Entry> load(GrocyClient grocy, List<ShoppingRepository.ProductRow> cached, long listId)
			throws IOException {
		return load(grocy, cached, new HashMap<>(), listId);
	}

	/** Wie {@link #load(GrocyClient, List, long)}, dazu die Einträge der Einkaufsliste (Einheitennamen aus {@code unitNames}). */
	public static List<StockOverview.Entry> load(GrocyClient grocy, List<ShoppingRepository.ProductRow> cached,
												 Map<Long, String> unitNames, long listId) throws IOException {
		List<StockOverview.StockRow> stock = GrocyStock.loadStockRows(grocy);
		Map<Long, StockOverview.Product> products;
		try {
			products = GrocyStock.loadProductSetups(grocy);
		} catch (IOException e) {
			// ohne Einstellungen gelten die Standardwerte, die Buchung selbst prüft Grocy
			products = new HashMap<>();
		}
		for (ShoppingRepository.ProductRow row : cached) {
			StockOverview.Product product = products.get(row.id);
			if (product == null) {
				product = new StockOverview.Product();
				product.id = row.id;
				products.put(row.id, product);
			}
			product.name  = row.name;
			product.group = row.group;
			product.unit  = row.stockUnit;
		}
		Map<Long, java.util.List<StockOverview.ListRow>> onList;
		try {
			onList = GrocyStock.loadShoppingRows(grocy, unitNames, listId, products);
		} catch (IOException e) {
			// ohne Einkaufsliste zeigt die Übersicht einfach keine Markierung
			onList = new HashMap<>();
		}
		return StockOverview.build(stock, products, onList);
	}
}
