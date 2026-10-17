package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.bulling.barcodebuddyscanner.Api.GrocyClient;
import de.bulling.barcodebuddyscanner.Api.GrocyProducts;
import de.bulling.barcodebuddyscanner.Api.GrocySync;
import de.bulling.barcodebuddyscanner.Api.SyncScheduler;
import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ShoppingData;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.ShoppingEntry;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.ReceiptPdf;
import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.PurchaseSender;
import de.bulling.barcodebuddyscanner.Helper.ScanFeedback;
import de.bulling.barcodebuddyscanner.Helper.ScanNotifier;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

public class MainActivity extends AppCompatActivity implements ShoppingRepository.Listener, ItemAdapter.Actions {

    private static final int RESULT_SETUP_COMPLETE = 0;
    private static final int RESULT_SETTINGS_OPENED = 1;
    private static final int MAX_QUANTITY = 999;

    /** Läuft über Drehungen und Neustarts der Activity hinweg weiter. */
    private static PurchaseSender activeSender = null;

    private ShoppingRepository repo;
    private ScanFeedback feedback;
    private ItemAdapter adapter;
    private ShoppingEntryAdapter entryAdapter;
    private TabLayout tabs;
    private ShoppingData shoppingData;
    /** 0 = Einkaufsliste aus Grocy, 1 = gescannte Artikel */
    private int currentTab = 1;

    private RecyclerView itemList;
    private SwipeRefreshLayout refresh;
    private TextView emptyView;
    private TextView btHint;
    private TextView totalBar;
    private TextView progressText;
    private LinearProgressIndicator progress;
    private MaterialButton sendButton;
    private MaterialButton cameraButton;

    private List<ShoppingItem> currentItems = new ArrayList<>();
    private boolean sending = false;

    /** Solange die App offen ist, wird die Einkaufsliste in diesem Abstand neu aus Grocy geholt. */
    private static final long POLL_MS = 120_000;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (SharedPrefHelper.hasGrocy(MainActivity.this) && SharedPrefHelper.isSyncOnOpenEnabled(MainActivity.this)
                    && !sending) {
                GrocySync.start(MainActivity.this, null);
            }
            pollHandler.postDelayed(this, POLL_MS);
        }
    };

    private boolean bluetoothPermissionAsked = false;
    private boolean useBarcodeScanner = false;
    private String receivedBarcode = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        repo = ShoppingRepository.get(this);
        setupUI();

        if (SharedPrefHelper.noApiDetailsSaved(this)) {
            showSetupScreen();
            return;
        }
        // ACCESS_LOCAL_NETWORK is optional: if denied, connecting to a
        // Barcode Buddy server on the same LAN might not succeed, but we
        // still go ahead either way.
        PermissionHelper.requestLocalNetworkPermissionIfNeeded(this);
        checkIfBarcodeScannerEnabled();
        if (SharedPrefHelper.isScanNotificationEnabled(this)) {
            PermissionHelper.requestNotificationPermissionIfNeeded(this);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        repo.addListener(this);
        refreshList();
        // Geschäfte, Namen und Preise aus Grocy auffrischen (ohne Verbindung bleibt die letzte Kopie)
        GrocySync.startIfStale(this);
        SyncScheduler.apply(this);
        pollHandler.postDelayed(poll, POLL_MS);
        if (activeSender != null) {
            activeSender.attach(senderListener);
        }
        ScannerService.addStatusListener(sppStatusListener);
        if (!SharedPrefHelper.noApiDetailsSaved(this)) {
            ensureSppService();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        repo.removeListener(this);
        pollHandler.removeCallbacks(poll);
        ScannerService.removeStatusListener(sppStatusListener);
        if (activeSender != null) {
            activeSender.detach();
        }
    }

    @Override
    public void onDataChanged() {
        refreshList();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case RESULT_SETUP_COMPLETE:
                if (SharedPrefHelper.noApiDetailsSaved(this))
                    finish();
                else {
                    PermissionHelper.requestLocalNetworkPermissionIfNeeded(this);
                    checkIfBarcodeScannerEnabled();
                }
                break;
            case RESULT_SETTINGS_OPENED:
                feedback.reloadPrefs();
                if (SharedPrefHelper.noApiDetailsSaved(this)) {
                    // In den Einstellungen von Barcode Buddy abgemeldet: neu koppeln
                    showSetupScreen();
                    break;
                }
                checkIfBarcodeScannerEnabled();
                break;
        }
    }

    private void checkIfBarcodeScannerEnabled() {
        // Scan-Methode aus den Einstellungen: Barcodescanner, Kamera oder beides
        useBarcodeScanner = SharedPrefHelper.isBtScannerEnabled(this);
        boolean useCamera = SharedPrefHelper.isCameraScannerEnabled(this);
        boolean useSpp = SharedPrefHelper.isSppScannerEnabled(this);
        btHint.setVisibility(useBarcodeScanner || useSpp ? View.VISIBLE : View.GONE);
        String sppStatus = ScannerService.getStatusText();
        if (useSpp) {
            setScannerBanner(sppStatus.isEmpty() ? getString(R.string.scanner_standby_idle) : sppStatus,
                    ScannerService.isConnected());
        } else {
            btHint.setText(R.string.bt_scanner_active);
            setBannerColors(R.attr.colorSecondaryContainer, R.attr.colorOnSecondaryContainer);
        }
        cameraButton.setVisibility(useCamera ? View.VISIBLE : View.GONE);
        ensureSppService();
        updateEmptyText();
        if (useBarcodeScanner) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    /**
     * Startet den Dienst für den seriellen (SPP) Scanner, wenn diese Scan-Methode gewählt ist,
     * und beendet ihn sonst. Fehlende Bluetooth-Berechtigungen werden einmal angefragt.
     */
    private void ensureSppService() {
        if (!SharedPrefHelper.isSppScannerEnabled(this)) {
            if (ScannerService.isRunning()) {
                ScannerService.stop(this);
            }
            return;
        }
        if (!PermissionHelper.hasBluetoothConnectPermission(this)) {
            if (!bluetoothPermissionAsked) {
                bluetoothPermissionAsked = true;
                PermissionHelper.requestBluetoothPermissions(this);
            } else {
                setScannerBanner(getString(R.string.scanner_status_no_permission), false);
            }
            return;
        }
        if (!bluetoothPermissionAsked) {
            // Benachrichtigungen (Android 13+) einmal anfragen, damit die Dienst-Meldung sichtbar ist
            bluetoothPermissionAsked = true;
            PermissionHelper.requestBluetoothPermissions(this);
        }
        if (SharedPrefHelper.getSppAddress(this).isEmpty()) {
            setScannerBanner(getString(R.string.scanner_status_no_device), false);
            return;
        }
        if (!ScannerService.isRunning()) {
            ScannerService.start(this);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PermissionHelper.REQUEST_CODE_BLUETOOTH) {
            ensureSppService();
        }
    }

    private final ScannerService.StatusListener sppStatusListener = (text, connected) -> {
        if (SharedPrefHelper.isSppScannerEnabled(this)) {
            setScannerBanner(text.isEmpty() ? getString(R.string.scanner_standby_idle) : text, connected);
        }
    };

    /** Statuszeile des Scanners: grün, solange er verbunden ist, sonst rot. */
    private void setScannerBanner(String text, boolean connected) {
        btHint.setText(text);
        btHint.setBackgroundColor(getColor(connected ? R.color.scanner_ok_bg : R.color.scanner_off_bg));
        btHint.setTextColor(getColor(connected ? R.color.scanner_ok_text : R.color.scanner_off_text));
    }

    private void setBannerColors(int backgroundAttr, int textAttr) {
        android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(backgroundAttr, value, true);
        btHint.setBackgroundColor(value.data);
        getTheme().resolveAttribute(textAttr, value, true);
        btHint.setTextColor(value.data);
    }

    private void setupUI() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Pad the AppBarLayout (not the Toolbar itself) below the status
        // bar: the AppBarLayout is wrap_content so it grows to absorb the
        // inset, whereas the Toolbar has a fixed actionBarSize height and
        // would just have its content (e.g. the overflow menu) squeezed
        // out of view if padded directly.
        View appBar = findViewById(R.id.app_bar_main);
        EdgeToEdgeHelper.applyInsetsAsPadding(appBar, false, true, false, false);
        // Die Buttonleiste unten darf nicht unter der Navigationsleiste liegen
        EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.bottom_bar), true, false, true, true);

        feedback = new ScanFeedback(this);

        itemList = findViewById(R.id.item_list);
        refresh = findViewById(R.id.refresh);
        refresh.setColorSchemeColors(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, 0));
        refresh.setProgressBackgroundColorSchemeColor(
                MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHigh, 0));
        refresh.setOnRefreshListener(this::refreshFromGrocy);
        emptyView = findViewById(R.id.empty_view);
        btHint = findViewById(R.id.bt_hint);
        totalBar = findViewById(R.id.total_bar);
        // Antippen der Statuszeile startet die Scannersuche neu, falls der Dienst ruht
        btHint.setOnClickListener(v -> {
            if (SharedPrefHelper.isSppScannerEnabled(this) && !ScannerService.isRunning()) {
                ensureSppService();
            }
        });
        progressText = findViewById(R.id.progress_text);
        progress = findViewById(R.id.progress);
        sendButton = findViewById(R.id.button_send);
        cameraButton = findViewById(R.id.button_camera);

        adapter = new ItemAdapter(this, this);
        entryAdapter = new ShoppingEntryAdapter(this, this::onToggleEntry);
        itemList.setLayoutManager(new LinearLayoutManager(this));
        itemList.setAdapter(adapter);

        tabs = findViewById(R.id.tabs);
        tabs.addTab(tabs.newTab().setText(R.string.tab_shopping));
        tabs.addTab(tabs.newTab().setText(R.string.tab_scanned));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                showTab(tab.getPosition());
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        setupTabSwipe();
        cameraButton.setOnClickListener(v -> startActivity(new Intent(this, ContinuousCaptureActivity.class)));
        sendButton.setOnClickListener(v -> confirmSend());
        // Mit Grocy startet die App in der Einkaufsliste, sonst bei den gescannten Artikeln
        showTab(SharedPrefHelper.hasGrocy(this) ? 0 : 1);
    }

    // ---------------------------------------------------------------- Liste

    private void refreshList() {
        repo.getOpenList(list -> {
            currentItems = list.items;
            adapter.submit(list);
            updateListState();
        });
        repo.getShoppingList(data -> {
            shoppingData = data;
            entryAdapter.submit(data);
            updateTabs();
            updateListState();
            // Abgehakte Einträge (von Hand oder durch Scan) sofort an Grocy melden
            if (!sending && SharedPrefHelper.hasGrocy(this)) {
                GrocySync.pushShoppingDone(this);
            }
        });
    }

    /** Herunterziehen: Einkaufsliste, Produkte, Bestand und Preise neu aus Grocy laden. */
    private void refreshFromGrocy() {
        if (!SharedPrefHelper.hasGrocy(this)) {
            refresh.setRefreshing(false);
            return;
        }
        GrocySync.start(this, (ok, message) -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            refresh.setRefreshing(false);
            if (!ok) {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Reiter nur zeigen, wenn Grocy gekoppelt ist; Titel mit Anzahl der offenen Einträge. */
    private void updateTabs() {
        boolean grocy = SharedPrefHelper.hasGrocy(this);
        tabs.setVisibility(grocy ? View.VISIBLE : View.GONE);
        if (!grocy) {
            if (currentTab != 1) {
                showTab(1);
            }
            return;
        }
        int open = 0;
        if (shoppingData != null) {
            for (ShoppingEntry e : shoppingData.entries) {
                if (!e.isDone()) {
                    open++;
                }
            }
        }
        TabLayout.Tab shopping = tabs.getTabAt(0);
        TabLayout.Tab scanned = tabs.getTabAt(1);
        if (shopping != null) {
            shopping.setText(getString(R.string.tab_shopping_count, open));
        }
        if (scanned != null) {
            scanned.setText(getString(R.string.tab_scanned_count, currentItems.size()));
        }
    }

    // ---------------------------------------------------------------- Wischen zwischen den Tabs

    private GestureDetector tabSwipe;

    /** Nach links oder rechts wischen wechselt zwischen Einkaufsliste und Gescannt (in beide Richtungen im Kreis). */
    private void setupTabSwipe() {
        final float density = getResources().getDisplayMetrics().density;
        tabSwipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (e1 == null || e2 == null || sending) {
                    return false;
                }
                float dx = e2.getX() - e1.getX();
                float dy = e2.getY() - e1.getY();
                if (Math.abs(dx) > 90 * density && Math.abs(dx) > 1.6f * Math.abs(dy)
                        && Math.abs(velocityX) > 300 * density) {
                    showTab(currentTab == 0 ? 1 : 0);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (tabSwipe != null) {
            tabSwipe.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private void showTab(int tab) {
        currentTab = tab;
        itemList.setAdapter(tab == 0 ? entryAdapter : adapter);
        TabLayout.Tab selected = tabs.getTabAt(tab);
        if (selected != null && !selected.isSelected()) {
            selected.select();
        }
        updateListState();
    }

    /** Eintrag von Hand abhaken oder wieder öffnen. */
    private void onToggleEntry(ShoppingEntry entry) {
        if (sending) {
            return;
        }
        if (entry.isCoveredByScans()) {
            Toast.makeText(this, R.string.entry_done_by_scan, Toast.LENGTH_SHORT).show();
            return;
        }
        repo.setShoppingDone(entry.id, !entry.isDone());
    }

    /** Einträge, die von Hand abgehakt wurden und beim Senden an Grocy übertragen werden. */
    private int manualDoneCount() {
        int count = 0;
        if (shoppingData != null) {
            for (ShoppingEntry e : shoppingData.entries) {
                if (e.needsBooking()) {
                    count++;
                }
            }
        }
        return count;
    }

    private int openUnits() {
        int units = 0;
        for (ShoppingItem item : currentItems) {
            units += Math.max(0, item.quantity - item.sentCount);
        }
        return units;
    }

    private void updateListState() {
        boolean grocyTab = currentTab == 0 && SharedPrefHelper.hasGrocy(this);
        boolean empty = grocyTab ? (shoppingData == null || shoppingData.entries.isEmpty()) : currentItems.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (grocyTab) {
            emptyView.setText(R.string.shopping_empty);
            if (!empty) {
                int unsynced = 0;
                for (ShoppingEntry e : shoppingData.entries) {
                    if (e.needsUpload()) {
                        unsynced++;
                    }
                }
                String text = getString(R.string.shopping_progress, entryAdapter.getDoneCount(),
                        entryAdapter.getTotalCount());
                text += "  ·  " + getString(R.string.shopping_value, Money.format(shoppingData.totalValue()));
                if (unsynced > 0) {
                    text += "  ·  " + getString(R.string.shopping_unsynced, unsynced);
                }
                totalBar.setText(text);
                totalBar.setVisibility(View.VISIBLE);
            } else {
                totalBar.setVisibility(View.GONE);
            }
        } else {
            updateEmptyText();
            updateTotalBar();
        }
        int pending = openUnits() + manualDoneCount();
        sendButton.setEnabled(pending > 0 && !sending);
        sendButton.setText(pending == 0 ? getString(R.string.button_send)
                : getString(R.string.button_send_count, pending));
    }

    /** Hinweistext der leeren Liste der gescannten Artikel, passend zur gewählten Scan-Methode. */
    private void updateEmptyText() {
        boolean useCamera = SharedPrefHelper.isCameraScannerEnabled(this);
        boolean useScanner = SharedPrefHelper.isBtScannerEnabled(this) || SharedPrefHelper.isSppScannerEnabled(this);
        if (useCamera && useScanner) {
            emptyView.setText(R.string.list_empty);
        } else if (useCamera) {
            emptyView.setText(R.string.list_empty_camera_only);
        } else {
            emptyView.setText(R.string.list_empty_scanner_only);
        }
    }

    /** Gesamtbetrag oben: was der Einkauf voraussichtlich kostet. */
    private void updateTotalBar() {
        if (currentItems.isEmpty()) {
            totalBar.setVisibility(View.GONE);
            return;
        }
        ReceiptPdf.Totals totals = ReceiptPdf.totals(currentItems);
        String text = getString(R.string.total_estimate, Money.format(totals.sum));
        if (totals.withoutPrice > 0) {
            text += "  ·  " + getString(R.string.total_missing, totals.withoutPrice);
        }
        totalBar.setText(text);
        totalBar.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- Kassenbon

    private final ActivityResultLauncher<String> receiptLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/pdf"), uri -> {
                if (uri != null) {
                    writeReceipt(uri);
                }
            });

    private void exportReceipt() {
        if (currentItems.isEmpty()) {
            Toast.makeText(this, R.string.receipt_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        receiptLauncher.launch("Einkaufsbeleg_"
                + new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(new Date()) + ".pdf");
    }

    private void writeReceipt(Uri uri) {
        try (OutputStream out = getContentResolver().openOutputStream(uri)) {
            ReceiptPdf.write(this, out, currentItems, System.currentTimeMillis());
            Toast.makeText(this, R.string.receipt_saved, Toast.LENGTH_LONG).show();
        } catch (IOException | RuntimeException e) {
            Toast.makeText(this, R.string.receipt_failed, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onIncrease(ShoppingItem item) {
        repo.setQuantity(item.id, Math.min(item.quantity + 1, MAX_QUANTITY));
    }

    @Override
    public void onDecrease(ShoppingItem item) {
        repo.setQuantity(item.id, item.quantity - 1);
    }

    @Override
    public void onEditQuantity(ShoppingItem item) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(item.quantity));
        input.setSelectAllOnFocus(true);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.qty_dialog_title)
                .setMessage(item.name != null ? item.name : item.barcode)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    try {
                        int quantity = Integer.parseInt(input.getText().toString().trim());
                        repo.setQuantity(item.id, Math.min(Math.max(quantity, 1), MAX_QUANTITY));
                    } catch (NumberFormatException ignored) {
                        // Leere oder ungültige Eingabe: Stückzahl bleibt unverändert
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- Unbekannten Barcode zuordnen

    private ShoppingItem assignItem;

    private final ActivityResultLauncher<Intent> pickProductLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                Intent data = result.getData();
                if (result.getResultCode() == RESULT_OK && data != null && assignItem != null) {
                    linkBarcodeToProduct(assignItem, data.getLongExtra(ProductsActivity.EXTRA_PRODUCT_ID, -1),
                            data.getStringExtra(ProductsActivity.EXTRA_PRODUCT_NAME),
                            data.getLongExtra(ProductsActivity.EXTRA_PRODUCT_SHOP, -1));
                }
            });

    @Override
    public void onAssign(ShoppingItem item) {
        if (new SharedPrefHelper(this).initGrocyClient() == null) {
            Toast.makeText(this, R.string.product_no_grocy, Toast.LENGTH_LONG).show();
            return;
        }
        assignItem = item;
        String[] choices = {getString(R.string.assign_new), getString(R.string.assign_existing)};
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(item.displayName() != null ? item.displayName() : item.barcode)
                .setItems(choices, (d, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, ProductCreateActivity.class)
                                .putExtra(ProductCreateActivity.EXTRA_BARCODE, item.barcode)
                                .putExtra(ProductCreateActivity.EXTRA_ITEM_ID, item.id)
                                .putExtra(ProductCreateActivity.EXTRA_NAME, item.displayName()));
                    } else {
                        pickProductLauncher.launch(new Intent(this, ProductsActivity.class)
                                .putExtra(ProductsActivity.EXTRA_PICK, true));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Fragt, in welchem Geschäft der Barcode gescannt wurde, und hängt ihn dann an das Produkt in Grocy. */
    private void linkBarcodeToProduct(final ShoppingItem item, final long productId, final String productName,
                                      final long defaultShopId) {
        repo.getOptions("shops", shops -> {
            final String[] names = new String[shops.size()];
            int checked = 0;
            for (int i = 0; i < shops.size(); i++) {
                names[i] = shops.get(i).name;
                if (shops.get(i).id == defaultShopId) {
                    checked = i;
                }
            }
            final int[] selected = {checked};
            com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle(getString(R.string.assign_to, productName))
                            .setNegativeButton(android.R.string.cancel, null);
            if (names.length == 0) {
                builder.setMessage(R.string.assign_no_shops);
            } else {
                builder.setSingleChoiceItems(names, checked, (d, which) -> selected[0] = which);
            }
            builder.setPositiveButton(R.string.assign_button, (d, w) -> {
                Long shopId = names.length == 0 ? null : shops.get(selected[0]).id;
                runLink(item, productId, shopId);
            }).show();
        });
    }

    private void runLink(final ShoppingItem item, final long productId, final Long shopId) {
        final GrocyClient client = new SharedPrefHelper(this).initGrocyClient();
        if (client == null) {
            return;
        }
        new Thread(() -> {
            try {
                GrocyProducts.linkBarcode(client, productId, item.barcode, shopId);
                runOnUiThread(() -> {
                    Toast.makeText(this, R.string.assign_done, Toast.LENGTH_LONG).show();
                    // Stammdaten neu holen: danach ist der Barcode in der Scan-Liste bekannt
                    GrocySync.start(MainActivity.this, null);
                });
            } catch (java.io.IOException e) {
                runOnUiThread(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.product_create_failed_title)
                        .setMessage(getString(R.string.product_create_failed, String.valueOf(e.getMessage())))
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
            }
        }, "barcode-link").start();
    }

    @Override
    public void onEditPrice(ShoppingItem item) {
        final View content = getLayoutInflater().inflate(R.layout.dialog_price, null);
        final TextInputEditText unitInput = content.findViewById(R.id.price_unit_input);
        final TextInputEditText totalInput = content.findViewById(R.id.price_total_input);
        TextInputLayout unitLayout = content.findViewById(R.id.price_unit_layout);
        TextView stockHint = content.findViewById(R.id.price_stock_hint);

        final String unitName = item.priceUnitName != null ? item.priceUnitName : getString(R.string.price_unit_scan);
        unitLayout.setHint(getString(R.string.price_hint_unit, unitName));
        // Eine Packung (ein Scan) enthält priceUnitsPerScan Preiseinheiten, normalerweise genau eine
        final double perScan = item.priceUnitsPerScan();
        final double units = item.quantity * perScan;

        Double current = item.effectivePrice();
        if (current != null) {
            unitInput.setText(Money.formatPlain(current));
            totalInput.setText(Money.formatPlain(current * units));
        }
        if (item.known && item.stockToPrice != 1 && item.stockUnitName != null) {
            stockHint.setText(R.string.price_stock_hint);
        } else {
            stockHint.setVisibility(View.GONE);
        }

        // Preis und Gesamtpreis hängen zusammen: Eingabe in einem Feld rechnet das andere aus
        final boolean[] updating = {false};
        unitInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                if (updating[0]) return;
                updating[0] = true;
                Double value = Money.parse(text);
                totalInput.setText(value == null ? "" : Money.formatPlain(value * units));
                updating[0] = false;
            }
        });
        totalInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void onChanged(String text) {
                if (updating[0]) return;
                updating[0] = true;
                Double value = Money.parse(text);
                unitInput.setText(value == null || units <= 0 ? "" : Money.formatPlain(value / units));
                updating[0] = false;
            }
        });

        new MaterialAlertDialogBuilder(this)
                .setTitle(item.displayName() != null ? item.displayName() : item.barcode)
                .setView(content)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    Double value = Money.parse(unitInput.getText() == null ? "" : unitInput.getText().toString());
                    // Leeres Feld: zurück zum letzten Einkaufspreis aus Grocy
                    repo.setPrice(item.id, value);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.price_reset, (d, w) -> repo.setPrice(item.id, null))
                .show();
    }

    @Override
    public void onDelete(ShoppingItem item) {
        repo.deleteItem(item.id);
        Snackbar.make(itemList, getString(R.string.item_removed, item.name != null ? item.name : item.barcode),
                        Snackbar.LENGTH_LONG)
                .setAction(R.string.undo, v -> repo.restoreItem(item))
                .show();
    }

    private void confirmClearList() {
        if (currentItems.isEmpty() || sending) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.clear_confirm_title)
                .setMessage(getString(R.string.clear_confirm_message, currentItems.size()))
                .setPositiveButton(R.string.clear, (d, w) -> repo.clearOpenList())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ---------------------------------------------------------------- Senden

    private void confirmSend() {
        int manual = manualDoneCount();
        if (sending || (currentItems.isEmpty() && manual == 0)) {
            return;
        }
        String message = currentItems.isEmpty()
                ? getString(R.string.confirm_send_message_only_manual, manual)
                : manual > 0
                ? getString(R.string.confirm_send_message_manual, currentItems.size(), openUnits(), manual)
                : getString(R.string.confirm_send_message, currentItems.size(), openUnits());
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_send_title)
                .setMessage(message)
                .setPositiveButton(R.string.send, (d, w) -> startSend())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startSend() {
        SharedPrefHelper prefs = new SharedPrefHelper(this);
        // Bekannte Produkte direkt in Grocy buchen, wenn Grocy gekoppelt und die Einstellung aktiv ist
        GrocyClient grocy = SharedPrefHelper.isDirectBookingEnabled(this) ? prefs.initGrocyClient() : null;
        activeSender = new PurchaseSender(prefs.initBBApi(), grocy, SharedPrefHelper.isManualDoneBooked(this), repo);
        setSending(true);
        activeSender.attach(senderListener);
        activeSender.start();
    }

    private void setSending(boolean value) {
        sending = value;
        adapter.setEditable(!value);
        progress.setVisibility(value ? View.VISIBLE : View.GONE);
        progressText.setVisibility(value ? View.VISIBLE : View.GONE);
        cameraButton.setEnabled(true);
        if (value) {
            progress.setIndeterminate(true);
            progressText.setText(R.string.sending_progress);
        }
        updateListState();
    }

    private final PurchaseSender.Listener senderListener = new PurchaseSender.Listener() {
        @Override
        public void onProgress(int done, int total) {
            setSending(true);
            progress.setIndeterminate(false);
            progress.setMax(Math.max(total, 1));
            progress.setProgress(done);
            progressText.setText(getString(R.string.sending_progress, done, total));
        }

        @Override
        public void onFinished(PurchaseSender.Result result) {
            activeSender = null;
            setSending(false);
            showSendResult(result);
            if (result.error == null && result.unitsSent == result.unitsTotal) {
                // Alles gesendet: Scanner auf 30 Sekunden stellen und ausschalten (falls aktiviert)
                ScannerService.finishShopping(MainActivity.this);
            }
            // Einkaufsliste, Bestand und Preise neu aus Grocy laden
            GrocySync.start(MainActivity.this, null);
        }
    };

    private void showSendResult(PurchaseSender.Result result) {
        StringBuilder message = new StringBuilder();
        if (result.error == null && result.unitsSent == result.unitsTotal) {
            message.append(getString(R.string.send_done, result.unitsSent));
            if (result.remainingItems > 0) {
                message.append("\n").append(getString(R.string.send_still_open, result.remainingItems));
            }
        } else if (result.unitsSent == 0) {
            message.append(getString(R.string.send_failed, result.error != null ? result.error : "-"));
        } else {
            message.append(getString(R.string.send_partial, result.unitsSent, result.unitsTotal,
                    result.error != null ? result.error : "-"));
        }
        if (result.warning != null) {
            message.append("\n\n").append(result.warning);
        }
        if (result.manualUnits > 0) {
            message.append("\n\n").append(getResources().getQuantityString(R.plurals.send_manual_done, result.manualUnits, result.manualUnits));
        }
        if (result.newProducts > 0) {
            message.append("\n\n").append(getResources().getQuantityString(
                    R.plurals.send_new_products, result.newProducts, result.newProducts));
        }
        MaterialAlertDialogBuilder dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.send_result_title)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null);
        if (result.newProducts > 0) {
            // Dort werden unbekannte Barcodes einem Grocy-Produkt zugeordnet oder neu angelegt
            dialog.setNeutralButton(R.string.open_barcode_buddy, (d, w) ->
                    startActivity(new Intent(Intent.ACTION_VIEW,
                            android.net.Uri.parse(new SharedPrefHelper(this).getBbWebUrl()))));
        }
        dialog.show();
    }

    // ---------------------------------------------------------------- Menü

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int itemId = item.getItemId();
        if (itemId == R.id.action_logout) {
            SharedPrefHelper.clearSettings(MainActivity.this);
            showSetupScreen();
            return true;
        } else if (itemId == R.id.action_settings) {
            Intent i = new Intent(MainActivity.this, SettingsActivity.class);
            startActivityForResult(i, RESULT_SETTINGS_OPENED);
            return true;
        } else if (itemId == R.id.action_history) {
            startActivity(new Intent(this, HistoryActivity.class));
            return true;
        } else if (itemId == R.id.action_sync_list) {
            Toast.makeText(this, R.string.grocy_syncing, Toast.LENGTH_SHORT).show();
            GrocySync.start(this, (ok, message) -> Toast.makeText(this,
                    ok ? getString(R.string.shopping_updated) : message, Toast.LENGTH_LONG).show());
            return true;
        } else if (itemId == R.id.action_scanner_control) {
            startActivity(new Intent(this, ScannerControlActivity.class));
            return true;
        } else if (itemId == R.id.action_scanner_battery) {
            if (ScannerService.requestBattery()) {
                Toast.makeText(this, R.string.scanner_battery_asking, Toast.LENGTH_SHORT).show();
            } else {
                // Keine Verbindung über den Dienst: den Code zum Abscannen zeigen
                showScannerBatteryCode();
            }
            return true;
        } else if (itemId == R.id.action_products) {
            startActivity(new Intent(this, ProductsActivity.class));
            return true;
        } else if (itemId == R.id.action_receipt) {
            exportReceipt();
            return true;
        } else if (itemId == R.id.action_clear) {
            confirmClearList();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showSetupScreen() {
        startActivityForResult(new Intent(MainActivity.this, SetupActivity.class), RESULT_SETUP_COMPLETE);
    }

    // ---------------------------------------------------------------- Bluetooth-Scanner (Tastaturmodus)

    private boolean isAllowedKeycode(int keycode) {
        return ((keycode >= KeyEvent.KEYCODE_0 && keycode <= KeyEvent.KEYCODE_9) ||
                (keycode >= KeyEvent.KEYCODE_NUMPAD_0 && keycode <= KeyEvent.KEYCODE_NUMPAD_9) ||
                (keycode >= KeyEvent.KEYCODE_A && keycode <= KeyEvent.KEYCODE_Z) ||
                keycode == KeyEvent.KEYCODE_PERIOD ||
                keycode == KeyEvent.KEYCODE_NUMPAD_SUBTRACT ||
                keycode == KeyEvent.KEYCODE_NUMPAD_DOT ||
                keycode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
                keycode == KeyEvent.KEYCODE_ENTER ||
                keycode == KeyEvent.KEYCODE_MINUS ||
                keycode == KeyEvent.KEYCODE_SEMICOLON);
    }

    /** Zeigt den Einstellungscode des Scanners für "Verbleibender Akku" (Handbuch) zum Abscannen vom Display. */
    private void showScannerBatteryCode() {
        android.widget.ImageView image = new android.widget.ImageView(this);
        image.setImageResource(R.drawable.scanner_battery_code);
        image.setAdjustViewBounds(true);
        image.setBackgroundColor(android.graphics.Color.WHITE);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setPadding(pad, pad, pad, 0);
        box.addView(image, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.scanner_battery_dialog_title)
                .setMessage(R.string.scanner_battery_dialog_text)
                .setView(box)
                .setPositiveButton(R.string.scanner_battery_close, null)
                .show();
    }

    private void processKeypress(final KeyEvent event, final int keycode) {
        if (keycode == KeyEvent.KEYCODE_ENTER || keycode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                Integer battery = ScannerService.parseBattery(receivedBarcode);
                if (battery != null) {
                    Toast.makeText(this, getString(R.string.scanner_battery_toast, battery), Toast.LENGTH_LONG).show();
                } else if (!receivedBarcode.isEmpty()) {
                    feedback.beep();
                    repo.addScan(receivedBarcode, item -> ScanNotifier.show(this));
                }
                receivedBarcode = "";
            }
        } else {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                receivedBarcode = receivedBarcode + (char) event.getUnicodeChar();
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int keycode = event.getKeyCode();
        if (useBarcodeScanner && isAllowedKeycode(keycode)) {
            processKeypress(event, keycode);
            return true;
        } else {
            return super.dispatchKeyEvent(event);
        }
    }

}
