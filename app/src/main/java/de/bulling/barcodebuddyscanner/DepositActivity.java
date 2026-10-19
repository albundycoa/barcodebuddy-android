package de.bulling.barcodebuddyscanner;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.bulling.barcodebuddyscanner.Data.ShoppingRepository;
import de.bulling.barcodebuddyscanner.Data.ShoppingRepository.Option;
import de.bulling.barcodebuddyscanner.Helper.Deposit;
import de.bulling.barcodebuddyscanner.Helper.EdgeToEdgeHelper;
import de.bulling.barcodebuddyscanner.Helper.Money;
import de.bulling.barcodebuddyscanner.Helper.SharedPrefHelper;

/**
 * Pfand je Produktgruppe: Alle Gruppen aus Grocy stehen mit einem Betragsfeld in der Liste, ein ausgefülltes Feld
 * bedeutet "Pfand". Neue Gruppen in Grocy erscheinen nach der nächsten Synchronisierung automatisch. Gespeichert wird
 * sofort bei jeder Eingabe.
 */
public class DepositActivity extends AppCompatActivity {

	private final List<Option>       groups = new ArrayList<>();
	private final Map<Long, Double>  rules  = new LinkedHashMap<>();
	private SharedPrefHelper         prefs;
	private ShoppingRepository       repo;
	private Adapter                  adapter;
	private TextView                 emptyView;

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

		emptyView = findViewById(R.id.empty);
		RecyclerView list = findViewById(R.id.list);
		EdgeToEdgeHelper.applyInsetsAsPadding(list, true, false, true, true);
		adapter = new Adapter();
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);

		repo.getOptions("groups", options -> {
			groups.clear();
			groups.addAll(options);
			adapter.notifyDataSetChanged();
			emptyView.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
		});
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

	private void save() {
		prefs.setDepositRules(rules);
	}

	private class Adapter extends RecyclerView.Adapter<Adapter.ViewHolder> {

		@NonNull
		@Override
		public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
			return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_deposit, parent, false));
		}

		@Override
		public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
			final Option group = groups.get(position);
			holder.name.setText(group.name);
			// Während des Befüllens darf der Beobachter nichts speichern
			holder.bound = false;
			Double amount = rules.get(group.id);
			holder.amount.setText(amount == null ? "" : Money.formatPlain(amount));
			holder.bound = true;
			holder.groupId = group.id;
		}

		@Override
		public int getItemCount() {
			return groups.size();
		}

		class ViewHolder extends RecyclerView.ViewHolder {
			final TextView name;
			final EditText amount;
			boolean        bound;
			long           groupId;

			ViewHolder(View view) {
				super(view);
				name   = view.findViewById(R.id.deposit_group);
				amount = view.findViewById(R.id.deposit_amount);
				amount.addTextChangedListener(new SimpleTextWatcher() {
					@Override
					public void onChanged(String text) {
						if (!bound) {
							return;
						}
						Double value = Money.parse(text);
						if (value == null || value <= 0) {
							rules.remove(groupId);
						} else {
							rules.put(groupId, value);
						}
						save();
					}
				});
			}
		}
	}
}
