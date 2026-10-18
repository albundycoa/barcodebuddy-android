package de.bulling.barcodebuddyscanner;

import android.app.Application;

import de.bulling.barcodebuddyscanner.Helper.Texts;

public class BuddyApp extends Application {

	@Override
	public void onCreate() {
		super.onCreate();
		Texts.init(this);
	}
}
