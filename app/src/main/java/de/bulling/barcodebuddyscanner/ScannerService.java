package de.bulling.barcodebuddyscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.ScanFeedback;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Hält die Verbindung zu einem Barcodescanner im seriellen Bluetooth-Modus (SPP)
 * und legt jeden empfangenen Barcode in die Einkaufsliste. Läuft als Vordergrunddienst,
 * damit auch bei ausgeschaltetem Display gescannt werden kann.
 * <p>
 * Standardmäßig ist der Dienst nur "scharf", solange der Scanner verbunden ist: Wird der
 * Scanner ausgeschaltet oder getrennt, beendet sich der Dienst samt Benachrichtigung. Er startet
 * wieder, wenn sich der Scanner verbindet ({@link ScannerConnectionReceiver}) oder die App
 * geöffnet wird. Die Einstellung "Immer aktiv halten" sucht stattdessen dauerhaft.
 */
public class ScannerService extends Service {

	private static final String TAG        = "ScannerService";
	private static final String CHANNEL_ID = "scanner";
	private static final int    NOTIFICATION_ID = 1;
	private static final int    WAKE_NOTIFICATION_ID = 2;

	/** Standard-UUID des Serial Port Profile */
	private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

	private static final long RETRY_DELAY_MS = 4000;
	/** Im Modus "nur bei verbundenem Scanner": so lange wird nach dem Start auf den Scanner gewartet */
	private static final long CONNECT_GRACE_MS = 60_000;
	/** Scanner ohne Zeilenende: nach so langer Pause gilt der Barcode als vollständig */
	private static final long FLUSH_AFTER_MS = 250;

	public interface StatusListener {
		void onStatus(String text, boolean connected);
	}

	private static final Handler                           main      = new Handler(Looper.getMainLooper());
	private static final CopyOnWriteArrayList<StatusListener> listeners = new CopyOnWriteArrayList<>();
	private static volatile String  statusText = "";
	private static volatile boolean connected  = false;
	private static volatile boolean running    = false;

	public static boolean isRunning() {
		return running;
	}

	public static String getStatusText() {
		return statusText;
	}

	public static boolean isConnected() {
		return connected;
	}

	public static void addStatusListener(StatusListener l) {
		listeners.addIfAbsent(l);
		l.onStatus(statusText, connected);
	}

	public static void removeStatusListener(StatusListener l) {
		listeners.remove(l);
	}

	/**
	 * Startet den Dienst. Aus dem Hintergrund erlaubt Android das nur ausnahmsweise; scheitert es,
	 * wird eine Benachrichtigung gezeigt, über die der Nutzer die App öffnet.
	 */
	public static void start(Context context) {
		ContextCompat.startForegroundService(context, new Intent(context, ScannerService.class));
	}

	public static void stop(Context context) {
		context.stopService(new Intent(context, ScannerService.class));
	}

	/** Hinweis "Scanner verbunden", falls der Dienst nicht selbst starten durfte; Tippen öffnet die App. */
	public static void showWakeNotification(Context context) {
		if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
			return;
		}
		ensureChannel(context);
		PendingIntent open = PendingIntent.getActivity(context, 1,
				new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_scanner)
				.setContentTitle(context.getString(R.string.scanner_wake_title))
				.setContentText(context.getString(R.string.scanner_wake_text))
				.setContentIntent(open)
				.setAutoCancel(true)
				.setPriority(NotificationCompat.PRIORITY_DEFAULT)
				.build();
		try {
			NotificationManagerCompat.from(context).notify(WAKE_NOTIFICATION_ID, n);
		} catch (SecurityException ignored) {
			// Benachrichtigungen nicht erlaubt
		}
	}

	private static void ensureChannel(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager manager = (NotificationManager) context.getSystemService(NOTIFICATION_SERVICE);
			manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
					context.getString(R.string.scanner_notification_channel), NotificationManager.IMPORTANCE_DEFAULT));
		}
	}

	private volatile boolean stopping   = false;
	private volatile boolean endedByItself = false;
	private volatile BluetoothSocket socket;
	private Thread                   worker;
	private ShoppingRepository       repo;
	private ScanFeedback             feedback;
	private NotificationManager      notifications;
	private int                      scanCount = 0;
	private long                     startedAt;
	private boolean                  everConnected;

	@Override
	public void onCreate() {
		super.onCreate();
		repo = ShoppingRepository.get(this);
		feedback = new ScanFeedback(this);
		notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		ensureChannel(this);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if (!PermissionHelper.hasBluetoothConnectPermission(this)) {
			stopSelf();
			return START_NOT_STICKY;
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(),
						ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
			} else {
				startForeground(NOTIFICATION_ID, buildNotification());
			}
		} catch (RuntimeException e) {
			// Z. B. Start aus dem Hintergrund nicht erlaubt: Nutzer bitten, die App zu öffnen
			Log.w(TAG, "Vordergrunddienst konnte nicht starten", e);
			showWakeNotification(this);
			stopSelf();
			return START_NOT_STICKY;
		}
		running = true;
		notifications.cancel(WAKE_NOTIFICATION_ID);
		if (worker == null) {
			stopping = false;
			endedByItself = false;
			startedAt = System.currentTimeMillis();
			everConnected = false;
			worker = new Thread(this::runLoop, "scanner-spp");
			worker.start();
		}
		return START_NOT_STICKY;
	}

	@Override
	public void onDestroy() {
		stopping = true;
		running = false;
		closeSocket();
		if (worker != null) {
			worker.interrupt();
			worker = null;
		}
		if (!endedByItself) {
			publish("", false);
		}
		super.onDestroy();
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	// ---------------------------------------------------------------- Verbindung

	private void runLoop() {
		while (!stopping) {
			boolean alwaysOn = SharedPrefHelper.isScannerAlwaysOn(this);
			String address = SharedPrefHelper.getSppAddress(this);
			String name = SharedPrefHelper.getSppName(this);
			if (address.isEmpty()) {
				if (!alwaysOn) {
					endService(getString(R.string.scanner_status_no_device));
					return;
				}
				publish(getString(R.string.scanner_status_no_device), false);
				sleep(RETRY_DELAY_MS);
				continue;
			}
			BluetoothAdapter adapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
			if (adapter == null || !adapter.isEnabled()) {
				if (!alwaysOn) {
					endService(getString(R.string.scanner_status_bt_off));
					return;
				}
				publish(getString(R.string.scanner_status_bt_off), false);
				sleep(RETRY_DELAY_MS);
				continue;
			}
			publish(getString(R.string.scanner_status_connecting, name), false);
			String reason = null;
			boolean wasConnected = false;
			try {
				BluetoothDevice device = adapter.getRemoteDevice(address);
				BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP_UUID);
				socket = s;
				s.connect();
				everConnected = true;
				wasConnected = true;
				publish(getString(R.string.scanner_status_connected, name), true);
				readLoop(s.getInputStream());
			} catch (IOException | IllegalArgumentException e) {
				Log.i(TAG, "Verbindung beendet: " + e.getMessage());
				reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			} catch (SecurityException e) {
				Log.w(TAG, "Bluetooth-Berechtigung fehlt", e);
				reason = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
			} finally {
				closeSocket();
			}
			if (stopping) {
				return;
			}
			if (!alwaysOn) {
				// Nur scharf, solange der Scanner verbunden ist: Verbindung weg oder nie zustande gekommen
				if (wasConnected || everConnected) {
					endService(getString(R.string.scanner_standby_disconnected));
					return;
				}
				if (System.currentTimeMillis() - startedAt > CONNECT_GRACE_MS) {
					endService(getString(R.string.scanner_standby_not_found));
					return;
				}
			}
			publish(reason == null ? getString(R.string.scanner_status_retry, name)
					: getString(R.string.scanner_status_retry_reason, name, reason), false);
			sleep(RETRY_DELAY_MS);
		}
	}

	/** Beendet den Dienst samt Benachrichtigung; der letzte Status bleibt in der App sichtbar. */
	private void endService(String text) {
		endedByItself = true;
		publish(text, false);
		main.post(this::stopSelf);
	}

	/** Liest Zeichen vom Scanner; ein Barcode endet mit Zeilenende oder einer kurzen Pause. */
	private void readLoop(InputStream in) throws IOException {
		StringBuilder buffer = new StringBuilder();
		long lastByteAt = 0;
		while (!stopping) {
			if (in.available() > 0) {
				int b = in.read();
				if (b < 0) {
					throw new IOException("Verbindung geschlossen");
				}
				lastByteAt = System.currentTimeMillis();
				if (b == '\r' || b == '\n') {
					flush(buffer);
				} else if (b >= 32) {
					buffer.append((char) b);
				}
			} else {
				if (buffer.length() > 0 && System.currentTimeMillis() - lastByteAt > FLUSH_AFTER_MS) {
					flush(buffer);
				}
				if (!sleep(25)) {
					return;
				}
			}
		}
	}

	private void flush(StringBuilder buffer) {
		final String code = buffer.toString().trim();
		buffer.setLength(0);
		if (code.isEmpty()) {
			return;
		}
		main.post(() -> {
			feedback.beep();
			repo.addScan(code, null);
			scanCount++;
			updateNotification();
		});
	}

	private void closeSocket() {
		BluetoothSocket s = socket;
		socket = null;
		if (s != null) {
			try {
				s.close();
			} catch (IOException ignored) {
				// schon geschlossen
			}
		}
	}

	/** @return false, wenn der Dienst währenddessen beendet wurde */
	private boolean sleep(long millis) {
		try {
			Thread.sleep(millis);
			return !stopping;
		} catch (InterruptedException e) {
			return false;
		}
	}

	// ---------------------------------------------------------------- Status und Benachrichtigung

	private void publish(final String text, final boolean isConnected) {
		statusText = text;
		connected = isConnected;
		main.post(() -> {
			for (StatusListener l : listeners) {
				l.onStatus(text, isConnected);
			}
			if (running) {
				updateNotification();
			}
		});
	}

	private Notification buildNotification() {
		PendingIntent open = PendingIntent.getActivity(this, 0,
				new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		String status = statusText.isEmpty() ? getString(R.string.scanner_status_starting) : statusText;
		return new NotificationCompat.Builder(this, CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_scanner)
				.setContentTitle(getString(R.string.scanner_notification_title))
				.setContentText(scanCount == 0 ? status : getString(R.string.scanner_notification_count, status, scanCount))
				.setContentIntent(open)
				.setOngoing(true)
				.setOnlyAlertOnce(true)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.build();
	}

	private void updateNotification() {
		notifications.notify(NOTIFICATION_ID, buildNotification());
	}
}
