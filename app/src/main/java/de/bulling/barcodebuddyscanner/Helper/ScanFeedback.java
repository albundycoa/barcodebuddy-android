package de.bulling.barcodebuddyscanner.Helper;

import android.content.Context;

/**
 * Piepen und Vibrieren bei einem Scan, gemäß den Einstellungen.
 */
public class ScanFeedback {

	private final BeepManager     beepManager;
	private final SharedPrefHelper prefHelper;

	public ScanFeedback(Context context) {
		prefHelper  = new SharedPrefHelper(context);
		beepManager = new BeepManager(context);
		reloadPrefs();
	}

	/** Übernimmt geänderte Einstellungen (z. B. nach dem Schließen der Einstellungen). */
	public void reloadPrefs() {
		beepManager.setBeepEnabled(prefHelper.isSoundEnabled());
		beepManager.setVibrateEnabled(prefHelper.isVibrationEnabled());
		beepManager.setBeepVolume(prefHelper.getBeepVolume());
	}

	public void beep() {
		beepManager.playBeepSoundAndVibrate();
	}
}
