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
 * Hält das Display der Uhr an, solange die Seite offen ist: Es wird gedimmt, wenn die Uhr eine Weile still liegt oder hängt, und
 * wieder hell, sobald du sie bewegst (anhebst) oder tippst. Entscheidend ist die Bewegung, nicht die Lage: Auch auf dem Tisch
 * mit dem Display nach oben wird gedimmt. Der normale Ambient-Modus von Wear OS lässt die App
 * auf manchen Uhren zurück zum Zifferblatt fallen, deshalb bleibt hier das Display selbst an.
 *
 * <p>Damit der Akku nicht leerläuft, schaltet sich das Anlassen nach {@link #IDLE_OFF_MS} im gedimmten Zustand ohne
 * Berührung und ohne neue Daten wieder aus; danach geht das Display wie gewohnt aus.
 */
final class ScreenKeeper implements SensorEventListener {

	/** Helligkeit im gedimmten Zustand (0 = dunkelstes, 1 = hellstes). */
	private static final float DIM_BRIGHTNESS = 0.05f;
	/** So stark muss sich die Beschleunigung zwischen zwei Messungen ändern (m/s²), damit es als Bewegung zählt (Anheben). */
	private static final float MOVEMENT_THRESHOLD = 4.0f;
	/** So lange bleibt das Display nach einer Bewegung hell. */
	private static final long MOVEMENT_BRIGHT_MS = 6_000;
	private static final long TOUCH_BRIGHT_MS = 10_000;
	private static final long IDLE_OFF_MS   = 15 * 60_000;

	private final Activity activity;
	private final SensorManager sensors;
	private final Handler handler = new Handler(Looper.getMainLooper());

	private boolean running;
	/** FLAG_KEEP_SCREEN_ON ist gesetzt (jedes Setzen löst sonst ein neues Layout des Fensters aus) */
	private boolean keeping;
	private float lastX, lastY, lastZ;
	private boolean haveLast;
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
		keep(true);
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
		haveLast = false;
		handler.removeCallbacksAndMessages(null);
		if (sensors != null) {
			sensors.unregisterListener(this);
		}
		keep(false);
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
			if (now < brightUntil) {
				// Gerade bewegt oder berührt: später noch einmal prüfen
				scheduleDimCheck(Math.max(500, brightUntil - now));
				return;
			}
			if (!dimmed) {
				setDimmed(true);
			}
			if (now - dimmedSince >= IDLE_OFF_MS) {
				// Lange unberührt: Display wieder dem System überlassen
				keep(false);
				return;
			}
			scheduleDimCheck(30_000);
		}
	};

	private void keep(boolean on) {
		if (on == keeping) {
			return;
		}
		keeping = on;
		if (on) {
			activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		} else {
			activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}
	}

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
		float x = event.values[0], y = event.values[1], z = event.values[2];
		if (haveLast) {
			float change = Math.abs(x - lastX) + Math.abs(y - lastY) + Math.abs(z - lastZ);
			if (change >= MOVEMENT_THRESHOLD) {
				// Bewegung (z. B. Uhr angehoben): hell und eine Weile so lassen
				keep(true);
				brightUntil = Math.max(brightUntil, System.currentTimeMillis() + MOVEMENT_BRIGHT_MS);
				if (dimmed) {
					setDimmed(false);
				}
				scheduleDimCheck(MOVEMENT_BRIGHT_MS);
			}
		}
		lastX = x;
		lastY = y;
		lastZ = z;
		haveLast = true;
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}
}
