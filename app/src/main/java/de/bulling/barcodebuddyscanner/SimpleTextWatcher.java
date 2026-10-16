package de.bulling.barcodebuddyscanner;

import android.text.Editable;
import android.text.TextWatcher;

/**
 * TextWatcher, bei dem man nur auf den neuen Text reagiert.
 */
public abstract class SimpleTextWatcher implements TextWatcher {

	public abstract void onChanged(String text);

	@Override
	public void beforeTextChanged(CharSequence s, int start, int count, int after) {
	}

	@Override
	public void onTextChanged(CharSequence s, int start, int before, int count) {
	}

	@Override
	public void afterTextChanged(Editable s) {
		onChanged(s.toString());
	}
}
