package de.bulling.barcodebuddyscanner.Helper;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

import de.bulling.barcodebuddyscanner.R;
import de.bulling.barcodebuddyscanner.ScannerService;

/**
 * Nimmt Barcodes für eine Seite entgegen, egal woher sie kommen: von der Handy-Kamera (wenn die Scan-Methode sie
 * vorsieht), von einem Bluetooth-Scanner im Tastaturmodus oder vom Scanner-Dienst (SPP). Die Activity ruft
 * {@link #onResume()}, {@link #onPause()}, {@link #onKey} und {@link #onPermissionResult} auf.
 */
public final class ScanInput {

	public interface Listener {
		void onBarcode(String code);
	}

	/** Solange ein Barcode ohne Pause im Bild bleibt, zählt er nur einmal */
	private static final long CAMERA_GAP_MS = 1000;

	private final Activity           activity;
	private final DecoratedBarcodeView cameraView;
	private final Listener           listener;

	private boolean cameraEnabled;
	private String  lastCameraCode;
	private long    lastCameraSeen;
	private String  keyboardBuffer = "";

	/**
	 * @param cameraView die Kameraansicht der Seite; sie wird ausgeblendet, wenn die Scan-Methode keine Kamera vorsieht
	 */
	public ScanInput(Activity activity, DecoratedBarcodeView cameraView, Listener listener) {
		this.activity   = activity;
		this.cameraView = cameraView;
		this.listener   = listener;
		cameraEnabled = SharedPrefHelper.isCameraScannerEnabled(activity);
		cameraView.setStatusText("");
		cameraView.initializeFromIntent(activity.getIntent());
		cameraView.decodeContinuous(cameraCallback);
		applyCameraVisibility();
	}

	public void onResume() {
		// Auch der Bluetooth-Scanner-Dienst liefert seine Barcodes hierher, solange die Seite offen ist
		InventorySession.setScanSink(listener::onBarcode);
		if (!cameraEnabled) {
			return;
		}
		if (PermissionHelper.hasCameraPermission(activity)) {
			cameraView.resume();
		} else {
			PermissionHelper.requestCameraPermission(activity);
		}
	}

	public void onPause() {
		InventorySession.setScanSink(null);
		cameraView.pause();
	}

	/** Zeigt dem Nutzer einen kurzen Hinweis unter der Kamera (z. B. den zuletzt gescannten Artikel). */
	public void setStatusText(String text) {
		cameraView.setStatusText(text);
	}

	public void onPermissionResult(int requestCode, int[] grantResults) {
		if (requestCode != PermissionHelper.REQUEST_CODE_CAMERA) {
			return;
		}
		if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
			cameraView.resume();
		} else {
			// Mit einem Handscanner geht es auch ohne Kamera
			Toast.makeText(activity, R.string.error_camera_permission_denied, Toast.LENGTH_LONG).show();
			cameraEnabled = false;
			applyCameraVisibility();
		}
	}

	/**
	 * Handscanner im Tastaturmodus: die Ziffern kommen als Tastendrücke, Enter beendet den Barcode.
	 *
	 * @return true, wenn die Taste zum Scanner gehört und verbraucht wurde
	 */
	public boolean onKey(KeyEvent event) {
		if (!SharedPrefHelper.isBtScannerEnabled(activity) || !isScannerKey(event.getKeyCode())) {
			return false;
		}
		if (event.getAction() == KeyEvent.ACTION_UP) {
			if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER) {
				String code = keyboardBuffer.trim();
				keyboardBuffer = "";
				if (!code.isEmpty() && ScannerService.parseBattery(code) == null) {
					listener.onBarcode(code);
				}
			} else {
				keyboardBuffer += (char) event.getUnicodeChar();
			}
		}
		return true;
	}

	private void applyCameraVisibility() {
		cameraView.setVisibility(cameraEnabled ? View.VISIBLE : View.GONE);
	}

	private final BarcodeCallback cameraCallback = new BarcodeCallback() {
		@Override
		public void barcodeResult(BarcodeResult result) {
			final String text = result.getText();
			if (text == null || text.isEmpty()) {
				return;
			}
			long now = SystemClock.elapsedRealtime();
			boolean stillInView = text.equals(lastCameraCode) && now - lastCameraSeen < CAMERA_GAP_MS;
			lastCameraCode = text;
			lastCameraSeen = now;
			if (!stillInView) {
				listener.onBarcode(text);
			}
		}

		@Override
		public void possibleResultPoints(List<ResultPoint> resultPoints) {
		}
	};

	private static boolean isScannerKey(int keycode) {
		return (keycode >= KeyEvent.KEYCODE_0 && keycode <= KeyEvent.KEYCODE_9)
				|| (keycode >= KeyEvent.KEYCODE_NUMPAD_0 && keycode <= KeyEvent.KEYCODE_NUMPAD_9)
				|| (keycode >= KeyEvent.KEYCODE_A && keycode <= KeyEvent.KEYCODE_Z)
				|| keycode == KeyEvent.KEYCODE_PERIOD
				|| keycode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT
				|| keycode == KeyEvent.KEYCODE_NUMPAD_DOT
				|| keycode == KeyEvent.KEYCODE_NUMPAD_ENTER
				|| keycode == KeyEvent.KEYCODE_ENTER
				|| keycode == KeyEvent.KEYCODE_MINUS
				|| keycode == KeyEvent.KEYCODE_SEMICOLON;
	}
}
