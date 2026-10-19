package de.bulling.barcodebuddyscanner;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.DecoratedBarcodeView;

import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.ScanFeedback;
import de.bulling.barcodebuddyscanner.Helper.ScanNotifier;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;


/**
 * Kamera-Scanner: Jeder erkannte Barcode landet in der Einkaufsliste, es wird
 * noch nichts an Barcode Buddy gesendet.
 */
public class ContinuousCaptureActivity extends Activity {
	private DecoratedBarcodeView barcodeView;
	private long                 lastScanTime = 0;
	private String               lastBarcode  = null;

	private ShoppingRepository repo;
	private ScanFeedback       feedback;


	@SuppressLint("SourceLockedOrientationActivity")
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		setContentView(R.layout.continuous_scan);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

		repo = ShoppingRepository.get(this);
		feedback = new ScanFeedback(this);

		barcodeView = findViewById(R.id.barcode_scanner);
		barcodeView.setStatusText("");
		findViewById(R.id.button_done).setOnClickListener(v -> finish());

		// The button bar is anchored to the bottom of the screen. Since
		// edge-to-edge is enforced when targeting SDK 35+, it would
		// otherwise render underneath the navigation/gesture bar, making
		// the button hard or impossible to tap. The camera preview
		// itself is left alone so it still fills the whole screen.
		View buttonsLayout = findViewById(R.id.buttonsLayout);
		EdgeToEdgeHelper.applyInsetsAsPadding(buttonsLayout, true, false, true, true);
		barcodeView.initializeFromIntent(getIntent());
		barcodeView.decodeContinuous(callback);

	}

	private BarcodeCallback callback = new BarcodeCallback() {
		@Override
		public void barcodeResult(BarcodeResult result) {
			final String text = result.getText();
			long millisSinceLastScan = (System.currentTimeMillis() - lastScanTime);
			if (text == null || text.isEmpty() || (text.equals(lastBarcode) && millisSinceLastScan < 1500)) {
				// Prevent duplicate scans
				return;
			}

			lastScanTime = System.currentTimeMillis();
			lastBarcode  = text;
			feedback.beep();
			repo.addScan(text, item -> {
				barcodeView.setStatusText(getString(R.string.scan_status, text, item.quantity));
				ScanNotifier.show(ContinuousCaptureActivity.this);
			});
		}

		@Override
		public void possibleResultPoints(List<ResultPoint> resultPoints) {
		}
	};

	@Override
	protected void onResume() {
		super.onResume();
		startScanningIfCameraPermitted();
	}

	/**
	 * The camera permission is required for scanning to work at all, so unlike
	 * ACCESS_LOCAL_NETWORK this one blocks: we only resume the barcode view once
	 * it's actually granted.
	 */
	private void startScanningIfCameraPermitted() {
		if (PermissionHelper.hasCameraPermission(this)) {
			barcodeView.resume();
		} else {
			PermissionHelper.requestCameraPermission(this);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == PermissionHelper.REQUEST_CODE_CAMERA) {
			if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				barcodeView.resume();
			} else {
				Toast.makeText(this, R.string.error_camera_permission_denied, Toast.LENGTH_LONG).show();
				finish();
			}
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		barcodeView.pause();
	}

}
