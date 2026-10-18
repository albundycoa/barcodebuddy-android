package de.bulling.barcodebuddyscanner.Helper;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

import de.bulling.barcodebuddyscanner.R;

/**
 * Einstellungs-Codes des Bluetooth-Scanners (Eyoyo EY-015P), die die App selbst als QR-Code zeichnet. Man scannt sie
 * mit dem Scanner vom Display ab, statt sie aus dem Handbuch abzuscannen. Der Inhalt ist der Text, der auch in den
 * QR-Codes des Handbuchs steht (Kapitel 9, "Bluetooth-SPP-Kopplungsmodus").
 */
public final class ScannerCodes {

	/** Scanner in den SPP-Suchmodus versetzen (die blaue Anzeige blinkt) */
	public static final String SPP_PAIRING = "^&013&^";

	private ScannerCodes() {
	}

	/** Der QR-Code in Modulgröße 1 mit weißem Rand von vier Modulen, den der Scanner zum Erkennen braucht. */
	public static BitMatrix matrix(String content) throws WriterException {
		Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
		hints.put(EncodeHintType.MARGIN, 4);
		hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
		hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
		return new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);
	}

	/**
	 * Das Bild des QR-Codes: schwarz auf weiß, jedes Modul ein Vielfaches ganzer Pixel breit, damit die Kanten scharf
	 * bleiben.
	 */
	public static Bitmap bitmap(String content, int targetSizePx) throws WriterException {
		BitMatrix matrix = matrix(content);
		int scale = Math.max(1, targetSizePx / matrix.getWidth());
		int size = matrix.getWidth() * scale;
		int[] pixels = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				pixels[y * size + x] = matrix.get(x / scale, y / scale) ? Color.BLACK : Color.WHITE;
			}
		}
		return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
	}

	/** Zeigt den Code zum Abscannen mit dem Scanner, bei voller Helligkeit, damit er sicher erkannt wird. */
	public static void show(Activity activity, int titleRes, int messageRes, String content) {
		Bitmap code;
		try {
			code = bitmap(content, (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.8f));
		} catch (WriterException e) {
			return;
		}
		ImageView image = new ImageView(activity);
		image.setImageBitmap(code);
		image.setAdjustViewBounds(true);
		image.setBackgroundColor(Color.WHITE);
		FrameLayout box = new FrameLayout(activity);
		int pad = (int) (16 * activity.getResources().getDisplayMetrics().density);
		box.setPadding(pad, pad / 2, pad, 0);
		box.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
		androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
				.setTitle(titleRes)
				.setMessage(messageRes)
				.setView(box)
				.setPositiveButton(R.string.spp_pairing_close, null)
				.create();
		dialog.show();
		if (dialog.getWindow() != null) {
			WindowManager.LayoutParams params = dialog.getWindow().getAttributes();
			params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
			dialog.getWindow().setAttributes(params);
		}
	}

	public static void showSppPairing(Activity activity) {
		show(activity, R.string.spp_pairing_title, R.string.spp_pairing_steps, SPP_PAIRING);
	}
}
