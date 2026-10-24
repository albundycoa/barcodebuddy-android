package de.bulling.barcodebuddyscanner;

import android.app.Application;

import de.bulling.barcodebuddyscanner.Helper.Texts;

public class BuddyApp extends Application {

	@Override
	public void onCreate() {
		super.onCreate();
		Texts.init(this);
		// Das gewählte Design (hell, dunkel, System) gilt schon beim Start
		de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper.applyTheme(this);
	}
}
