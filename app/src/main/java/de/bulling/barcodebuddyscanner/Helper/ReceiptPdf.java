package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.IOException;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.R;

/**
 * Erzeugt einen Beleg als PDF, nach Geschäften getrennt (eine Seite je Geschäft, bei vielen
 * Artikeln mehrere), zum Vergleichen mit dem echten Kassenbon.
 */
public final class ReceiptPdf {

	private static final int PAGE_WIDTH  = 595;  // A4 in Punkten
	private static final int PAGE_HEIGHT = 842;
	private static final int MARGIN      = 40;
	private static final int ROW_HEIGHT  = 34;

	private ReceiptPdf() {
	}

	public static class Totals {
		/** Summe inklusive Pfand */
		public double sum;
		/** Darin enthaltenes Pfand */
		public double deposit;
		/** Abgegebenes Pfand (Pfandbon), schon von der Summe abgezogen */
		public double depositReturn;
		public int    withoutPrice;
	}

	/** Summe der Zeile: Stückzahl mal Preis je Scan; null, wenn kein Preis bekannt ist. */
	public static Double lineTotal(ShoppingItem item) {
		Double scan = item.effectiveScanPrice();
		if (scan == null) {
			scan = item.scanPrice;
		}
		return scan == null ? null : scan * item.quantity;
	}

	public static Totals totals(List<ShoppingItem> items) {
		return totals(items, 0);
	}

	/** Wie {@link #totals(List)}, dazu das abgegebene Pfand: Es wird von der Summe abgezogen. */
	public static Totals totals(List<ShoppingItem> items, double depositReturn) {
		Totals totals = new Totals();
		for (ShoppingItem item : items) {
			Double line = lineTotal(item);
			if (line == null) {
				totals.withoutPrice++;
			} else {
				totals.sum += line;
			}
			totals.sum     += item.depositTotal();
			totals.deposit += item.depositTotal();
		}
		totals.depositReturn = Math.max(0, depositReturn);
		totals.sum -= totals.depositReturn;
		return totals;
	}

	/** Name des Abschnitts, in den der Artikel gehört. */
	public static String groupName(Context context, ShoppingItem item) {
		if (item.shopName != null) {
			return item.shopName;
		}
		if (item.tripId == 0 && !item.known) {
			return context.getString(R.string.section_new_short);
		}
		return context.getString(R.string.section_none);
	}

	public static void write(Context context, OutputStream out, List<ShoppingItem> items, long timestamp)
			throws IOException {
		write(context, out, items, timestamp, 0);
	}

	/** Beleg mit dem beim Einkauf abgegebenen Pfand (Betrag vom Pfandbon), das von der Gesamtsumme abgeht. */
	public static void write(Context context, OutputStream out, List<ShoppingItem> items, long timestamp,
							 double depositReturn) throws IOException {
		// Geschäfte in der Reihenfolge ihres ersten Auftretens, Artikel je Geschäft nach Name
		Map<String, List<ShoppingItem>> groups = new LinkedHashMap<>();
		for (ShoppingItem item : items) {
			String key = groupName(context, item);
			List<ShoppingItem> list = groups.get(key);
			if (list == null) {
				list = new ArrayList<>();
				groups.put(key, list);
			}
			list.add(item);
		}
		for (List<ShoppingItem> list : groups.values()) {
			java.util.Collections.sort(list, (a, b) -> name(a).compareToIgnoreCase(name(b)));
		}

		PdfDocument document = new PdfDocument();
		Paint title = paint(18, true, 0xFF000000);
		Paint shop = paint(22, true, 0xFF000000);
		Paint text = paint(12, false, 0xFF000000);
		Paint bold = paint(12, true, 0xFF000000);
		Paint small = paint(10, false, 0xFF555555);
		Paint line = new Paint();
		line.setColor(0xFF999999);
		line.setStrokeWidth(0.8f);
		String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(timestamp));
		String note = context.getString(R.string.receipt_note);

		int pageNumber = 0;
		for (Map.Entry<String, List<ShoppingItem>> group : groups.entrySet()) {
			PdfDocument.Page page = null;
			android.graphics.Canvas canvas = null;
			float y = 0;
			List<ShoppingItem> list = group.getValue();
			int index = 0;
			boolean first = true;
			while (index < list.size() || first) {
				page = document.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, ++pageNumber).create());
				canvas = page.getCanvas();
				y = MARGIN + 18;
				canvas.drawText(context.getString(R.string.receipt_title), MARGIN, y, title);
				y += 28;
				canvas.drawText(group.getKey(), MARGIN, y, shop);
				y += 18;
				canvas.drawText(date, MARGIN, y, small);
				y += 14;
				canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, line);
				y += 20;
				first = false;

				float bottom = PAGE_HEIGHT - MARGIN - 60;
				while (index < list.size() && y + ROW_HEIGHT <= bottom) {
					drawRow(context, canvas, list.get(index++), y, text, small);
					y += ROW_HEIGHT;
				}
				if (index >= list.size()) {
					canvas.drawLine(MARGIN, y - 6, PAGE_WIDTH - MARGIN, y - 6, line);
					y += 14;
					Totals subtotal = totals(list);
					canvas.drawText(context.getString(R.string.receipt_subtotal), MARGIN, y, bold);
					drawRight(canvas, Money.format(subtotal.sum), PAGE_WIDTH - MARGIN, y, bold);
					if (subtotal.withoutPrice > 0) {
						y += 14;
						canvas.drawText(context.getString(R.string.receipt_missing_prices, subtotal.withoutPrice),
								MARGIN, y, small);
					}
					if (groups.size() == 1 && depositReturn > 0) {
						y += 18;
						canvas.drawText(context.getString(R.string.receipt_deposit_return), MARGIN, y, text);
						drawRight(canvas, "−" + Money.format(depositReturn), PAGE_WIDTH - MARGIN, y, text);
						y += 18;
						canvas.drawText(context.getString(R.string.receipt_total), MARGIN, y, bold);
						drawRight(canvas, Money.format(subtotal.sum - depositReturn), PAGE_WIDTH - MARGIN, y, bold);
					}
				}
				canvas.drawText(note, MARGIN, PAGE_HEIGHT - MARGIN, small);
				document.finishPage(page);
			}
		}

		if (groups.size() > 1) {
			PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, ++pageNumber).create());
			android.graphics.Canvas canvas = page.getCanvas();
			float y = MARGIN + 18;
			canvas.drawText(context.getString(R.string.receipt_title), MARGIN, y, title);
			y += 28;
			canvas.drawText(context.getString(R.string.receipt_overview), MARGIN, y, shop);
			y += 18;
			canvas.drawText(date, MARGIN, y, small);
			y += 14;
			canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, line);
			y += 22;
			for (Map.Entry<String, List<ShoppingItem>> group : groups.entrySet()) {
				canvas.drawText(group.getKey(), MARGIN, y, text);
				drawRight(canvas, Money.format(totals(group.getValue()).sum), PAGE_WIDTH - MARGIN, y, text);
				y += 20;
			}
			canvas.drawLine(MARGIN, y - 8, PAGE_WIDTH - MARGIN, y - 8, line);
			y += 10;
			Totals all = totals(items, depositReturn);
			if (depositReturn > 0) {
				canvas.drawText(context.getString(R.string.receipt_deposit_return), MARGIN, y, text);
				drawRight(canvas, "−" + Money.format(depositReturn), PAGE_WIDTH - MARGIN, y, text);
				y += 20;
			}
			canvas.drawText(context.getString(R.string.receipt_total), MARGIN, y, bold);
			drawRight(canvas, Money.format(all.sum), PAGE_WIDTH - MARGIN, y, bold);
			if (all.withoutPrice > 0) {
				y += 16;
				canvas.drawText(context.getString(R.string.receipt_missing_prices, all.withoutPrice), MARGIN, y, small);
			}
			canvas.drawText(note, MARGIN, PAGE_HEIGHT - MARGIN, small);
			document.finishPage(page);
		}

		try {
			document.writeTo(out);
		} finally {
			document.close();
		}
	}

	private static void drawRow(Context context, android.graphics.Canvas canvas, ShoppingItem item, float y,
								Paint text, Paint small) {
		float right = PAGE_WIDTH - MARGIN;
		Double total = lineTotal(item);
		String price = total == null ? "–" : Money.format(total + item.depositTotal());
		float priceWidth = text.measureText(price);
		float maxName = right - MARGIN - priceWidth - 16;
		canvas.drawText(fit(name(item), text, maxName), MARGIN, y, text);
		drawRight(canvas, price, right, y, text);

		Double scan = item.effectiveScanPrice();
		if (scan == null) {
			scan = item.scanPrice;
		}
		String detail = scan == null
				? item.quantity + " × " + context.getString(R.string.receipt_no_price)
				: item.quantity + " × " + Money.format(scan);
		if (item.deposit != null) {
			detail += " + " + context.getString(R.string.receipt_deposit, item.quantity, Money.format(item.deposit));
		}
		canvas.drawText(fit(detail + " · " + item.barcode, small, right - MARGIN), MARGIN, y + 13, small);
	}

	private static String name(ShoppingItem item) {
		return item.displayName() != null ? item.displayName() : item.barcode;
	}

	private static String fit(String value, Paint paint, float maxWidth) {
		if (paint.measureText(value) <= maxWidth) {
			return value;
		}
		int length = paint.breakText(value, true, maxWidth - paint.measureText("…"), null);
		return value.substring(0, Math.max(0, length)) + "…";
	}

	private static void drawRight(android.graphics.Canvas canvas, String value, float right, float y, Paint paint) {
		canvas.drawText(value, right - paint.measureText(value), y, paint);
	}

	private static Paint paint(float size, boolean bold, int color) {
		Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
		p.setTextSize(size);
		p.setColor(color);
		p.setTypeface(bold ? Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) : Typeface.SANS_SERIF);
		return p;
	}
}
