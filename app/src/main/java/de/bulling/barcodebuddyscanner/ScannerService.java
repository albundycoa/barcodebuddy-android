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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.InventorySession;
import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.ScanFeedback;
import de.bulling.barcodebuddyscanner.Helper.ScanNotifier;
import de.bulling.barcodebuddyscanner.Wear.WearSync;
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

	private static final java.util.regex.Pattern BATTERY = java.util.regex.Pattern.compile("^(\\d{1,3})\\s*%$");

	/**
	 * Der Scanner sendet nach dem Einstellungscode "Verbleibender Akku" (Handbuch) seinen Akkustand als Text,
	 * zum Beispiel "61%". Liefert den Wert, oder null, wenn der Text etwas anderes ist.
	 */
	public static Integer parseBattery(String text) {
		java.util.regex.Matcher m = BATTERY.matcher(text.trim());
		if (!m.matches()) {
			return null;
		}
		int level = Integer.parseInt(m.group(1));
		return level <= 100 ? level : null;
	}

	private volatile String  deviceName = "";
	private static final int LOW_BATTERY = 20;
	private static final int BATTERY_NOTIFICATION_ID = 4;
	private static final String BATTERY_CHANNEL_ID = "scanner_battery_alert";

	/** Zuletzt gemeldeter Akkustand des Scanners in Prozent, -1 = unbekannt. */
	private static volatile int batteryLevel = -1;

	/** Akkustand für die Anzeige: nur solange der Scanner verbunden ist. */
	public static int getBatteryLevel() {
		return connected ? batteryLevel : -1;
	}

	/** Meldet einmal, wenn der Akku unter die Schwelle fällt, und nimmt die Meldung beim Laden wieder weg. */
	private void checkLowBattery(int level) {
		NotificationManagerCompat manager = NotificationManagerCompat.from(this);
		if (level > LOW_BATTERY) {
			manager.cancel(BATTERY_NOTIFICATION_ID);
			lowBatteryNotified = false;
			return;
		}
		if (lowBatteryNotified || !manager.areNotificationsEnabled()) {
			return;
		}
		lowBatteryNotified = true;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(BATTERY_CHANNEL_ID,
					getString(R.string.scanner_battery_channel), NotificationManager.IMPORTANCE_HIGH);
			// Mit Vibration, damit die Meldung auch auf der Smartwatch als Alarm ankommt
			channel.enableVibration(true);
			channel.setVibrationPattern(new long[]{0, 250, 150, 250});
			notifications.createNotificationChannel(channel);
		}
		PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class)
				.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP),
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		NotificationCompat.Builder builder = new NotificationCompat.Builder(this, BATTERY_CHANNEL_ID)
				.setSmallIcon(R.drawable.ic_scanner)
				.setContentTitle(getString(R.string.scanner_battery_low_title))
				.setContentText(getString(R.string.scanner_battery_low_text, level))
				.setContentIntent(open)
				.setAutoCancel(true)
				.setCategory(NotificationCompat.CATEGORY_STATUS)
				.setPriority(NotificationCompat.PRIORITY_HIGH);
		try {
			manager.notify(BATTERY_NOTIFICATION_ID, builder.build());
		} catch (SecurityException ignored) {
			// Benachrichtigungen nicht erlaubt
		}
	}

	private boolean lowBatteryNotified;
	private volatile java.io.OutputStream out;

	/** Befehl, auf den der Scanner mit seinem Akkustand antwortet (Inhalt des Codes "Verbleibender Akku" im Handbuch). */
	private static final String BATTERY_COMMAND = "^&037&^";

	private static volatile ScannerService current;

	/** Fragt den Akkustand des verbundenen Scanners ab. Liefert false, wenn gerade keine Verbindung besteht. */
	public static boolean requestBattery() {
		return sendCommand(BATTERY_COMMAND);
	}

	/**
	 * Schickt einen Befehl an den verbundenen Scanner: den Inhalt eines Einstellungs-QR-Codes aus dem
	 * Handbuch. Der Scanner verhält sich dann, als hätte er den Code gescannt. Liefert false, wenn gerade
	 * keine Verbindung besteht.
	 */
	public static boolean sendCommand(String command) {
		ScannerService service = current;
		if (service == null || !connected || service.out == null) {
			return false;
		}
		service.write(command);
		return true;
	}

	private void sendBatteryQuery() {
		write(BATTERY_COMMAND);
	}

	/** Alle Befehle laufen nacheinander, mit Pause dazwischen: Der Scanner braucht Zeit, um einen Code zu verarbeiten. */
	private final java.util.concurrent.ExecutorService commands = java.util.concurrent.Executors.newSingleThreadExecutor();
	private static final long COMMAND_GAP_MS = 800;

	private void write(final String command) {
		write(command, COMMAND_GAP_MS);
	}

	private void write(final String command, final long pauseAfterMs) {
		final java.io.OutputStream o = out;
		if (o == null) {
			return;
		}
		// Eine eventuelle Antwort (Akku: "61%") kommt als normaler Scan und wird in handleCode erkannt
		commands.execute(() -> {
			try {
				o.write(command.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
				o.flush();
				Thread.sleep(pauseAfterMs);
			} catch (IOException e) {
				Log.i(TAG, "Befehl an den Scanner nicht möglich: " + e.getMessage());
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private static final String CMD_AUTO_OFF_30MIN  = "^&048&^";
	private static final String CMD_AUTO_OFF_30S    = "^&043&^";
	private static final String CMD_SLEEP_NOW       = "^&041&^";

	/**
	 * Einkauf ist gesendet: Scanner auf 30 Sekunden Ausschaltzeit stellen und danach sofort in den
	 * Ruhezustand schicken, damit er nicht bis zur nächsten Nutzung Akku verbraucht. Nur, wenn die
	 * automatische Steuerung in den Einstellungen aktiv und der Scanner verbunden ist.
	 */
	public static void finishShopping(Context context) {
		ScannerService service = current;
		if (service == null || !connected || service.out == null || !SharedPrefHelper.isScannerAutoControl(context)) {
			return;
		}
		service.write(CMD_AUTO_OFF_30S, 1500);
		service.write(CMD_SLEEP_NOW, 0);
	}

	/** Fragt den Akkustand regelmäßig ab, solange der Scanner verbunden ist. */
	private final Runnable batteryPoll = new Runnable() {
		@Override
		public void run() {
			if (connected && !stopping) {
				long interval = SharedPrefHelper.getBatteryIntervalMs(ScannerService.this);
				if (interval > 0) {
					sendBatteryQuery();
					main.postDelayed(this, interval);
				}
			}
		}
	};
	private volatile boolean stopping   = false;
	private volatile boolean endedByItself = false;
	private volatile BluetoothSocket socket;
	private Thread                   worker;
	private ShoppingRepository       repo;
	private ScanFeedback             feedback;
	private NotificationManager      notifications;
	private int                      scanCount = 0;
	private boolean                  aclReceiverRegistered;

	/** Meldet Android, dass der gewählte Scanner getrennt wurde, beenden wir das blockierende Lesen sofort. */
	private final BroadcastReceiver aclDisconnected = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			BluetoothDevice device = Build.VERSION.SDK_INT >= 33
					? intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class)
					: (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
			if (device != null && device.getAddress().equalsIgnoreCase(SharedPrefHelper.getSppAddress(context))) {
				closeSocket();
			}
		}
	};
	private long                     startedAt;
	private boolean                  everConnected;

	@Override
	public void onCreate() {
		super.onCreate();
		repo = ShoppingRepository.get(this);
		feedback = new ScanFeedback(this);
		notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		ensureChannel(this);
		current = this;
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
		if (!aclReceiverRegistered) {
			ContextCompat.registerReceiver(this, aclDisconnected,
					new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED), ContextCompat.RECEIVER_EXPORTED);
			aclReceiverRegistered = true;
		}
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
		current = null;
		main.removeCallbacks(batteryPoll);
		commands.shutdown();
		stopping = true;
		running = false;
		if (aclReceiverRegistered) {
			unregisterReceiver(aclDisconnected);
			aclReceiverRegistered = false;
		}
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
			deviceName = name;
			batteryLevel = -1;
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
				out = s.getOutputStream();
				if (SharedPrefHelper.isScannerAutoControl(this)) {
					// Beim Einkaufen soll sich der Scanner nicht gleich ausschalten: 30 Minuten Ruhezeit
					write(CMD_AUTO_OFF_30MIN);
				}
				main.removeCallbacks(batteryPoll);
				if (SharedPrefHelper.getBatteryIntervalMs(this) > 0) {
					main.postDelayed(batteryPoll, 3000);
				}
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
			// Gleichbleibende Meldung statt Wechsel zwischen "Verbinde" und "nicht erreichbar"; der Grund steht im Log
			if (reason != null) {
				Log.i(TAG, "Scanner nicht erreichbar: " + reason);
			}
			publish(getString(R.string.scanner_status_connecting, name), false);
			sleep(RETRY_DELAY_MS);
		}
	}

	/** Beendet den Dienst samt Benachrichtigung; der letzte Status bleibt in der App sichtbar. */
	private void endService(String text) {
		endedByItself = true;
		publish(text, false);
		main.post(this::stopSelf);
	}

	/**
	 * Liest vom Scanner; ein Barcode endet mit Zeilenende oder einer kurzen Pause. Das Lesen blockiert
	 * absichtlich, denn nur so merkt die App, wenn der Scanner die Verbindung beendet (z. B. weil er sich
	 * nach der eingestellten Zeit ausschaltet). Eine Abfrage mit available() erkennt das nicht.
	 */
	private void readLoop(InputStream in) throws IOException {
		final StringBuilder buffer = new StringBuilder();
		final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
		ScheduledFuture<?> pending = null;
		byte[] chunk = new byte[256];
		try {
			while (!stopping) {
				int n = in.read(chunk);
				if (n < 0) {
					throw new IOException(getString(R.string.scanner_connection_closed));
				}
				synchronized (buffer) {
					for (int i = 0; i < n; i++) {
						int b = chunk[i] & 0xFF;
						if (b == '\r' || b == '\n') {
							flush(buffer);
						} else if (b >= 32) {
							buffer.append((char) b);
						}
					}
					if (pending != null) {
						pending.cancel(false);
						pending = null;
					}
					if (buffer.length() > 0) {
						// Scanner ohne Zeilenende: nach einer kurzen Pause gilt der Barcode als vollständig
						pending = timer.schedule(() -> {
							synchronized (buffer) {
								flush(buffer);
							}
						}, FLUSH_AFTER_MS, TimeUnit.MILLISECONDS);
					}
				}
			}
		} finally {
			timer.shutdownNow();
		}
	}

	private void flush(StringBuilder buffer) {
		final String code = buffer.toString().trim();
		buffer.setLength(0);
		if (code.isEmpty()) {
			return;
		}
		final Integer battery = parseBattery(code);
		if (battery != null && battery == 0) {
			// Direkt nach dem Verbinden meldet der Scanner oft 0 %, bevor der Akkumesser bereit ist: später noch einmal fragen
			main.postDelayed(this::sendBatteryQuery, 5000);
			return;
		}
		if (battery != null) {
			// Antwort auf den Akku-Code: kein Artikel, sondern Anzeige in der Statuszeile
			batteryLevel = battery;
			checkLowBattery(battery);
			WearSync.push(this);
			if (connected) {
				publish(getString(battery <= 20 ? R.string.scanner_status_connected_battery_low
						: R.string.scanner_status_connected_battery, deviceName, battery), true);
			}
			return;
		}
		main.post(() -> {
			// Läuft gerade eine Inventur, zählt der Scan dort und landet nicht in der Einkaufsliste
			final InventorySession.ScanSink inventory = InventorySession.getScanSink();
			if (inventory != null) {
				inventory.onBarcode(code);
				return;
			}
			feedback.beep();
			// Danach die Einkaufsliste in Grocy abhaken, falls der Scan einen Eintrag abdeckt
			repo.addScan(code, item -> {
				GrocySync.pushShoppingDone(getApplicationContext());
				ScanNotifier.show(getApplicationContext());
			});
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
		WearSync.push(this);
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
