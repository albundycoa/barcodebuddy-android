package de.bulling.barcodebuddyscanner.Data;

/**
 * Ein abgeschickter Einkauf im Verlauf.
 */
public class Trip {

	public long    id;
	public long    sentAt;
	public int     itemCount;
	public int     unitCount;
	/** false = ein Teil der Artikel konnte nicht gesendet werden und blieb in der Liste */
	public boolean complete;
}
