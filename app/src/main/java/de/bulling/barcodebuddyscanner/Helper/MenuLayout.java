package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.PopupMenu;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.bulling.barcodebuddyscanner.R;

/**
 * Reihenfolge und Sichtbarkeit der Einträge im Drei-Punkte-Menü. Die Einträge selbst stehen in res/menu/main.xml; hier
 * wird nur gespeichert, wie der Nutzer sie angeordnet und welche er ausgeblendet hat. Neue Einträge einer späteren
 * Version erscheinen automatisch am Ende, entfernte werden ignoriert.
 */
public final class MenuLayout {

	private static final String PREF_ORDER  = "menu_order";
	private static final String PREF_HIDDEN = "menu_hidden";

	/** Diese Einträge lassen sich nicht ausblenden: Über die Einstellungen kommt man zur Anpassung zurück. */
	private static final Set<String> MANDATORY = new HashSet<>(Arrays.asList("action_settings"));

	/** Ein Menüeintrag: Schlüssel (Name der Ressource), Anzeigetext und Kennung. */
	public static final class Entry {
		public final String       key;
		public final CharSequence title;
		public final int          id;

		Entry(String key, CharSequence title, int id) {
			this.key   = key;
			this.title = title;
			this.id    = id;
		}
	}

	private MenuLayout() {
	}

	public static boolean isMandatory(String key) {
		return MANDATORY.contains(key);
	}

	// ---------------------------------------------------------------- reine Logik (ohne Android, getestet)

	/** "a,b,c" -> [a, b, c]; leere Eingabe ergibt eine leere Liste. */
	public static List<String> split(String text) {
		List<String> list = new ArrayList<>();
		if (text == null) {
			return list;
		}
		for (String part : text.split(",")) {
			String key = part.trim();
			if (!key.isEmpty() && !list.contains(key)) {
				list.add(key);
			}
		}
		return list;
	}

	public static String join(List<String> keys) {
		StringBuilder text = new StringBuilder();
		for (String key : keys) {
			if (text.length() > 0) {
				text.append(',');
			}
			text.append(key);
		}
		return text.toString();
	}

	/**
	 * Die gespeicherte Reihenfolge, aufgefüllt um neue Einträge (am Ende, in der Standardreihenfolge) und bereinigt um
	 * Einträge, die es nicht mehr gibt.
	 */
	public static List<String> mergeOrder(List<String> defaults, List<String> saved) {
		List<String> result = new ArrayList<>();
		for (String key : saved) {
			if (defaults.contains(key) && !result.contains(key)) {
				result.add(key);
			}
		}
		for (String key : defaults) {
			if (!result.contains(key)) {
				result.add(key);
			}
		}
		return result;
	}

	// ---------------------------------------------------------------- Menü

	/** Alle Einträge in der Standardreihenfolge der XML-Datei. */
	public static List<Entry> defaultEntries(Context context) {
		PopupMenu popup = new PopupMenu(context, new View(context));
		popup.getMenuInflater().inflate(R.menu.main, popup.getMenu());
		return entriesOf(context, popup.getMenu());
	}

	private static List<Entry> entriesOf(Context context, Menu menu) {
		Resources res = context.getResources();
		List<Entry> entries = new ArrayList<>();
		for (int i = 0; i < menu.size(); i++) {
			MenuItem item = menu.getItem(i);
			entries.add(new Entry(res.getResourceEntryName(item.getItemId()), item.getTitle(), item.getItemId()));
		}
		return entries;
	}

	/** Alle Einträge in der Reihenfolge des Nutzers, auch die ausgeblendeten. */
	public static List<Entry> orderedEntries(Context context) {
		List<Entry> defaults = defaultEntries(context);
		Map<String, Entry> byKey = new LinkedHashMap<>();
		List<String> keys = new ArrayList<>();
		for (Entry e : defaults) {
			byKey.put(e.key, e);
			keys.add(e.key);
		}
		List<Entry> result = new ArrayList<>();
		for (String key : mergeOrder(keys, split(prefs(context).getString(PREF_ORDER, "")))) {
			result.add(byKey.get(key));
		}
		return result;
	}

	public static Set<String> hidden(Context context) {
		Set<String> hidden = new HashSet<>(split(prefs(context).getString(PREF_HIDDEN, "")));
		hidden.removeAll(MANDATORY);
		return hidden;
	}

	public static void save(Context context, List<String> order, Set<String> hidden) {
		prefs(context).edit()
				.putString(PREF_ORDER, join(order))
				.putString(PREF_HIDDEN, join(new ArrayList<>(hidden)))
				.apply();
	}

	public static void reset(Context context) {
		prefs(context).edit().remove(PREF_ORDER).remove(PREF_HIDDEN).apply();
	}

	/** Ordnet das gerade aus res/menu/main.xml aufgebaute Menü nach den Wünschen des Nutzers. */
	public static void apply(Context context, Menu menu) {
		List<Entry> inflated = entriesOf(context, menu);
		Map<String, Entry> byKey = new HashMap<>();
		List<String> defaults = new ArrayList<>();
		for (Entry e : inflated) {
			byKey.put(e.key, e);
			defaults.add(e.key);
		}
		List<String> order = mergeOrder(defaults, split(prefs(context).getString(PREF_ORDER, "")));
		Set<String> hidden = hidden(context);
		menu.clear();
		int position = 0;
		for (String key : order) {
			if (hidden.contains(key)) {
				continue;
			}
			Entry e = byKey.get(key);
			menu.add(Menu.NONE, e.id, position++, e.title).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
		}
	}

	private static SharedPreferences prefs(Context context) {
		return PreferenceManager.getDefaultSharedPreferences(context);
	}
}
