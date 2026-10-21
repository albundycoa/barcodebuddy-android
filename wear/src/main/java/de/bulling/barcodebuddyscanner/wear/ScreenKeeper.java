package de.bulling.barcodebuddyscanner.wear;

import android.app.Activity;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;

/**
 * Hält das Display der Uhr an, solange die Seite offen ist: Es wird gedimmt, wenn du die Uhr unten hältst, und wieder hell,
 * sobald du sie anhebst (das Display zeigt dann zu dir) oder tippst. Der normale Ambient-Modus von Wear OS lässt die App
 * auf manchen Uhren zurück zum Zifferblatt fallen, deshalb bleibt hier das Display selbst an.
 *
 * <p>Damit der Akku nicht leerläuft, schaltet sich das Anlassen nach {@link #IDLE_OFF_MS} im gedimmten Zustand ohne
 * Berührung und ohne neue Daten wieder aus; danach geht das Display wie gewohnt aus.
 */
final class ScreenKeeper implements SensorEventListener {

	/** Helligkeit im gedimmten Zustand (0 = dunkelstes, 1 = hellstes). */
	private static final float DIM_BRIGHTNESS = 0.05f;
	/** Ab dieser Neigung (Beschleunigung senkrecht zum Display, m/s²) gilt die Uhr als angehoben. */
	private static final float RAISED_ABOVE = 5.0f;
	/** Darunter gilt sie als gesenkt; der Abstand zu RAISED_ABOVE verhindert Flackern. */
	private static final float LOWERED_BELOW = 3.0f;
	private static final long DIM_DELAY_MS  = 2_500;
	private static final long TOUCH_BRIGHT_MS = 10_000;
	private static final long IDLE_OFF_MS   = 15 * 60_000;

	private final Activity activity;
	private final SensorManager sensors;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private boolean running;
	private boolean raised = true;
	private boolean dimmed;
	private long    brightUntil;
	private long    dimmedSince;

	ScreenKeeper(Activity activity) {
		this.activity = activity;
		this.sensors  = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
	}

	/** Display anlassen und die Lage der Uhr beobachten. */
	void start() {
		if (running) {
			return;
		}
		running = true;
		activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		Sensor accelerometer = sensors == null ? null : sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		if (accelerometer != null) {
			sensors.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
		}
		interaction();
	}

	/** Alles zurücksetzen: Display darf wieder ausgehen. */
	void stop() {
		if (!running) {
			return;
		}
		running = false;
		handler.removeCallbacksAndMessages(null);
		if (sensors != null) {
			sensors.unregisterListener(this);
		}
		activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setDimmed(false);
	}

	/** Berührung oder neue Daten: eine Weile hell, danach (wenn die Uhr unten ist) wieder dimmen. */
	void interaction() {
		if (!running) {
			return;
		}
		brightUntil = System.currentTimeMillis() + TOUCH_BRIGHT_MS;
		setDimmed(false);
		scheduleDimCheck(TOUCH_BRIGHT_MS);
	}

	private void scheduleDimCheck(long delay) {
		handler.removeCallbacks(dimCheck);
		handler.postDelayed(dimCheck, delay);
	}

	private final Runnable dimCheck = new Runnable() {
		@Override
		public void run() {
			if (!running) {
				return;
			}
			long now = System.currentTimeMillis();
			if (raised || now < brightUntil) {
				// Noch angehoben oder gerade berührt: später noch einmal prüfen
				scheduleDimCheck(Math.max(500, brightUntil - now));
				return;
			}
			if (!dimmed) {
				setDimmed(true);
			}
			if (now - dimmedSince >= IDLE_OFF_MS) {
				// Lange unberührt: Display wieder dem System überlassen
				activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
				return;
			}
			scheduleDimCheck(30_000);
		}
	};

	private void setDimmed(boolean dim) {
		if (dim == dimmed && !(dim && dimmedSince == 0)) {
			return;
		}
		dimmed = dim;
		dimmedSince = dim ? System.currentTimeMillis() : 0;
		WindowManager.LayoutParams params = activity.getWindow().getAttributes();
		params.screenBrightness = dim ? DIM_BRIGHTNESS : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
		activity.getWindow().setAttributes(params);
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		// z zeigt senkrecht aus dem Display: Schaust du auf die Uhr, ist z deutlich positiv
		float z = event.values[2];
		if (z >= RAISED_ABOVE && !raised) {
			raised = true;
			activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
			setDimmed(false);
		} else if (z < LOWERED_BELOW && raised) {
			raised = false;
			scheduleDimCheck(DIM_DELAY_MS);
		}
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}
}
