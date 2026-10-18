package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;

import androidx.annotation.StringRes;

/**
 * Texte aus den Ressourcen für Klassen ohne eigenen Context (Netzwerk- und Buchungslogik). Der Application-Context
 * wird beim Start der App gesetzt.
 */
public final class Texts {

	private static Context appContext;

	private Texts() {
	}

	public static void init(Context context) {
		appContext = context.getApplicationContext();
	}

	public static String get(@StringRes int id, Object... args) {
		return appContext.getString(id, args);
	}
}
