package de.bulling.barcodebuddyscanner.Helper;

import android.app.Activity;

/**
 * Piepen und Vibrieren bei einem Scan, gemäß den Einstellungen.
 */
public class ScanFeedback {

	private final BeepManager     beepManager;
	private final SharedPrefHelper prefHelper;

	public ScanFeedback(Activity activity) {
		prefHelper  = new SharedPrefHelper(activity);
		beepManager = new BeepManager(activity);
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
