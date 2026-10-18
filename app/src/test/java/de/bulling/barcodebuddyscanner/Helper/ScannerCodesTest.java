package de.bulling.barcodebuddyscanner.Helper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import org.junit.Test;

/** Der gezeichnete Code muss beim Abscannen genau den Text liefern, den der Scanner erwartet. */
public class ScannerCodesTest {

	private static String decode(BitMatrix matrix, int scale) throws Exception {
		int size = matrix.getWidth() * scale;
		int[] pixels = new int[size * size];
		for (int y = 0; y < size; y++) {
			for (int x = 0; x < size; x++) {
				pixels[y * size + x] = matrix.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF;
			}
		}
		LuminanceSource source = new RGBLuminanceSource(size, size, pixels);
		Result result = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source)));
		return result.getText();
	}

	@Test
	public void sppPairingCodeReadsBackExactly() throws Exception {
		assertEquals("^&013&^", ScannerCodes.SPP_PAIRING);
		assertEquals("^&013&^", decode(ScannerCodes.matrix(ScannerCodes.SPP_PAIRING), 8));
	}

	@Test
	public void codeHasAQuietZone() throws Exception {
		BitMatrix matrix = ScannerCodes.matrix(ScannerCodes.SPP_PAIRING);
		// Die äußeren vier Module sind weiß, sonst erkennt der Scanner den Code nicht
		for (int i = 0; i < matrix.getWidth(); i++) {
			for (int edge = 0; edge < 4; edge++) {
				assertFalse(matrix.get(i, edge));
				assertFalse(matrix.get(edge, i));
				assertFalse(matrix.get(i, matrix.getHeight() - 1 - edge));
				assertFalse(matrix.get(matrix.getWidth() - 1 - edge, i));
			}
		}
		assertTrue(matrix.getWidth() > 8);
	}
}
