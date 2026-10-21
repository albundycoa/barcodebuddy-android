package de.bulling.barcodebuddyscanner;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.Option;
import de.bulling.barcodebuddyscanner.Helper.AmountInput;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Pfand je Produktgruppe: Die Liste zeigt nur Gruppen, für die es Pfand gibt. Über das Plus kommt eine Gruppe mit
 * Betrag dazu, ein Tipp auf eine Zeile ändert oder löscht sie. Gespeichert wird sofort in den Einstellungen
 * ({@link SharedPrefHelper#setDepositRules}), die Berechnung steckt in {@link de.bulling.barcodebuddyscanner.Helper.Deposit}.
 */
public class DepositActivity extends AppCompatActivity {

	private final List<Option>       groups = new ArrayList<>();
	private final Map<Long, String>  names  = new HashMap<>();
	private final Map<Long, Double>  rules  = new LinkedHashMap<>();
	private final List<Long>         shown  = new ArrayList<>();
	private SharedPrefHelper         prefs;
	private ShoppingRepository       repo;
	private Adapter                  adapter;
	private TextView                 emptyView;
	private View                     content;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_deposit);
		prefs = new SharedPrefHelper(this);
		repo  = ShoppingRepository.get(this);
		rules.putAll(prefs.getDepositRules());

		Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		EdgeToEdgeHelper.applyInsetsAsPadding(findViewById(R.id.app_bar), false, true, false, false);
		content = findViewById(R.id.content);
		EdgeToEdgeHelper.applyInsetsAsPadding(content, true, false, true, true);

		emptyView = findViewById(R.id.empty);
		RecyclerView list = findViewById(R.id.list);
		adapter = new Adapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		findViewById(R.id.add).setOnClickListener(v -> showAddDialog());

		repo.getOptions("groups", options -> {
			groups.clear();
			groups.addAll(options);
			names.clear();
			for (Option option : options) {
				names.put(option.id, option.name);
			}
			refresh();
		});
		refresh();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	protected void onPause() {
		super.onPause();
		// Liste und Summen neu berechnen lassen
		repo.reload();
	}

	private String groupName(long id) {
		String name = names.get(id);
		return name != null ? name : getString(R.string.deposit_group_missing, id);
	}

	/** Baut die angezeigte Reihenfolge (nach Name) neu auf. */
	private void refresh() {
		shown.clear();
		shown.addAll(rules.keySet());
		Collections.sort(shown, (a, b) -> groupName(a).compareToIgnoreCase(groupName(b)));
		adapter.notifyDataSetChanged();
		emptyView.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private void save() {
		prefs.setDepositRules(rules);
		refresh();
	}

	// ---------------------------------------------------------------- Dialoge

	private void showAddDialog() {
		final List<Option> free = new ArrayList<>();
		for (Option option : groups) {
			if (!rules.containsKey(option.id)) {
				free.add(option);
			}
		}
		if (groups.isEmpty()) {
			Toast.makeText(this, R.string.deposit_no_groups, Toast.LENGTH_LONG).show();
			return;
		}
		if (free.isEmpty()) {
			Toast.makeText(this, R.string.deposit_all_used, Toast.LENGTH_LONG).show();
			return;
		}
		showDialog(null, free);
	}

	private void showEditDialog(final long groupId) {
		showDialog(groupId, null);
	}

	/**
	 * Eine Gruppe mit Betrag erfassen (groupId == null, Auswahl aus free) oder den Betrag einer vorhandenen Gruppe
	 * ändern. Das Fenster bleibt bei ungültiger Eingabe offen.
	 */
	private void showDialog(final Long groupId, final List<Option> free) {
		final View view = getLayoutInflater().inflate(R.layout.dialog_deposit, null);
		final TextInputLayout groupLayout = view.findViewById(R.id.deposit_group_layout);
		final MaterialAutoCompleteTextView groupInput = view.findViewById(R.id.deposit_group_input);
		final TextInputLayout amountLayout = view.findViewById(R.id.deposit_amount_layout);
		final TextInputEditText amountInput = view.findViewById(R.id.deposit_amount_input);
		AmountInput.apply(amountInput);

		final Option[] selected = {null};
		if (groupId == null) {
			ArrayAdapter<Option> adapter = new ArrayAdapter<>(this,
					com.google.android.material.R.layout.m3_auto_complete_simple_item, free);
			groupInput.setAdapter(adapter);
			groupInput.setOnItemClickListener((parent, v, position, id) -> {
				selected[0] = (Option) parent.getItemAtPosition(position);
				groupLayout.setError(null);
				amountInput.requestFocus();
			});
		} else {
			groupInput.setText(groupName(groupId));
			groupInput.setEnabled(false);
			Double current = rules.get(groupId);
			if (current != null) {
				amountInput.setText(Money.formatPlain(current));
			}
		}

		MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
				.setTitle(groupId == null ? R.string.deposit_add : R.string.deposit_edit_title)
				.setView(view)
				.setPositiveButton(R.string.deposit_save, null)
				.setNegativeButton(android.R.string.cancel, null);
		if (groupId != null) {
			builder.setNeutralButton(R.string.deposit_delete, (d, w) -> delete(groupId));
		}
		final AlertDialog dialog = builder.create();
		dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
			long id = groupId != null ? groupId : (selected[0] != null ? selected[0].id : -1);
			if (id < 0) {
				groupLayout.setError(getString(R.string.deposit_err_group));
				return;
			}
			Double amount = Money.parse(String.valueOf(amountInput.getText()));
			if (amount == null || amount <= 0) {
				amountLayout.setError(getString(R.string.deposit_err_amount));
				return;
			}
			rules.put(id, amount);
			save();
			dialog.dismiss();
		}));
		dialog.show();
		if (groupId != null) {
			amountInput.requestFocus();
		}
	}

	/** Entfernt das Pfand einer Gruppe; über die Meldung unten lässt sich das zurücknehmen. */
	private void delete(final long groupId) {
		final Double old = rules.remove(groupId);
		if (old == null) {
			return;
		}
		save();
		Snackbar.make(content, getString(R.string.deposit_deleted, groupName(groupId)), Snackbar.LENGTH_LONG)
				.setAction(R.string.deposit_undo, v -> {
					rules.put(groupId, old);
					save();
				}).show();
	}

	// ---------------------------------------------------------------- Liste

	private class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_deposit, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			final long groupId = shown.get(position);
			Double amount = rules.get(groupId);
			holder.name.setText(groupName(groupId));
			holder.amount.setText(amount == null ? "" : Money.format(amount));
			holder.itemView.setOnClickListener(v -> showEditDialog(groupId));
			holder.delete.setOnClickListener(v -> delete(groupId));
		}

		@Override
		public int getItemCount() {
			return shown.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView name;
			final TextView amount;
			final View     delete;

			ViewHolder(View view) {
				super(view);
				name   = view.findViewById(R.id.deposit_group);
				amount = view.findViewById(R.id.deposit_amount);
				delete = view.findViewById(R.id.deposit_delete);
			}
		}
	}
}
