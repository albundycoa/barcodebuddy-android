package de.bulling.barcodebuddyscanner.wear;

import static androidx.wear.protolayout.ColorBuilders.argb;
import static androidx.wear.protolayout.DimensionBuilders.dp;
import static androidx.wear.protolayout.DimensionBuilders.expand;
import static androidx.wear.protolayout.DimensionBuilders.sp;
import static androidx.wear.protolayout.DimensionBuilders.wrap;

import android.graphics.Color;

import androidx.annotation.NonNull;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.LayoutElementBuilders.Box;
import androidx.wear.protolayout.LayoutElementBuilders.Column;
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement;
import androidx.wear.protolayout.LayoutElementBuilders.Row;
import androidx.wear.protolayout.LayoutElementBuilders.Spacer;
import androidx.wear.protolayout.LayoutElementBuilders.Text;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.tiles.TileService;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Kachel zum Wischen neben dem Zifferblatt: zwei Reiter (Gescannt mit Summe, Einkauf mit offenen Einträgen) im Design der
 * Handy-App. Tippen auf einen Eintrag der Einkaufsliste hakt ihn ab, ein Fehlscan wird erst angetippt und dann mit
 * „Entfernen“ gelöscht. Die Kachel zeigt ein paar Zeilen pro Seite; „▼“ blättert weiter.
 *
 * <p>Eine Kachel bekommt Klicks als neue Anfrage mit der Nummer des zuletzt angetippten Elements. Jede Anzeige trägt ein
 * eigenes Kennzeichen in diesen Nummern, damit dieselbe Berührung bei späteren Aktualisierungen nicht noch einmal
 * wirkt (sonst würde ein Haken beim nächsten Stand vom Handy wieder entfernt).
 */
public class BbsTileService extends TileService {

	private static final String RES_VERSION = "1";
	private static final int ROWS = 3;

	private static final int BG        = Color.BLACK;
	private static final int CARD      = Color.parseColor("#14201F");
	private static final int CARD_LINE = Color.parseColor("#2C3C3A");
	private static final int TAB_ON    = Color.parseColor("#1E5049");
	private static final int ACCENT    = Color.parseColor("#80CBC4");
	private static final int TEXT      = Color.parseColor("#E6F2F0");
	private static final int TEXT_DIM  = Color.parseColor("#93A6A3");
	private static final int DANGER    = Color.parseColor("#E57373");
	private static final int DANGER_BG = Color.parseColor("#3A1F22");

	private static int page;
	/** Seitenzahl der zuletzt ausgelieferten Anzeige, damit ▲ und ▼ im Kreis blättern */
	private static int pageCount = 1;
	private static long selectedScan = -1;
	/** Kennzeichen der zuletzt ausgelieferten Anzeige; ein Klick darauf gilt genau einmal. */
	private static long token;

	/** Sagt der Kachel, dass sich Scanliste oder Einkaufsliste geändert haben. */
	static void refresh(android.content.Context context) {
		try {
			TileService.getUpdater(context.getApplicationContext()).requestUpdate(BbsTileService.class);
		} catch (RuntimeException e) {
			// Keine Kachel eingerichtet: nichts zu aktualisieren
		}
	}

	@NonNull
	@Override
	protected ListenableFuture<TileBuilders.Tile> onTileRequest(@NonNull RequestBuilders.TileRequest request) {
		handleClick(request.getCurrentState().getLastClickableId());
		LayoutElement root = layout(State.load(this), request.getDeviceConfiguration().getScreenHeightDp());
		TileBuilders.Tile tile = new TileBuilders.Tile.Builder()
				.setResourcesVersion(RES_VERSION)
				.setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(root))
				.build();
		return Futures.immediateFuture(tile);
	}

	@NonNull
	@Override
	protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(@NonNull RequestBuilders.ResourcesRequest request) {
		return Futures.immediateFuture(new ResourceBuilders.Resources.Builder().setVersion(RES_VERSION).build());
	}

	/** Klick-Nummern sehen aus wie "shop:12@1730000000000" (Aktion, Zeile, Kennzeichen der Anzeige). */
	private void handleClick(String clickId) {
		if (clickId == null || clickId.isEmpty()) {
			return;
		}
		int at = clickId.lastIndexOf('@');
		if (at < 0 || !clickId.substring(at + 1).equals(String.valueOf(token))) {
			return;
		}
		token = 0;
		String[] parts = clickId.substring(0, at).split(":");
		String action = parts[0];
		long id = parts.length > 1 ? Long.parseLong(parts[1]) : 0;
		switch (action) {
			case "tab":
				Settings.setFaceTab(this, (int) id);
				page = 0;
				selectedScan = -1;
				break;
			case "page":
				page = Math.floorMod(page + (int) id, pageCount);
				selectedScan = -1;
				break;
			case "shop":
				ListEdits.toggleShopping(this, id);
				break;
			case "scan":
				selectedScan = selectedScan == id ? -1 : id;
				break;
			case "del":
				selectedScan = -1;
				ListEdits.removeScan(this, id);
				break;
			default:
				break;
		}
	}

	private LayoutElement layout(JsonObject state, float screenHeightDp) {
		token = System.currentTimeMillis();
		int tab = FaceModel.tab(this);
		List<FaceModel.Row> rows = FaceModel.rows(state, tab);
		int pages = Math.max(1, (rows.size() + ROWS - 1) / ROWS);
		pageCount = pages;
		if (page >= pages) {
			page = 0;
		}

		Column.Builder column = new Column.Builder()
				.setWidth(expand())
				.setHeight(expand())
				.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
				.addContent(spacer(0, 30))
				.addContent(tabs(state, tab))
				.addContent(spacer(0, 5));

		if (rows.isEmpty()) {
			column.addContent(new Box.Builder()
					.setWidth(dp(150))
					.setHeight(dp(80))
					.addContent(text(FaceModel.emptyText(state, tab), 12, TEXT_DIM, false, 3))
					.build());
		} else {
			int from = page * ROWS;
			for (int i = 0; i < ROWS && from + i < rows.size(); i++) {
				column.addContent(row(rows.get(from + i), tab, i)).addContent(spacer(0, 3));
			}
			if (pages > 1) {
				column.addContent(new Row.Builder()
						.setWidth(dp(140))
						.setHeight(dp(26))
						.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
						.addContent(pageButton("▲", -1))
						.addContent(new Box.Builder()
								.setWidth(dp(60))
								.setHeight(dp(26))
								.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
								.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
								.addContent(text((page + 1) + " / " + pages, 12, TEXT_DIM, false, 1))
								.build())
						.addContent(pageButton("▼", 1))
						.build());
			}
		}
		return new Box.Builder()
				.setWidth(expand())
				.setHeight(expand())
				.setModifiers(new ModifiersBuilders.Modifiers.Builder()
						.setBackground(new ModifiersBuilders.Background.Builder().setColor(argb(BG)).build())
						.build())
				.addContent(column.build())
				.build();
	}

	/** ▲ blättert zurück, ▼ weiter; nach der letzten Seite geht es wieder von vorn los. */
	private LayoutElement pageButton(String symbol, int delta) {
		return new Box.Builder()
				.setWidth(dp(40))
				.setHeight(dp(26))
				.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
				.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
				.setModifiers(clickable("page:" + delta))
				.addContent(text(symbol, 14, ACCENT, true, 1))
				.build();
	}

	private LayoutElement tabs(JsonObject state, int tab) {
		return new Row.Builder()
				.setWidth(dp(160))
				.setHeight(dp(36))
				.addContent(tabButton(state, FaceModel.TAB_SCANS, "Gescannt", tab == FaceModel.TAB_SCANS))
				.addContent(spacer(4, 0))
				.addContent(tabButton(state, FaceModel.TAB_SHOP, "Einkauf", tab == FaceModel.TAB_SHOP))
				.build();
	}

	private LayoutElement tabButton(JsonObject state, int tab, String title, boolean on) {
		return new Box.Builder()
				.setWidth(dp(78))
				.setHeight(dp(36))
				.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
				.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
				.setModifiers(boxModifiers(on ? TAB_ON : CARD, on ? ACCENT : CARD_LINE, 14, clickableAction("tab:" + tab)))
				.addContent(new Column.Builder()
						.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
						.addContent(text(title, 12, on ? ACCENT : TEXT_DIM, true, 1))
						.addContent(text(FaceModel.summary(state, tab), 11, on ? TEXT : TEXT_DIM, false, 1))
						.build())
				.build();
	}

	private LayoutElement row(FaceModel.Row row, int tab, int index) {
		boolean selected = tab == FaceModel.TAB_SCANS && row.id == selectedScan;
		// Zum unteren Rand hin wird der Kreis schmaler: die letzte Zeile bekommt mehr Rand
		float margin = index >= ROWS - 1 ? 24 : 14;
		float width = 226 - 2 * margin;

		Row.Builder content = new Row.Builder()
				.setWidth(expand())
				.setHeight(wrap())
				.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER);
		if (tab == FaceModel.TAB_SHOP) {
			content.addContent(checkbox(row)).addContent(spacer(7, 0));
		}
		Column.Builder label = new Column.Builder()
				.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_START)
				.addContent(text(row.title, 13, row.done ? TEXT_DIM : TEXT, true, 1))
				.addContent(text(row.sub, 11, TEXT_DIM, false, 1));
		content.addContent(label.build());
		if (selected) {
			content.addContent(spacer(6, 0)).addContent(new Box.Builder()
					.setWidth(dp(66))
					.setHeight(dp(26))
					.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
					.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
					.setModifiers(boxModifiers(DANGER, DANGER, 10, clickableAction("del:" + row.id)))
					.addContent(text("Entfernen", 11, Color.BLACK, true, 1))
					.build());
		}

		String id = (tab == FaceModel.TAB_SHOP ? "shop:" : "scan:") + row.id;
		return new Box.Builder()
				.setWidth(dp(width))
				.setHeight(dp(38))
				.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
				.setModifiers(boxModifiers(selected ? DANGER_BG : CARD, selected ? DANGER : CARD_LINE, 12, clickableAction(id), 8))
				.addContent(content.build())
				.build();
	}

	private LayoutElement checkbox(FaceModel.Row row) {
		Box.Builder box = new Box.Builder()
				.setWidth(dp(18))
				.setHeight(dp(18))
				.setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
				.setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
				.setModifiers(boxModifiers(row.done ? ACCENT : CARD, row.done ? ACCENT : (row.covered ? TEXT_DIM : TEXT), 4, null, 0));
		if (row.done) {
			box.addContent(text("✓", 12, Color.parseColor("#0B2B27"), true, 1));
		}
		return box.build();
	}

	private Text text(String value, float size, int color, boolean bold, int maxLines) {
		return new Text.Builder()
				.setText(value)
				.setMaxLines(maxLines)
				.setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
				.setFontStyle(new LayoutElementBuilders.FontStyle.Builder()
						.setSize(sp(size))
						.setColor(argb(color))
						.setWeight(bold ? LayoutElementBuilders.FONT_WEIGHT_BOLD : LayoutElementBuilders.FONT_WEIGHT_NORMAL)
						.build())
				.build();
	}

	private static Spacer spacer(float widthDp, float heightDp) {
		return new Spacer.Builder().setWidth(dp(widthDp)).setHeight(dp(heightDp)).build();
	}

	private ModifiersBuilders.Modifiers clickable(String id) {
		return new ModifiersBuilders.Modifiers.Builder().setClickable(clickableAction(id)).build();
	}

	private ModifiersBuilders.Clickable clickableAction(String id) {
		return new ModifiersBuilders.Clickable.Builder()
				.setId(id + "@" + token)
				.setOnClick(new ActionBuilders.LoadAction.Builder().build())
				.build();
	}

	private static ModifiersBuilders.Modifiers boxModifiers(int fill, int line, float radius, ModifiersBuilders.Clickable click) {
		return boxModifiers(fill, line, radius, click, 0);
	}

	private static ModifiersBuilders.Modifiers boxModifiers(int fill, int line, float radius, ModifiersBuilders.Clickable click, float padding) {
		ModifiersBuilders.Modifiers.Builder m = new ModifiersBuilders.Modifiers.Builder()
				.setBackground(new ModifiersBuilders.Background.Builder()
						.setColor(argb(fill))
						.setCorner(new ModifiersBuilders.Corner.Builder().setRadius(dp(radius)).build())
						.build())
				.setBorder(new ModifiersBuilders.Border.Builder().setWidth(dp(1.5f)).setColor(argb(line)).build());
		if (padding > 0) {
			m.setPadding(new ModifiersBuilders.Padding.Builder().setStart(dp(padding)).setEnd(dp(padding)).build());
		}
		if (click != null) {
			m.setClickable(click);
		}
		return m.build();
	}
}
