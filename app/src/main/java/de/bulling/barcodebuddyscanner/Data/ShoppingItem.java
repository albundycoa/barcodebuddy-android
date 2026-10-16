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
		return c;
	}
}
