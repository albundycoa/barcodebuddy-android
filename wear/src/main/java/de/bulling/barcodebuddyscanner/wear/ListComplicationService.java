package de.bulling.barcodebuddyscanner.wear;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationText;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Grundlage der Komplikationen: Zahl und Summe der Scanliste oder der Einkaufsliste für ein Zifferblatt. Eine Komplikation
 * kann nur Text zeigen, die Liste selbst nicht; ein Tipp darauf öffnet die Liste auf der Uhr. Die Daten kommen aus dem
 * zuletzt vom Handy gespeicherten Stand ({@link State}), neue Daten stoßen {@link Complications#refresh} an.
 */
abstract class ListComplicationService extends ComplicationDataSourceService {

	/** Was die Komplikation zeigt: kurz (z. B. "3"), lang (z. B. "3 Artikel · 12,50 €"), Titel und die Seite beim Tippen. */
	static final class Content {
		final String shortText;
		final String shortTitle;
		final String longText;
		final String longTitle;

		Content(String shortText, String shortTitle, String longText, String longTitle) {
			this.shortText  = shortText;
			this.shortTitle = shortTitle;
			this.longText   = longText;
			this.longTitle  = longTitle;
		}
	}

	/** Inhalt aus dem gespeicherten Stand; state kann null sein (noch keine Daten vom Handy). */
	abstract Content content(@Nullable JsonObject state);

	/** Beispiel für die Auswahl am Zifferblatt. */
	abstract Content preview();

	/** Die Seite, die ein Tipp auf die Komplikation öffnet. */
	abstract Class<?> target();

	@Override
	public void onComplicationRequest(@NonNull ComplicationRequest request,
									  @NonNull ComplicationRequestListener listener) {
		try {
			listener.onComplicationData(build(request.getComplicationType(), content(State.load(this))));
		} catch (android.os.RemoteException e) {
			// Das Zifferblatt ist weg; nichts zu tun
		}
	}

	@Nullable
	@Override
	public ComplicationData getPreviewData(@NonNull ComplicationType type) {
		return build(type, preview());
	}

	@Nullable
	private ComplicationData build(ComplicationType type, Content content) {
		PendingIntent tap = PendingIntent.getActivity(this, 0, new Intent(this, target()),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		if (type == ComplicationType.SHORT_TEXT) {
			return new ShortTextComplicationData.Builder(text(content.shortText), text(content.longText))
					.setTitle(text(content.shortTitle))
					.setTapAction(tap)
					.build();
		}
		if (type == ComplicationType.LONG_TEXT) {
			return new LongTextComplicationData.Builder(text(content.longText), text(content.longText))
					.setTitle(text(content.longTitle))
					.setTapAction(tap)
					.build();
		}
		return null;
	}

	private static ComplicationText text(String value) {
		return new PlainComplicationText.Builder(value).build();
	}

	/** Anzahl der Einträge eines Feldes, 0 wenn es fehlt. */
	static int count(@Nullable JsonObject state, String field) {
		return State.array(state, field).size();
	}

	/** Anzahl der nicht abgehakten Einträge der Einkaufsliste. */
	static int openCount(@Nullable JsonObject state) {
		JsonArray shop = State.array(state, "shop");
		int open = 0;
		for (int i = 0; i < shop.size(); i++) {
			if (!shop.get(i).getAsJsonObject().get("d").getAsBoolean()) {
				open++;
			}
		}
		return open;
	}

	static String field(@Nullable JsonObject state, String name) {
		return state != null && state.has(name) && !state.get(name).isJsonNull() ? state.get(name).getAsString() : "";
	}
}
