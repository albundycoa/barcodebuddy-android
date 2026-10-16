package de.bulling.barcodebuddyscanner;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import java.util.ArrayList;
import java.util.List;

import de.bulling.barcodebuddyscanner.Data.ShoppingItem;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.PermissionHelper;
import de.bulling.barcodebuddyscanner.Helper.PurchaseSender;
import de.bulling.barcodebuddyscanner.Helper.ScanFeedback;
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

    private RecyclerView itemList;
    private TextView emptyView;
    private TextView btHint;
    private TextView progressText;
    private LinearProgressIndicator progress;
    private MaterialButton sendButton;
    private MaterialButton cameraButton;

    private List<ShoppingItem> currentItems = new ArrayList<>();
    private boolean sending = false;

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
    }

    @Override
    protected void onStart() {
        super.onStart();
        repo.addListener(this);
        refreshList();
        if (activeSender != null) {
            activeSender.attach(senderListener);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        repo.removeListener(this);
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
                checkIfBarcodeScannerEnabled();
                break;
        }
    }

    private void checkIfBarcodeScannerEnabled() {
        // Scan-Methode aus den Einstellungen: Barcodescanner, Kamera oder beides
        useBarcodeScanner = SharedPrefHelper.isBtScannerEnabled(this);
        boolean useCamera = SharedPrefHelper.isCameraScannerEnabled(this);
        btHint.setVisibility(useBarcodeScanner ? View.VISIBLE : View.GONE);
        cameraButton.setVisibility(useCamera ? View.VISIBLE : View.GONE);
        if (useCamera && useBarcodeScanner) {
            emptyView.setText(R.string.list_empty);
        } else if (useCamera) {
            emptyView.setText(R.string.list_empty_camera_only);
        } else {
            emptyView.setText(R.string.list_empty_scanner_only);
        }
        if (useBarcodeScanner) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
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
        emptyView = findViewById(R.id.empty_view);
        btHint = findViewById(R.id.bt_hint);
        progressText = findViewById(R.id.progress_text);
        progress = findViewById(R.id.progress);
        sendButton = findViewById(R.id.button_send);
        cameraButton = findViewById(R.id.button_camera);

        adapter = new ItemAdapter(this);
        itemList.setLayoutManager(new LinearLayoutManager(this));
        itemList.setAdapter(adapter);
        new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                  @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public int getSwipeDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return sending ? 0 : super.getSwipeDirs(recyclerView, viewHolder);
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getBindingAdapterPosition();
                if (position != RecyclerView.NO_POSITION) {
                    onDelete(adapter.getItem(position));
                }
            }
        }).attachToRecyclerView(itemList);

        cameraButton.setOnClickListener(v -> startActivity(new Intent(this, ContinuousCaptureActivity.class)));
        sendButton.setOnClickListener(v -> confirmSend());
    }

    // ---------------------------------------------------------------- Liste

    private void refreshList() {
        repo.getOpenItems(items -> {
            currentItems = items;
            adapter.submit(items);
            updateListState();
        });
    }

    private int openUnits() {
        int units = 0;
        for (ShoppingItem item : currentItems) {
            units += Math.max(0, item.quantity - item.sentCount);
        }
        return units;
    }

    private void updateListState() {
        boolean empty = currentItems.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        itemList.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        sendButton.setEnabled(!empty && !sending);
        sendButton.setText(empty ? getString(R.string.button_send)
                : getString(R.string.button_send_count, openUnits()));
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
        if (sending || currentItems.isEmpty()) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.confirm_send_title)
                .setMessage(getString(R.string.confirm_send_message, currentItems.size(), openUnits()))
                .setPositiveButton(R.string.send, (d, w) -> startSend())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startSend() {
        activeSender = new PurchaseSender(new SharedPrefHelper(this).initBBApi(), repo);
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
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.send_result_title)
                .setMessage(message.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
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

    private void processKeypress(final KeyEvent event, final int keycode) {
        if (keycode == KeyEvent.KEYCODE_ENTER || keycode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (!receivedBarcode.isEmpty()) {
                    feedback.beep();
                    repo.addScan(receivedBarcode, null);
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
