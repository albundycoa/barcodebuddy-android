package de.bulling.barcodebuddyscanner.Data;

/**
 * Ein gescannter Artikel. Solange {@link #tripId} 0 ist, steht er auf der
 * offenen Einkaufsliste; nach dem Senden gehört er zu einem Verlaufseintrag.
 */
public class ShoppingItem {

	public long   id;
	public long   tripId;
	public String barcode;
	/** null = Name (noch) unbekannt */
	public String name;
	public int    quantity;
	public long   scannedAt;
	/** Wie viele Stück davon schon an Barcode Buddy gesendet wurden */
	public int    sentCount;
	/** Letzte Antwort von Barcode Buddy */
	public String message;
	/** Fehler beim letzten Sendeversuch, null = kein Fehler */
	public String error;
	/** Vom Nutzer geänderter Preis je Preiseinheit (z. B. je Pack), null = nicht geändert */
	public Double price;
	/** Beim Senden verwendeter Preis je Scan, für den Verlauf */
	public Double scanPrice;
	/** Pfand je Scan (nur in der App mitgerechnet, nicht im Produktpreis), null = keins */
	public Double deposit;

	// ---- Aus dem Grocy-Speicher, nur beim Laden der offenen Liste gefüllt ----

	/** Der Barcode gehört zu einem Produkt, das Grocy kennt */
	public boolean known;
	/** Namensvorschlag von OpenFoodFacts für einen unbekannten Barcode, null = keiner */
	public String  suggestion;
	public Long    productId;
	/** Produktgruppe in Grocy, daraus ergibt sich das Pfand */
	public Long    groupId;
	public String  productName;
	public Long    shopId;
	public String  shopName;
	/** Lagereinheiten, die ein Scan bucht (z. B. 8 Stück für 1 Pack) */
	public double  stockPerScan   = 1;
	/** Faktor Lagereinheit -> Preiseinheit (Stück -> Pack = 0,125) */
	public double  stockToPrice   = 1;
	public String  stockUnitName;
	public String  priceUnitName;
	/** Letzter Einkaufspreis je Lagereinheit */
	public Double  lastPrice;
	/** Aktueller Bestand in Lagereinheiten */
	public Double  stock;
	/** Standard-Haltbarkeit in Tagen, -1 = unbegrenzt, null = unbekannt */
	public Integer bestBeforeDays;
	/** Produkt mit Tara-Gewicht: braucht eine Gewichtseingabe, das macht Barcode Buddy */
	public boolean tare;
	/** Der Barcode hat in Grocy eine eigene Menge, die App kennt also die gebuchte Menge sicher */
	public boolean exactAmount;

	/** Darf die App dieses Produkt direkt in Grocy buchen (statt über Barcode Buddy)? */
	public boolean canBookDirectly() {
		return known && productId != null && !tare && exactAmount;
	}

	public ShoppingItem copy() {
		ShoppingItem c = new ShoppingItem();
		c.id        = id;
		c.tripId    = tripId;
		c.barcode   = barcode;
		c.name      = name;
		c.quantity  = quantity;
		c.scannedAt = scannedAt;
		c.sentCount = sentCount;
		c.message   = message;
		c.error     = error;
		c.price     = price;
		c.scanPrice = scanPrice;
		c.deposit   = deposit;
		c.shopName  = shopName;
		return c;
	}

	/** Pfand für die ganze Zeile (Stückzahl mal Pfand je Scan). */
	public double depositTotal() {
		return deposit == null ? 0 : deposit * quantity;
	}

	/** Name aus Grocy, sonst der gespeicherte Name, sonst null. */
	public String displayName() {
		if (productName != null) {
			return productName;
		}
		return name;
	}

	/** Lagereinheiten je Scan umgerechnet in die Preiseinheit (bei 1 Pack je Scan = 1). */
	public double priceUnitsPerScan() {
		return stockPerScan * stockToPrice;
	}

	/** Preis je Preiseinheit: vom Nutzer geändert, sonst der letzte Einkaufspreis. */
	public Double effectivePrice() {
		if (price != null) {
			return price;
		}
		if (lastPrice != null && stockToPrice > 0) {
			return lastPrice / stockToPrice;
		}
		return null;
	}

	/** Preis für einen Scan (also für eine Packung). */
	public Double effectiveScanPrice() {
		Double p = effectivePrice();
		return p == null ? null : p * priceUnitsPerScan();
	}

	/** Preis je Lagereinheit, so wie ihn Grocy beim Einkauf erwartet. */
	public Double effectivePricePerStockUnit() {
		Double p = effectivePrice();
		return p == null ? null : p * stockToPrice;
	}
}
