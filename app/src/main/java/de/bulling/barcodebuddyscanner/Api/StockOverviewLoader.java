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

	/** Der Bestand samt Zuständen: {@code entries} sind Produkte im Bestand, {@code missingOnly} Produkte unter Mindestbestand ohne Bestand. */
	public static final class Result {
		public final List<StockOverview.Entry> entries;
		public final List<StockOverview.Entry> missingOnly;
		public final int expired;
		public final int overdue;
		public final int dueSoon;
		public final int belowMin;

		Result(List<StockOverview.Entry> entries, List<StockOverview.Entry> missingOnly, GrocyStock.Volatile status) {
			this.entries     = entries;
			this.missingOnly = missingOnly;
			this.expired     = status == null ? 0 : status.expired.size();
			this.overdue     = status == null ? 0 : status.overdue.size();
			this.dueSoon     = status == null ? 0 : status.dueSoon.size();
			this.belowMin    = status == null ? 0 : status.belowMin.size();
		}
	}

	/** Wie {@link #load(GrocyClient, List, long)}, dazu die Einträge der Einkaufsliste (Einheitennamen aus {@code unitNames}). */
	public static List<StockOverview.Entry> load(GrocyClient grocy, List<ShoppingRepository.ProductRow> cached,
												 Map<Long, String> unitNames, long listId) throws IOException {
		return loadFull(grocy, cached, unitNames, listId).entries;
	}

	/** Wie {@link #load(GrocyClient, List, Map, long)}, dazu die Zustände der Produkte (abgelaufen, überfällig, bald fällig, zu wenig). */
	public static Result loadFull(GrocyClient grocy, List<ShoppingRepository.ProductRow> cached,
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
		List<StockOverview.Entry> entries = StockOverview.build(stock, products, onList);
		GrocyStock.Volatile status = null;
		try {
			status = GrocyStock.loadVolatile(grocy, GrocyStock.DUE_SOON_DAYS);
		} catch (IOException e) {
			// ohne Zustände zeigt die Übersicht einfach keine Markierung
		}
		List<StockOverview.Entry> missingOnly = new java.util.ArrayList<>();
		if (status != null) {
			java.util.Set<Long> inStock = new java.util.HashSet<>();
			for (StockOverview.Entry entry : entries) {
				inStock.add(entry.product.id);
				entry.expired  = status.expired.contains(entry.product.id);
				entry.overdue  = status.overdue.contains(entry.product.id);
				entry.dueSoon  = status.dueSoon.contains(entry.product.id);
				entry.belowMin = status.belowMin.contains(entry.product.id);
			}
			for (Long id : status.belowMin) {
				StockOverview.Product product = products.get(id);
				if (!inStock.contains(id) && product != null && product.name != null) {
					StockOverview.Entry entry = new StockOverview.Entry(product);
					entry.belowMin = true;
					List<StockOverview.ListRow> listed = onList.get(id);
					if (listed != null) {
						entry.list.addAll(listed);
					}
					missingOnly.add(entry);
				}
			}
		}
		return new Result(entries, missingOnly, status);
	}
}
