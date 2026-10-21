package de.bulling.barcodebuddyscanner.Helper;

import android.text.InputType;
import android.text.method.DigitsKeyListener;
import android.widget.EditText;

/**
 * Betragsfelder: Zifferntastatur, aber Komma und Punkt sind beide erlaubt. Mit "numberDecimal" allein lassen manche
 * Tastaturen nur den Punkt zu, wer "2,20" tippt, bekäme das Komma nicht. Gelesen wird mit {@link Money#parse}.
 */
public final class AmountInput {

	private AmountInput() {
	}

	/** Erlaubt Ziffern, Komma und Punkt; muss nach {@code setInputType} aufgerufen werden. */
	public static void apply(EditText field) {
		field.setKeyListener(DigitsKeyListener.getInstance("0123456789,."));
		// setKeyListener setzt die Eingabeart zurück: Tastatur wieder als Zahlenfeld mit Dezimalzeichen anfordern
		field.setRawInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
	}
}
